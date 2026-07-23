package app.zylos.cart.adapter.out.catalog.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import app.zylos.cart.adapter.out.catalog.resilience.ResilientCatalogClient;
import app.zylos.cart.application.port.out.CatalogLookup;
import app.zylos.cart.application.port.out.CatalogLookupPort;
import app.zylos.cart.config.ZylosCatalogCacheProperties;
import app.zylos.cart.domain.vo.Sku;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Cache-aside over the resilient client, with two-tier TTL, negative caching, and stale-while-error.
 * A fresh hit is served directly; a stale hit refreshes, but if the delegate is {@link
 * CatalogLookup.Unavailable} the stale snapshot is served rather than degrading.
 */
@Component
@Primary
public class CachingCatalogClient implements CatalogLookupPort {

    private static final Logger log = LoggerFactory.getLogger(CachingCatalogClient.class);

    private final ResilientCatalogClient delegate;
    private final RedisTemplate<String, CatalogCacheEntry> cache;
    private final String keyPrefix;
    private final Duration foundFresh;
    private final Duration foundHard;
    private final Duration negativeFresh;
    private final Duration negativeHard;
    private final Counter staleServed;

    public CachingCatalogClient(
            ResilientCatalogClient delegate,
            RedisTemplate<String, CatalogCacheEntry> catalogCacheRedisTemplate,
            ZylosCatalogCacheProperties properties,
            MeterRegistry registry) {
        this.delegate = delegate;
        this.cache = catalogCacheRedisTemplate;
        this.keyPrefix = properties.keyPrefix();
        this.foundFresh = Duration.ofSeconds(properties.foundFreshSeconds());
        this.foundHard = Duration.ofSeconds(properties.foundHardSeconds());
        this.negativeFresh = Duration.ofSeconds(properties.negativeFreshSeconds());
        this.negativeHard = Duration.ofSeconds(properties.negativeHardSeconds());
        this.staleServed = Counter.builder("zylos.cart.catalog.cache.stale_served")
                .description("Catalog lookups served from a stale cache entry because Catalog was unavailable")
                .register(registry);
    }

    @Override
    public CatalogLookup lookup(Sku sku) {
        String key = keyPrefix + sku.value();
        long now = Instant.now().toEpochMilli();

        Optional<CatalogCacheEntry> cached = get(key);

        if (cached.isPresent()) {
            CatalogCacheEntry entry = cached.get();

            if (entry.isFresh(now)) {
                return entry.toLookup(sku);
            }
            // Stale: refresh, but fall back to the stale value if Catalog is down.
            CatalogLookup refreshed = delegate.lookup(sku);
            if (refreshed instanceof CatalogLookup.Unavailable) {
                staleServed.increment();
                return entry.toLookup(sku);
            }
            put(key, refreshed, now);
            return refreshed;
        }

        CatalogLookup result = delegate.lookup(sku);
        if (!(result instanceof CatalogLookup.Unavailable)) {
            put(key, result, now); // caches Found AND negatives; Unavailable is never cached
        }
        return result;
    }

    private Optional<CatalogCacheEntry> get(String key) {
        try {
            return Optional.ofNullable(cache.opsForValue().get(key));
        } catch (RuntimeException e) {
            log.warn("Valkey get failed for {}; treating as miss", key, e);
            return Optional.empty();
        }
    }

    private void put(String key, CatalogLookup lookup, long now) {
        boolean negative = !(lookup instanceof CatalogLookup.Found);
        Duration fresh = negative ? negativeFresh : foundFresh;
        Duration hard = negative ? negativeHard : foundHard;
        try {
            cache.opsForValue().set(key, CatalogCacheEntry.of(lookup, now + fresh.toMillis()), hard);
        } catch (RuntimeException e) {
            log.warn("Valkey put failed for {}; continuing uncached", key, e);
        }
    }
}
