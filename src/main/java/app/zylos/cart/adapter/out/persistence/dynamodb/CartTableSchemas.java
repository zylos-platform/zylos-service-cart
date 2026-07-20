package app.zylos.cart.adapter.out.persistence.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttributeTags;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticImmutableTableSchema;

/**
 * Enhanced-client schemas for the heterogeneous single-table design (two item types, one table).
 */
public final class CartTableSchemas {

    static final String GSI1_OWNER = "GSI1-owner";
    public static final String GSI3_OUTBOX_PENDING = "GSI3-outbox-pending";

    static final StaticImmutableTableSchema<CartLineItem, CartLineItem.Builder> LINE =
            StaticImmutableTableSchema.builder(CartLineItem.class, CartLineItem.Builder.class)
                    .newItemBuilder(CartLineItem::builder, CartLineItem.Builder::build)
                    .addAttribute(
                            String.class,
                            a -> a.name("sku").getter(CartLineItem::sku).setter(CartLineItem.Builder::sku))
                    .addAttribute(
                            Integer.class,
                            a -> a.name("quantity")
                                    .getter(CartLineItem::quantity)
                                    .setter(CartLineItem.Builder::quantity))
                    .addAttribute(
                            Long.class,
                            a -> a.name("priceMinorUnits")
                                    .getter(CartLineItem::priceMinorUnits)
                                    .setter(CartLineItem.Builder::priceMinorUnits))
                    .addAttribute(
                            String.class,
                            a -> a.name("priceCurrency")
                                    .getter(CartLineItem::priceCurrency)
                                    .setter(CartLineItem.Builder::priceCurrency))
                    .addAttribute(
                            Long.class,
                            a -> a.name("catalogVersion")
                                    .getter(CartLineItem::catalogVersion)
                                    .setter(CartLineItem.Builder::catalogVersion))
                    .addAttribute(
                            String.class,
                            a -> a.name("sellerId")
                                    .getter(CartLineItem::sellerId)
                                    .setter(CartLineItem.Builder::sellerId))
                    .addAttribute(
                            String.class,
                            a -> a.name("origin").getter(CartLineItem::origin).setter(CartLineItem.Builder::origin))
                    .build();

    static final StaticImmutableTableSchema<CartItem, CartItem.Builder> CART = StaticImmutableTableSchema.builder(
                    CartItem.class, CartItem.Builder.class)
            .newItemBuilder(CartItem::builder, CartItem.Builder::build)
            .addAttribute(
                    String.class,
                    a -> a.name("PK")
                            .getter(CartItem::pk)
                            .setter(CartItem.Builder::pk)
                            .tags(StaticAttributeTags.primaryPartitionKey()))
            .addAttribute(
                    String.class,
                    a -> a.name("SK")
                            .getter(CartItem::sk)
                            .setter(CartItem.Builder::sk)
                            .tags(StaticAttributeTags.primarySortKey()))
            .addAttribute(
                    String.class,
                    a -> a.name("entityType").getter(CartItem::entityType).setter(CartItem.Builder::entityType))
            .addAttribute(
                    String.class, a -> a.name("cartId").getter(CartItem::cartId).setter(CartItem.Builder::cartId))
            .addAttribute(
                    String.class,
                    a -> a.name("ownerType").getter(CartItem::ownerType).setter(CartItem.Builder::ownerType))
            .addAttribute(
                    String.class,
                    a -> a.name("ownerId").getter(CartItem::ownerId).setter(CartItem.Builder::ownerId))
            .addAttribute(
                    String.class, a -> a.name("status").getter(CartItem::status).setter(CartItem.Builder::status))
            .addAttribute(
                    String.class,
                    a -> a.name("currency").getter(CartItem::currency).setter(CartItem.Builder::currency))
            .addAttribute(
                    EnhancedType.listOf(EnhancedType.documentOf(CartLineItem.class, LINE)),
                    a -> a.name("lines").getter(CartItem::lines).setter(CartItem.Builder::lines))
            .addAttribute(
                    Long.class, a -> a.name("version").getter(CartItem::version).setter(CartItem.Builder::version))
            .addAttribute(
                    java.time.Instant.class,
                    a -> a.name("createdAt").getter(CartItem::createdAt).setter(CartItem.Builder::createdAt))
            .addAttribute(
                    java.time.Instant.class,
                    a -> a.name("updatedAt").getter(CartItem::updatedAt).setter(CartItem.Builder::updatedAt))
            .addAttribute(
                    String.class,
                    a -> a.name("convertedOrderId")
                            .getter(CartItem::convertedOrderId)
                            .setter(CartItem.Builder::convertedOrderId))
            .addAttribute(
                    String.class,
                    a -> a.name("mergedIntoCartId")
                            .getter(CartItem::mergedIntoCartId)
                            .setter(CartItem.Builder::mergedIntoCartId))
            .addAttribute(
                    String.class,
                    a -> a.name("GSI1PK")
                            .getter(CartItem::gsi1pk)
                            .setter(CartItem.Builder::gsi1pk)
                            .tags(StaticAttributeTags.secondaryPartitionKey(GSI1_OWNER)))
            .addAttribute(
                    String.class,
                    a -> a.name("GSI1SK")
                            .getter(CartItem::gsi1sk)
                            .setter(CartItem.Builder::gsi1sk)
                            .tags(StaticAttributeTags.secondarySortKey(GSI1_OWNER)))
            .addAttribute(
                    Long.class,
                    a -> a.name("expiresAt").getter(CartItem::expiresAt).setter(CartItem.Builder::expiresAt))
            .build();

