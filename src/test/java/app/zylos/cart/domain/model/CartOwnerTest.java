package app.zylos.cart.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.zylos.cart.domain.exception.CartDomainException;
import app.zylos.cart.domain.model.CartOwner.CustomerOwner;
import app.zylos.cart.domain.model.CartOwner.GuestOwner;

class CartOwnerTest {

    @Test
    void customerAndGuestExposeTheirSubjectUniformly() {
        assertThat(new CustomerOwner("cust-1").subjectId()).isEqualTo("cust-1");
        assertThat(new GuestOwner("guest-1").subjectId()).isEqualTo("guest-1");
    }

    @Test
    void blankSubjectsAreRejected() {
        assertThatThrownBy(() -> new CustomerOwner(" ")).isInstanceOf(CartDomainException.class);
        assertThatThrownBy(() -> new GuestOwner("")).isInstanceOf(CartDomainException.class);
    }
}
