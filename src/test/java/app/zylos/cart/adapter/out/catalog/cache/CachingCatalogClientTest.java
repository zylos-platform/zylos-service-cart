package app.zylos.cart.adapter.out.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import app.zylos.cart.adapter.out.catalog.resilience.ResilientCatalogClient;
import app.zylos.cart.application.port.out.CatalogLookup;
import app.zylos.cart.application.port.out.CatalogSnapshot;
import app.zylos.cart.config.ZylosCatalogCacheProperties;
import app.zylos.cart.domain.vo.Money;
import app.zylos.cart.domain.vo.Sku;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CachingCatalogClientTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Sku SKU = Sku.of("SKU-1");

    private ResilientCatalogClient delegate;
    private Map<String, CatalogCacheEntry> store;
    private CachingCatalogClient client;

    private static CatalogLookup found(long minor) {
        return new CatalogLookup.Found(new CatalogSnapshot(SKU, Money.ofMinor(minor, USD), "seller-1", 1L));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        delegate = mock(ResilientCatalogClient.class);
        RedisTemplate<String, CatalogCacheEntry> template = mock(RedisTemplate.class);
        store = new HashMap<>();
        ValueOperations<String, CatalogCacheEntry> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> store.get(i.getArgument(0, String.class)));
        doAnswer(i -> {
                    store.put(i.getArgument(0), i.getArgument(1));
                    return null;
                })
                .when(ops)
                .set(anyString(), any(), any(java.time.Duration.class));

        ZylosCatalogCacheProperties props = new ZylosCatalogCacheProperties("k:", 90, 86400, 10, 60);

        client = new CachingCatalogClient(delegate, template, props, new SimpleMeterRegistry());
    }

    private void seed(CatalogCacheEntry entry) {
        store.put("k:" + SKU.value(), entry);
    }

    @Test
    void aFreshHitIsServedWithoutCallingTheDelegate() {
        seed(new CatalogCacheEntry("FOUND", 1999L, "USD", "seller-1", 1L, System.currentTimeMillis() + 60_000));

        CatalogLookup result = client.lookup(SKU);

        assertThat(result).isInstanceOf(CatalogLookup.Found.class);
        verify(delegate, never()).lookup(any());
    }

    @Test
    void aMissPopulatesTheCacheFromTheDelegate() {
        when(delegate.lookup(SKU)).thenReturn(found(1999));

        assertThat(client.lookup(SKU)).isInstanceOf(CatalogLookup.Found.class);
        assertThat(store).containsKey("k:" + SKU.value());
    }

    @Test
    void aNegativeResultIsCached() {
        when(delegate.lookup(SKU)).thenReturn(new CatalogLookup.NotFound(SKU));

        client.lookup(SKU);

        assertThat(store.get("k:" + SKU.value()).isNegative()).isTrue();
    }

    @Test
    void unavailableIsNeverCached() {
        when(delegate.lookup(SKU)).thenReturn(new CatalogLookup.Unavailable(SKU));

        assertThat(client.lookup(SKU)).isInstanceOf(CatalogLookup.Unavailable.class);
        assertThat(store).doesNotContainKey("k:" + SKU.value());
    }

    @Test
    void aStaleHitServesTheStaleValueWhenCatalogIsUnavailable() {
        seed(new CatalogCacheEntry("FOUND", 1999L, "USD", "seller-1", 1L, System.currentTimeMillis() - 1)); // stale
        when(delegate.lookup(SKU)).thenReturn(new CatalogLookup.Unavailable(SKU));

        CatalogLookup result = client.lookup(SKU);

        assertThat(result)
                .isInstanceOfSatisfying(
                        CatalogLookup.Found.class,
                        f -> assertThat(f.snapshot().unitPrice().minorUnits()).isEqualTo(1999L)); // stale-while-error
    }

    @Test
    void aStaleHitRefreshesWhenCatalogAnswers() {
        seed(new CatalogCacheEntry("FOUND", 1999L, "USD", "seller-1", 1L, System.currentTimeMillis() - 1)); // stale
        when(delegate.lookup(SKU)).thenReturn(found(2999));

        CatalogLookup result = client.lookup(SKU);

        assertThat(result)
                .isInstanceOfSatisfying(
                        CatalogLookup.Found.class,
                        f -> assertThat(f.snapshot().unitPrice().minorUnits()).isEqualTo(2999L)); // refreshed
        assertThat(store).containsKey("k:" + SKU.value());
    }

    @Test
    void anEmptyRequestNeverTouchesTheDelegate() {
        assertThat(client.lookupAll(List.of())).isEmpty();
        verify(delegate, never()).lookupAll(any());
    }
}
