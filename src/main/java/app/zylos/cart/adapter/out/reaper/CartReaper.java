package app.zylos.cart.adapter.out.reaper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import app.zylos.cart.application.port.out.CartRepository;
import app.zylos.cart.config.ZylosCartReaperProperties;
import app.zylos.cart.domain.vo.CartId;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * Application-managed cart expiry.
 *
 * <p>The reaper emits {@code CartExpired} through the outbox and deletes the cart in
 * one atomic write.
 */
@Component
public class CartReaper {

    private static final Logger log = LoggerFactory.getLogger(CartReaper.class);

    private final ExpiredCartScanner scanner;
    private final CartRepository carts;
    private final int batchSize;
    private final RateLimiter writeLimiter;
    private final Retry retry;

    private final Counter expired;
    private final Counter skipped;
    private final Counter failures;
    private final Counter rateLimited;
    private final Counter dlqEvicted;

    public CartReaper(
            ExpiredCartScanner scanner,
            CartRepository carts,
            ZylosCartReaperProperties properties,
            MeterRegistry registry) {

        this.scanner = scanner;
        this.carts = carts;
        this.batchSize = properties.batchSize();

        // The timeoutDuration MUST be strictly greater than (batchSize / maxWritesPerSecond).
        this.writeLimiter = RateLimiter.of(
                "cart-reaper-writes",
                RateLimiterConfig.custom()
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .limitForPeriod(properties.rateLimit().maxWritesPerSecond())
                        .timeoutDuration(properties.rateLimit().timeout())
                        .build());

        this.retry = Retry.of(
                "cart-reaper-retry",
                RetryConfig.custom()
                        .maxAttempts(properties.retry().maxAttempts())
                        .waitDuration(properties.retry().waitDuration())
                        .retryExceptions(AwsServiceException.class, SdkClientException.class)
                        .build());

        this.expired = Counter.builder("zylos.cart.reaper.expired")
                .description("Carts expired: tombstoned through the outbox and deleted")
                .register(registry);
        this.skipped = Counter.builder("zylos.cart.reaper.skipped")
                .description("Carts left alone because they were modified after the sweep selected them")
                .register(registry);
        this.failures = Counter.builder("zylos.cart.reaper.failures")
                .description("Carts left for the next sweep due to transient infrastructure failures")
                .register(registry);
        this.rateLimited = Counter.builder("zylos.cart.reaper.rate_limited")
                .description("Carts dropped from sweep because the local rate-limiter queue timed out")
                .register(registry);
        this.dlqEvicted = Counter.builder("zylos.cart.reaper.dlq_evicted")
                .description("Corrupted carts permanently removed from the expiration queue")
                .register(registry);
    }

    /**
     * Returns the number of carts successfully expired.
     */
    public int sweep(Instant now) {
        List<CartId> candidates = scanner.scan(now, batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }

        List<Callable<ReapResult>> tasks = new ArrayList<>(candidates.size());
        for (CartId cartId : candidates) {
            tasks.add(() -> processCandidate(cartId));
        }

        int sweepReaped = 0;
        int sweepSkipped = 0;
        int sweepDlqEvicted = 0;
        int sweepFailed = 0;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ReapResult>> futures = executor.invokeAll(tasks);

            // Map-reduce results on the main thread to avoid concurrent mutation of primitives
            for (Future<ReapResult> future : futures) {
                if (future.state() == Future.State.SUCCESS) {
                    switch (future.resultNow()) {
                        case REAPED -> {
                            sweepReaped++;
                            expired.increment();
                        }
                        case SKIPPED -> {
                            sweepSkipped++;
                            skipped.increment();
                        }
                        case FAILED -> {
                            sweepFailed++;
                            failures.increment();
                        }
                        case DLQ_EVICTED -> {
                            sweepDlqEvicted++;
                            dlqEvicted.increment();
                        }
                        case RATE_LIMITED -> rateLimited.increment();
                    }
                } else {
                    failures.increment();
                    log.error("Virtual thread execution failed unpredictably for a cart reaper task.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cart reaper sweep was interrupted", e);
        }

        if (sweepReaped > 0 || sweepFailed > 0 || sweepDlqEvicted > 0) {
            log.info(
                    "Expired {} carts ({} skipped, {} failed, {} evicted)",
                    sweepReaped,
                    sweepSkipped,
                    sweepFailed,
                    sweepDlqEvicted);
        }
        return sweepReaped;
    }

    private ReapResult processCandidate(CartId cartId) {
        try {
            return Retry.decorateSupplier(
                            retry,
                            RateLimiter.decorateSupplier(
                                    writeLimiter,
                                    () -> carts.findById(cartId)
                                            .map(cart -> {
                                                cart.expire();
                                                return carts.expire(cart) ? ReapResult.REAPED : ReapResult.SKIPPED;
                                            })
                                            .orElse(ReapResult.SKIPPED)))
                    .get();

        } catch (RequestNotPermitted _) {
            log.warn("Rate limiter queue timeout exceeded for cart {}.", cartId);
            return ReapResult.RATE_LIMITED;
        } catch (AwsServiceException | SdkClientException e) {
            // Explicitly transient infrastructure failure (DynamoDB throttle, network drop)
            log.warn("Transient failure processing cart {}. Leaving for next sweep. Cause: {}", cartId, e.getMessage());
            return ReapResult.FAILED;

        } catch (RuntimeException e) {
            log.error(
                    "Cart {} failed with non-transient or unknown error. Evicting to DLQ. Cause: {}",
                    cartId,
                    e.getMessage());
            evictToDLQ(cartId);
            return ReapResult.DLQ_EVICTED;
        }
    }

    /**
     * Removes the item from the GSI queue so it stops blocking healthy carts.
     */
    private void evictToDLQ(CartId cartId) {
        try {
            carts.moveToDeadLetter(cartId);
        } catch (Exception fatal) {
            log.error("Failed to DLQ cart {}.", cartId, fatal);
        }
    }

    private enum ReapResult {
        REAPED,
        SKIPPED,
        FAILED,
        RATE_LIMITED,
        DLQ_EVICTED
    }
}
