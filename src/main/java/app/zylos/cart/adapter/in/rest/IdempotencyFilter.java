package app.zylos.cart.adapter.in.rest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import app.zylos.cart.application.exception.IdempotencyConflictException;
import app.zylos.cart.application.exception.IdempotencyInFlightException;
import app.zylos.cart.application.port.out.CurrentOwnerProvider;
import app.zylos.cart.application.port.out.IdempotencyStore;
import app.zylos.cart.application.port.out.IdempotencyStore.Reservation;

/**
 * Enforces {@code Idempotency-Key} on cart mutations: a retried request replays the original response
 * instead of applying the command twice.
 *
 * <p>Only successful (2xx) responses are stored. A failed command had no effect, so re-executing it on
 * retry is both harmless and correct — and it avoids the trap of pinning a transient 404 or 503 to the
 * key for a day. The reservation is released on any non-2xx outcome so the client can genuinely retry.
 *
 * <p>The fingerprint binds the key to the authenticated subject, method, path, and body, so one client
 * cannot replay another's response and key reuse with a changed payload is rejected rather than
 * silently returning the wrong answer.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20) // after CorrelationIdFilter, after Spring Security
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String HEADER = "Idempotency-Key";
    private static final Set<String> MUTATING = Set.of("POST", "PATCH", "PUT", "DELETE");

    private final IdempotencyStore store;
    private final CurrentOwnerProvider currentOwner;
    private final String pathPrefix;
    private final Duration inFlightTtl;
    private final Duration completedTtl;

    public IdempotencyFilter(
            IdempotencyStore store,
            CurrentOwnerProvider currentOwner,
            @Value("${zylos.cart.idempotency.path-prefix:/api/v1/cart}") String pathPrefix,
            @Value("${zylos.cart.idempotency.in-flight-ttl-seconds:60}") long inFlightTtlSeconds,
            @Value("${zylos.cart.idempotency.completed-ttl-hours:24}") long completedTtlHours) {
        this.store = store;
        this.currentOwner = currentOwner;
        this.pathPrefix = pathPrefix;
        this.inFlightTtl = Duration.ofSeconds(inFlightTtlSeconds);
        this.completedTtl = Duration.ofHours(completedTtlHours);
    }

    private static void writeStored(HttpServletResponse response, Reservation.Replay replay) throws IOException {
        response.setStatus(replay.status());
        if (replay.contentType() != null) {
            response.setContentType(replay.contentType());
        }
        response.setHeader("Idempotent-Replay", "true");
        response.getOutputStream().write(replay.body());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MUTATING.contains(request.getMethod())
                || !request.getRequestURI().startsWith(pathPrefix);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "Missing required header: " + HEADER);
            return;
        }

        CachedBodyHttpServletRequest bufferedRequest = new CachedBodyHttpServletRequest(request);
        String fingerprint = fingerprint(request, bufferedRequest.body());

        Reservation reservation = store.reserve(key, fingerprint, inFlightTtl);
        switch (reservation) {
            case Reservation.Conflict _ -> throw new IdempotencyConflictException(key);
            case Reservation.InFlight _ -> throw new IdempotencyInFlightException(key);
            case Reservation.Replay replay -> writeStored(response, replay);
            case Reservation.Fresh _ -> execute(key, fingerprint, bufferedRequest, response, chain);
        }
    }

    private void execute(
            String key,
            String fingerprint,
            CachedBodyHttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain)
            throws ServletException, IOException {

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(request, captured);

            int status = captured.getStatus();
            if (status >= 200 && status < 300) {
                store.complete(
                        key,
                        fingerprint,
                        status,
                        captured.getContentType(),
                        captured.getContentAsByteArray(),
                        completedTtl);
                stored = true;
            }
        } finally {
            if (!stored) {
                releaseQuietly(key);
            }
            captured.copyBodyToResponse();
        }
    }

    private void releaseQuietly(String key) {
        try {
            store.release(key);
        } catch (RuntimeException e) {
            // Already completed, or the store is unavailable; the reservation expires on its own.
            log.debug("Could not release idempotency reservation {}", key, e);
        }
    }

    private String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(currentOwner.currentOwner().subjectId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
