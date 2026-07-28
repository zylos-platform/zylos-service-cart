package app.zylos.cart.adapter.in.rest;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.github.f4b6a3.uuid.UuidCreator;

import app.zylos.cart.application.port.out.RequestCorrelationProvider;

/**
 * Supplies the correlation id established by {@link CorrelationIdFilter} (read from the SLF4J MDC).
 * Falls back to a generated id for non-HTTP entry points where the filter did not run.
 */
@Component
public class MdcRequestCorrelationProvider implements RequestCorrelationProvider {

    @Override
    public String correlationId() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId != null
                ? correlationId
                : UuidCreator.getTimeOrderedEpoch().toString();
    }
}
