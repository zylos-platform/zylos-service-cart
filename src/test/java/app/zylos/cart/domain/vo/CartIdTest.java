package app.zylos.cart.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.zylos.cart.domain.exception.CartDomainException;

class CartIdTest {

    @Test
    void newIdIsTimeOrderedUuidV7() {
        assertThat(CartId.newId().value().version()).isEqualTo(7);
    }

    @Test
    void ofStringParsesAndRoundTrips() {
        UUID uuid = UUID.randomUUID();
        assertThat(CartId.of(uuid.toString()).value()).isEqualTo(uuid);
        assertThat(CartId.of(uuid)).hasToString(uuid.toString());
    }

    @Test
    void ofStringRejectsMalformedInput() {
        assertThatThrownBy(() -> CartId.of("not-a-uuid")).isInstanceOf(CartDomainException.class);
    }
}
