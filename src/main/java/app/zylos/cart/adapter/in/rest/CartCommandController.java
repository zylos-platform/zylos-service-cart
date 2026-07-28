package app.zylos.cart.adapter.in.rest;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import app.zylos.cart.application.command.AddLineToCartCommand;
import app.zylos.cart.application.command.ChangeLineQuantityCommand;
import app.zylos.cart.application.port.in.CartCommandPort;
import app.zylos.cart.domain.vo.CartId;
import app.zylos.cart.domain.vo.Quantity;
import app.zylos.cart.domain.vo.Sku;

/**
 * REST driving adapter for cart write commands. The cart is resolved from the authenticated subject
 * rather than a path identifier, so there is no cart id in these routes — a client can only ever act on
 * its own cart. Every mutation requires an {@code Idempotency-Key} (enforced by {@link IdempotencyFilter});
 * failures surface as RFC 9457 problem responses.
 */
@RestController
@RequestMapping("/api/v1/cart")
public class CartCommandController {

    private final CartCommandPort commands;

    public CartCommandController(CartCommandPort commands) {
        this.commands = commands;
    }

    @PostMapping("/lines")
    public ResponseEntity<CartCommandResponse> addLine(@Valid @RequestBody AddLineRequest request) {
        CartId cartId =
                commands.addLine(new AddLineToCartCommand(Sku.of(request.sku()), Quantity.of(request.quantity())));
        return ResponseEntity.ok(new CartCommandResponse(cartId.toString()));
    }

    @PatchMapping("/lines/{sku}")
    public ResponseEntity<CartCommandResponse> changeQuantity(
            @PathVariable String sku, @Valid @RequestBody ChangeQuantityRequest request) {
        CartId cartId = commands.changeLineQuantity(
                new ChangeLineQuantityCommand(Sku.of(sku), Quantity.of(request.quantity())));
        return ResponseEntity.ok(new CartCommandResponse(cartId.toString()));
    }

    @DeleteMapping("/lines/{sku}")
    public ResponseEntity<CartCommandResponse> removeLine(@PathVariable String sku) {
        return ResponseEntity.ok(
                new CartCommandResponse(commands.removeLine(Sku.of(sku)).toString()));
    }

    @DeleteMapping
    public ResponseEntity<CartCommandResponse> clear() {
        return ResponseEntity.ok(new CartCommandResponse(commands.clearCart().toString()));
    }
}
