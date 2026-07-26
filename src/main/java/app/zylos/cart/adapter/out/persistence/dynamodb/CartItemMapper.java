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
final class CartItemMapper {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String GUEST = "GUEST";

    private static final Duration CUSTOMER_TTL = Duration.ofDays(180);
    private static final Duration GUEST_TTL = Duration.ofDays(30);

    private CartItemMapper() {}

    static String cartPk(CartId cartId) {
        return "CART#" + cartId.value();
    }

    static CartItem toItem(Cart cart, Instant now) {
        String pk = cartPk(cart.id());

        // TODO: Consolidate the instanceof Checks

        String ownerType = cart.owner() instanceof CustomerOwner ? CUSTOMER : GUEST;
        Duration ttl = cart.owner() instanceof CustomerOwner ? CUSTOMER_TTL : GUEST_TTL;

        List<CartLineItem> lineItems =
                cart.lines().stream().map(CartItemMapper::toLineItem).toList();

        return CartItem.builder()
                .pk(pk)
                .sk(pk)
                .cartId(cart.id().value().toString())
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
                .gsi1pk("OWNER#" + ownerType + "#" + cart.owner().subjectId())
                .gsi1sk(pk)
                .expiresAt(now.plus(ttl).getEpochSecond())
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

    static Cart toDomain(CartItem item) {
        List<CartLine> lines = item.lines().stream().map(CartItemMapper::toLine).toList();

        CartOwner owner =
                CUSTOMER.equals(item.ownerType()) ? new CustomerOwner(item.ownerId()) : new GuestOwner(item.ownerId());

        return Cart.reconstitute(
                CartId.of(item.cartId()),
                owner,
                CartStatus.valueOf(item.status()),
                item.currency() == null ? null : Currency.getInstance(item.currency()),
                lines,
                item.convertedOrderId(),
                item.mergedIntoCartId() == null ? null : CartId.of(item.mergedIntoCartId()),
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
        if (lineItem.priceMinorUnits() == null
                || lineItem.priceCurrency() == null
                || lineItem.catalogVersion() == null) {
            return null;
        }

        Money price = Money.ofMinor(lineItem.priceMinorUnits(), Currency.getInstance(lineItem.priceCurrency()));
        return new PriceSnapshot(price, lineItem.catalogVersion());
    }
}
