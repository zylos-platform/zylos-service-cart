package app.zylos.cart.adapter.out.persistence.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zylos.cart.application.port.out.IdempotencyStore.Reservation;

import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Verifies the reservation state machine against dynamodb-local. Extends the shared cart-table IT base.
 */
class DynamoIdempotencyStoreIT extends AbstractCartTableIT {

    private static final Duration IN_FLIGHT = Duration.ofSeconds(60);
    private static final Duration COMPLETED = Duration.ofHours(24);

    private DynamoIdempotencyStore store;

    @BeforeEach
    void setUpStore() {
        store = new DynamoIdempotencyStore(client, enhancedClient, TABLE);
    }

    @Test
    void firstReservationOfAKeyIsFresh() {
        assertThat(store.reserve("key-1", "fp-1", IN_FLIGHT)).isInstanceOf(Reservation.Fresh.class);
    }

    @Test
    void aSecondClaimWhileInFlightIsRejectedAsInFlight() {
        store.reserve("key-1", "fp-1", IN_FLIGHT);

        assertThat(store.reserve("key-1", "fp-1", IN_FLIGHT)).isInstanceOf(Reservation.InFlight.class);
    }

    @Test
    void aCompletedKeyReplaysTheStoredResponse() {
        store.reserve("key-1", "fp-1", IN_FLIGHT);
        store.complete(
                "key-1",
                "fp-1",
                200,
                "application/json",
                "{\"cartId\":\"c-1\"}".getBytes(StandardCharsets.UTF_8),
                COMPLETED);

        assertThat(store.reserve("key-1", "fp-1", IN_FLIGHT))
                .isInstanceOfSatisfying(Reservation.Replay.class, replay -> {
                    assertThat(replay.status()).isEqualTo(200);
                    assertThat(replay.contentType()).isEqualTo("application/json");
                    assertThat(new String(replay.body(), StandardCharsets.UTF_8))
                            .contains("c-1");
                });
    }

    @Test
    void reusingAKeyWithADifferentRequestIsAConflict() {
        store.reserve("key-1", "fp-1", IN_FLIGHT);
        store.complete("key-1", "fp-1", 200, "application/json", new byte[0], COMPLETED);

        assertThat(store.reserve("key-1", "fp-DIFFERENT", IN_FLIGHT)).isInstanceOf(Reservation.Conflict.class);
    }

    @Test
    void aReleasedReservationCanBeClaimedAgain() {
        store.reserve("key-1", "fp-1", IN_FLIGHT);
        store.release("key-1");

        assertThat(store.reserve("key-1", "fp-1", IN_FLIGHT)).isInstanceOf(Reservation.Fresh.class);
    }

    @Test
    void anExpiredInFlightReservationIsReclaimable() {
        store.reserve("key-1", "fp-1", Duration.ofSeconds(-1)); // already logically expired

        // Liveness is decided by expiresAt at write time, not by DynamoDB TTL (which is lazy).
        assertThat(store.reserve("key-1", "fp-1", IN_FLIGHT)).isInstanceOf(Reservation.Fresh.class);
    }

    @Test
    void releasingACompletedReservationDoesNotDeleteIt() {
        store.reserve("key-1", "fp-1", IN_FLIGHT);
        store.complete("key-1", "fp-1", 200, "application/json", new byte[0], COMPLETED);

        assertThatThrownBy(() -> store.release("key-1")).isInstanceOf(ConditionalCheckFailedException.class);

        assertThat(store.reserve("key-1", "fp-1", IN_FLIGHT)).isInstanceOf(Reservation.Replay.class);
    }
}
