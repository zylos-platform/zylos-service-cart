package app.zylos.cart.adapter.out.reaper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import app.zylos.cart.adapter.out.persistence.dynamodb.CartItem;
import app.zylos.cart.adapter.out.persistence.dynamodb.CartItemMapper;
import app.zylos.cart.adapter.out.persistence.dynamodb.CartTableSchemas;
import app.zylos.cart.adapter.out.relay.ZylosDynamodbProperties;
import app.zylos.cart.config.ZylosCartExpiryProperties;
import app.zylos.cart.domain.vo.CartId;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/**
 * Finds carts whose expiry has elapsed by sweeping static GSI shards using native
 * DynamoDB range queries.
 */
@Component
public class ExpiredCartScanner {

    private final DynamoDbIndex<CartItem> expiryIndex;
    private final ZylosCartExpiryProperties expiryProperties;

    public ExpiredCartScanner(
            DynamoDbEnhancedClient enhancedClient,
            ZylosDynamodbProperties properties,
            ZylosCartExpiryProperties expiryProperties) {
        this.expiryIndex = enhancedClient
                .table(properties.tableName(), CartTableSchemas.CART)
                .index(CartTableSchemas.GSI1_EXPIRY);
        this.expiryProperties = expiryProperties;
    }

    /**
     * Returns up to {@code limit} carts that expired at or before {@code now}, oldest first.
     */
    public List<CartId> scan(Instant now, int limit) {
        List<CartId> expired = new ArrayList<>(limit);
        String nowStr = String.valueOf(now.getEpochSecond());

        for (int shard = 0; shard < expiryProperties.readShards() && expired.size() < limit; shard++) {
            int remaining = limit - expired.size();

            QueryConditional condition = QueryConditional.sortLessThanOrEqualTo(Key.builder()
                    .partitionValue(CartItemMapper.expiryPk(shard))
                    .sortValue(nowStr)
                    .build());

            expiryIndex.query(r -> r.queryConditional(condition).limit(remaining)).stream()
                    .flatMap(page -> page.items().stream())
                    .map(item -> CartItemMapper.parseIdFromPk(item.pk()))
                    .limit(remaining)
                    .forEach(expired::add);
        }

        return expired;
    }
}
