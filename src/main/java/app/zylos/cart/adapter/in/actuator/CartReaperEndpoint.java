package app.zylos.cart.adapter.in.actuator;

import java.time.Instant;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import app.zylos.cart.adapter.out.reaper.CartReaper;

@Component
@Endpoint(id = "reaper")
public class CartReaperEndpoint {

    private final CartReaper cartReaper;

    public CartReaperEndpoint(CartReaper cartReaper) {
        this.cartReaper = cartReaper;
    }

    @WriteOperation
    public Map<String, Integer> triggerSweep() {
        int reaped = cartReaper.sweep(Instant.now());
        return Map.of("reaped", reaped);
    }
}
