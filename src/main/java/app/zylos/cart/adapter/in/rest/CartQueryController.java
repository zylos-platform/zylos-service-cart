package app.zylos.cart.adapter.in.rest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.zylos.cart.application.port.in.CartQueryPort;
import app.zylos.cart.application.query.CartView;

/**
 * REST driving adapter for cart reads. The cart is resolved from the authenticated subject, so there is
 * no identifier in the route. Responses are explicitly non-cacheable: a cart is per-user, mutable, and
 * carries live pricing, so any shared cache would be both wrong and a data leak.
 */
@RestController
@RequestMapping("/api/v1/cart")
public class CartQueryController {

    private final CartQueryPort queries;

    public CartQueryController(CartQueryPort queries) {
        this.queries = queries;
    }

    @GetMapping
    public ResponseEntity<CartView> getCart() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(queries.getCart());
    }
}
