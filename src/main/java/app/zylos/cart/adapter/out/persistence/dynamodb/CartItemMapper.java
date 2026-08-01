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
    private static final String PREFIX_CART_PK = "CART#";
    private static final String PREFIX_EXPIRY_PK = "EXP#";

    private CartItemMapper() {}

    static String cartPk(CartId cartId) {
        return PREFIX_CART_PK + cartId.value();
    }

    static int shardFor(String cartId, int shard) {
        return Math.floorMod(cartId.hashCode(), shard);
    }

    public static String expiryPk(int shard) {
        return PREFIX_EXPIRY_PK + shard;
    }

    public static CartId parseIdFromPk(@Nullable String pk) {
        if (pk != null && pk.startsWith(PREFIX_CART_PK)) {
            return CartId.of(pk.substring(PREFIX_CART_PK.length()));
        }
        throw new IllegalArgumentException("Malformed DynamoDB Partition Key: " + pk);
    }

    static CartItem toItem(Cart cart, Instant now, int shard) {
        String pk = cartPk(cart.id());

        String ownerType = cart.owner() instanceof CustomerOwner ? TYPE_CUSTOMER : TYPE_GUEST;
        Duration ttl = cart.owner().timeToLive();

        List<CartLineItem> lineItems =
                cart.lines().stream().map(CartItemMapper::toLineItem).toList();

        long expiresAt = now.plus(ttl).getEpochSecond();
        String cartId = cart.id().value().toString();
        Currency cartCurrency = cart.currency();
        CartId mergedIntoCartId = cart.mergedIntoCartId();

        return CartItem.builder()
                .pk(pk)
                .sk(pk)
                .cartId(cartId)
                .ownerType(ownerType)
                .ownerId(cart.owner().subjectId())
                .status(cart.status().name())
                .currency(cartCurrency == null ? null : cartCurrency.getCurrencyCode())
                .lines(lineItems)
                .version(cart.version())
                .createdAt(UuidUtil.getInstant(cart.id().value()))
                .updatedAt(now)
                .convertedOrderId(cart.convertedOrderId())
                .mergedIntoCartId(
                        mergedIntoCartId == null
                                ? null
                                : mergedIntoCartId.value().toString())
                .gsi1pk(expiryPk(shardFor(cartId, shard)))
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

        CartOwner owner = TYPE_CUSTOMER.equals(item.ownerType())
                ? new CustomerOwner(item.ownerId())
                : new GuestOwner(item.ownerId());
        String mergedIntoCartId = item.mergedIntoCartId();

        return Cart.reconstitute(
                CartId.of(item.cartId()),
                owner,
                CartStatus.valueOf(item.status()),
                item.currency() == null ? null : Currency.getInstance(item.currency()),
                lines,
                item.convertedOrderId(),
                mergedIntoCartId == null ? null : CartId.of(mergedIntoCartId),
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