    public static final StaticImmutableTableSchema<OutboxRecordItem, OutboxRecordItem.Builder> OUTBOX =
            StaticImmutableTableSchema.builder(OutboxRecordItem.class, OutboxRecordItem.Builder.class)
                    .newItemBuilder(OutboxRecordItem::builder, OutboxRecordItem.Builder::build)
                    .addAttribute(
                            String.class,
                            a -> a.name("PK")
                                    .getter(OutboxRecordItem::pk)
                                    .setter(OutboxRecordItem.Builder::pk)
                                    .tags(StaticAttributeTags.primaryPartitionKey()))
                    .addAttribute(
                            String.class,
                            a -> a.name("SK")
                                    .getter(OutboxRecordItem::sk)
                                    .setter(OutboxRecordItem.Builder::sk)
                                    .tags(StaticAttributeTags.primarySortKey()))
                    .addAttribute(
                            String.class,
                            a -> a.name("entityType")
                                    .getter(OutboxRecordItem::entityType)
                                    .setter(OutboxRecordItem.Builder::entityType))
                    .addAttribute(
                            Integer.class,
                            a -> a.name("shardId")
                                    .getter(OutboxRecordItem::shardId)
                                    .setter(OutboxRecordItem.Builder::shardId))
                    .addAttribute(
                            String.class,
                            a -> a.name("outboxId")
                                    .getter(OutboxRecordItem::outboxId)
                                    .setter(OutboxRecordItem.Builder::outboxId))
                    .addAttribute(
                            String.class,
                            a -> a.name("eventId")
                                    .getter(OutboxRecordItem::eventId)
                                    .setter(OutboxRecordItem.Builder::eventId))
                    .addAttribute(
                            String.class,
                            a -> a.name("eventType")
                                    .getter(OutboxRecordItem::eventType)
                                    .setter(OutboxRecordItem.Builder::eventType))
                    .addAttribute(
                            String.class,
                            a -> a.name("aggregateId")
                                    .getter(OutboxRecordItem::aggregateId)
                                    .setter(OutboxRecordItem.Builder::aggregateId))
                    .addAttribute(
                            String.class,
                            a -> a.name("aggregateType")
                                    .getter(OutboxRecordItem::aggregateType)
                                    .setter(OutboxRecordItem.Builder::aggregateType))
                    .addAttribute(
                            String.class,
                            a -> a.name("cartId")
                                    .getter(OutboxRecordItem::cartId)
                                    .setter(OutboxRecordItem.Builder::cartId))
                    .addAttribute(
                            java.time.Instant.class,
                            a -> a.name("occurredAt")
                                    .getter(OutboxRecordItem::occurredAt)
                                    .setter(OutboxRecordItem.Builder::occurredAt))
                    .addAttribute(
                            Boolean.class,
                            a -> a.name("terminal")
                                    .getter(OutboxRecordItem::terminal)
                                    .setter(OutboxRecordItem.Builder::terminal))
                    .addAttribute(
                            software.amazon.awssdk.core.SdkBytes.class,
                            a -> a.name("payload")
                                    .getter(OutboxRecordItem::payload)
                                    .setter(OutboxRecordItem.Builder::payload))
                .addAttribute(String.class, a -> a.name("GSI3PK")
                    .getter(OutboxRecordItem::gsi3pk)
                    .setter(OutboxRecordItem.Builder::gsi3pk)
                    .tags(StaticAttributeTags.secondaryPartitionKey(GSI3_OUTBOX_PENDING)))
                .addAttribute(String.class, a -> a.name("GSI3SK")
                    .getter(OutboxRecordItem::gsi3sk)
                    .setter(OutboxRecordItem.Builder::gsi3sk)
                    .tags(StaticAttributeTags.secondarySortKey(GSI3_OUTBOX_PENDING)))
                .addAttribute(String.class, a -> a.name("status")
                    .getter(OutboxRecordItem::status)
                    .setter(OutboxRecordItem.Builder::status))
                .addAttribute(Long.class, a -> a.name("expiresAt")
                    .getter(OutboxRecordItem::expiresAt)
                    .setter(OutboxRecordItem.Builder::expiresAt))
                    .build();

    private CartTableSchemas() {}
}
