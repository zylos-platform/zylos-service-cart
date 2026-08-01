package app.zylos.cart.adapter.out.persistence.dynamodb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import org.springframework.stereotype.Component;

import com.github.f4b6a3.uuid.UuidCreator;
import com.github.f4b6a3.uuid.util.UuidUtil;

import app.zylos.cart.application.port.out.RequestCorrelationProvider;
import app.zylos.cart.config.ZylosCartOutboxProperties;
import app.zylos.cart.config.ZylosCartServiceProperties;
import app.zylos.cart.domain.event.CartConverted;
import app.zylos.cart.domain.event.CartExpired;
import app.zylos.cart.domain.event.DomainEvent;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartLine;
import app.zylos.cart.domain.model.CartOwner.CustomerOwner;
import app.zylos.cart.domain.vo.CartId;
import app.zylos.cart.domain.vo.PriceSnapshot;
import app.zylos.contracts.cart.v1.CartEvent;
import app.zylos.contracts.cart.v1.CartOwner;
import app.zylos.contracts.cart.v1.CartState;
import app.zylos.contracts.common.v1.Money;
import app.zylos.contracts.common.v1.Producer;

import software.amazon.awssdk.core.SdkBytes;

/**
 * Builds outbox records from an aggregate's recorded domain events. Each thin event is enriched into a
 * {@link CartEvent} envelope carrying a full ECST {@link CartState} snapshot (built once per command)
 * and serialized with Avro single-object encoding — deliberately WITHOUT the Schema Registry, so the
 * write path has no dependency on it. The relay decodes these bytes and
 * performs Confluent/registry serialization per-topic at publish time.
 */
@Component
public class CartOutboxRecordFactory {

    private static final String AGGREGATE_TYPE = "cart";
    private static final int EVENT_SCHEMA_VERSION = 1;

    private static final String PREFIX_OUTBOX = "OUTBOX#";
    private static final String PREFIX_PENDING = "PENDING#";
    private static final String STATUS_PENDING = "PENDING";

    private final ZylosCartServiceProperties serviceProperties;
    private final ZylosCartOutboxProperties outboxProperties;
    private final RequestCorrelationProvider correlation;

    public CartOutboxRecordFactory(
            ZylosCartServiceProperties serviceProperties,
            ZylosCartOutboxProperties outboxProperties,
            RequestCorrelationProvider correlation) {
        this.serviceProperties = serviceProperties;
        this.outboxProperties = outboxProperties;
        this.correlation = correlation;
    }

    /**
     * Avro single-object encoding: self-describing (schema fingerprint), registry-free.
     */
    private static SdkBytes encode(CartEvent envelope) {
        try {
            return SdkBytes.fromByteBuffer(envelope.toByteBuffer());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode CartEvent for the outbox", e);
        }
    }

    private static Money money(PriceSnapshot snapshot) {
        return Money.newBuilder()
                .setMinorUnits(snapshot.unitPrice().minorUnits())
                .setCurrency(snapshot.currency().getCurrencyCode())
                .build();
    }

    public List<OutboxRecordItem> from(Cart cart, List<DomainEvent> events, Instant occurredAt) {
        if (events.isEmpty()) {
            return List.of();
        }

        CartState state = cartState(cart, occurredAt);
        Producer producer = Producer.newBuilder()
                .setService(serviceProperties.name())
                .setVersion(serviceProperties.version())
                .build();

        String aggregateId = cart.id().value().toString();
        String correlationId = correlation.correlationId();
        String causationId = UuidCreator.getTimeOrderedEpoch().toString();
        int shard = shardFor(aggregateId);

        List<OutboxRecordItem> records = new ArrayList<>(events.size());
        for (DomainEvent event : events) {
            String eventId = UuidCreator.getTimeOrderedEpoch().toString();
            String eventType = event.getClass().getSimpleName();

            CartEvent envelope = CartEvent.newBuilder()
                    .setEventId(eventId)
                    .setEventType(eventType)
                    .setEventVersion(EVENT_SCHEMA_VERSION)
                    .setAggregateId(aggregateId)
                    .setAggregateType(AGGREGATE_TYPE)
                    .setOccurredAt(occurredAt)
                    .setCorrelationId(correlationId)
                    .setCausationId(causationId)
                    .setProducer(producer)
                    .setPayload(state)
                    .build();

            records.add(OutboxRecordItem.builder()
                    .pk(PREFIX_OUTBOX + shard)
                    .sk(eventId)
                    .shardId(shard)
                    .outboxId(eventId)
                    .eventId(eventId)
                    .eventType(eventType)
                    .aggregateId(aggregateId)
                    .aggregateType(AGGREGATE_TYPE)
                    .cartId(aggregateId)
                    .occurredAt(occurredAt)
                    .terminal(isTerminal(event))
                    .payload(encode(envelope))
                    .gsi2pk(PREFIX_PENDING + shard)
                    .gsi2sk(eventId)
                    .status(STATUS_PENDING)
                    .build());
        }
        return records;
    }

    private int shardFor(String cartId) {
        return Math.floorMod(cartId.hashCode(), outboxProperties.writeShards());
    }

    private boolean isTerminal(DomainEvent event) {
        return event instanceof CartConverted || event instanceof CartExpired;
    }

    private CartState cartState(Cart cart, Instant occurredAt) {
        List<app.zylos.contracts.cart.v1.CartLine> lines = new ArrayList<>();
        for (CartLine line : cart.lines()) {
            PriceSnapshot snapshot = line.priceSnapshot();
            lines.add(app.zylos.contracts.cart.v1.CartLine.newBuilder()
                    .setSku(line.sku().value())
                    .setQuantity(line.quantity().value())
                    .setUnitPrice(snapshot == null ? null : money(snapshot))
                    .setPriceCapturedAt(null)
                    .setCatalogVersion(snapshot == null ? null : snapshot.catalogVersion())
                    .setSellerId(line.sellerId())
                    .setLineOrigin(line.origin().name())
                    .setAddedAt(null)
                    .build());
        }

        String ownerType = cart.owner() instanceof CustomerOwner ? "CUSTOMER" : "GUEST";
        Currency currency = cart.currency();
        CartId mergedIntoCartId = cart.mergedIntoCartId();

        return CartState.newBuilder()
                .setCartId(cart.id().value().toString())
                .setOwner(CartOwner.newBuilder()
                        .setOwnerType(ownerType)
                        .setOwnerId(cart.owner().subjectId())
                        .build())
                .setStatus(cart.status().name())
                .setCurrency(currency == null ? null : currency.getCurrencyCode())
                .setLines(lines)
                .setVersion(cart.version())
                .setCreatedAt(UuidUtil.getInstant(cart.id().value()))
                .setUpdatedAt(occurredAt)
                .setConvertedOrderId(cart.convertedOrderId())
                .setMergedIntoCartId(
                        mergedIntoCartId == null
                                ? null
                                : mergedIntoCartId.value().toString())
                .build();
    }
}
