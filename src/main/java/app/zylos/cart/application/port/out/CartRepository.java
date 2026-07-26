package app.zylos.cart.application.port.out;

import java.util.Optional;

import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.cart.domain.vo.CartId;

/**
 * Outbound (driven) port for cart persistence. The DynamoDB adapter implements this by writing the
 * cart item and the events the aggregate recorded to the transactional outbox atomically within a
 * single {@code TransactWriteItems}.
 */
public interface CartRepository {

    /**
     * Persists the cart and its recorded events atomically. Throws {@link OptimisticConcurrencyException}
     * if the cart was modified concurrently since it was loaded.
     */
    void save(Cart cart);

    Optional<Cart> findById(CartId cartId);

    Optional<Cart> findActiveByOwner(CartOwner owner);
}
