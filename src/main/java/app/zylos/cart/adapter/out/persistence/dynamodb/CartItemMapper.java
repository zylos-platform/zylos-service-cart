package app.zylos.cart.adapter.out.persistence.dynamodb;

import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.github.f4b6a3.uuid.util.UuidUtil;

import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartLine;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.cart.domain.model.CartOwner.CustomerOwner;
import app.zylos.cart.domain.model.CartOwner.GuestOwner;
import app.zylos.cart.domain.model.CartStatus;
import app.zylos.cart.domain.vo.*;

/**
 * Maps between the {@link Cart} aggregate and its single-table {@link CartItem} representation.
 */
public final class CartItemMapper {

    private static final String TYPE_CUSTOMER = "CUSTOMER";
    private static final String TYPE_GUEST = "GUEST";
    public static final int EXPIRY_SHARDS = 8;

    private CartItemMapper() {}

    static String cartPk(CartId cartId) {
        return "CART#" + cartId.value();
    }

    public static String expiryPartition(int shard) {
        return "EXP#" + shard;
    }

    static int shardFor(String cartId) {
        return Math.floorMod(cartId.hashCode(), EXPIRY_SHARDS);
    }

    static CartItem toItem(Cart cart, Instant now) {
        String pk = cartPk(cart.id());

        String ownerType = cart.owner() instanceof CustomerOwner ? TYPE_CUSTOMER : TYPE_GUEST;
        Duration ttl = cart.owner().timeToLive();

        List<CartLineItem> lineItems =
                cart.lines().stream().map(CartItemMapper::toLineItem).toList();

        long expiresAt = now.plus(ttl).getEpochSecond();
        String cartId = cart.id().value().toString();

        return CartItem.builder()
                .pk(pk)
                .sk(pk)
                .cartId(cartId)
                .ownerType(ownerType)
                .ownerId(cart.owner().subjectId())
                .status(cart.status().name())
                .currency(cart.currency() == null ? null : cart.currency().getCurrencyCode())
                .lines(lineItems)
                .version(cart.version())
                .createdAt(UuidUtil.getInstant(cart.id().value()))
                .updatedAt(now)
                .convertedOrderId(cart.convertedOrderId())
                .mergedIntoCartId(
                        cart.mergedIntoCartId() == null
                                ? null
                                : cart.mergedIntoCartId().value().toString())
                .gsi1pk(expiryPartition(shardFor(cartId)))
                .gsi1sk(String.valueOf(expiresAt))
                .expiresAt(expiresAt)
                .build();
    }

    private static CartLineItem toLineItem(CartLine line) {
        PriceSnapshot snapshot = line.priceSnapshot();
        return CartLineItem.builder()
                .sku(line.sku().value())
                .quantity(line.quantity().value())
                .priceMinorUnits(snapshot == null ? null : snapshot.unitPrice().minorUnits())
                .priceCurrency(snapshot == null ? null : snapshot.currency().getCurrencyCode())
                .catalogVersion(snapshot == null ? null : snapshot.catalogVersion())
                .sellerId(line.sellerId())
                .origin(line.origin().name())
                .build();
    }

    public static Cart toDomain(CartItem item) {
        List<CartLine> lines = item.lines().stream().map(CartItemMapper::toLine).toList();

        CartOwner owner =
                TYPE_CUSTOMER.equals(item.ownerType()) ? new CustomerOwner(item.ownerId()) : new GuestOwner(item.ownerId());
        String mergeId = item.mergedIntoCartId();

        return Cart.reconstitute(
                CartId.of(item.cartId()),
                owner,
                CartStatus.valueOf(item.status()),
                item.currency() == null ? null : Currency.getInstance(item.currency()),
                lines,
                item.convertedOrderId(),
                mergeId == null ? null : CartId.of(mergeId),
                item.version());
    }

    private static CartLine toLine(CartLineItem lineItem) {
        PriceSnapshot snapshot = priceSnapshot(lineItem);
        return CartLine.reconstitute(
                Sku.of(lineItem.sku()),
                Quantity.of(lineItem.quantity()),
                snapshot,
                lineItem.sellerId(),
                LineOrigin.valueOf(lineItem.origin()));
    }

    private static @Nullable PriceSnapshot priceSnapshot(CartLineItem lineItem) {
        Long minorUnits = lineItem.priceMinorUnits();
        String priceCurrency = lineItem.priceCurrency();
        Long catalogVersion = lineItem.catalogVersion();

        if (minorUnits == null || priceCurrency == null || catalogVersion == null) {
            return null;
        }

        Money price = Money.ofMinor(minorUnits, Currency.getInstance(priceCurrency));
        return new PriceSnapshot(price, catalogVersion);
    }
}
