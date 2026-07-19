package app.zylos.cart.application.port.out;

import org.jspecify.annotations.Nullable;

/**
 * Supplies the correlation id of the in-flight request for the outbox envelope.
 */
@FunctionalInterface
public interface RequestCorrelationProvider {

    @Nullable
    String correlationId();
}
