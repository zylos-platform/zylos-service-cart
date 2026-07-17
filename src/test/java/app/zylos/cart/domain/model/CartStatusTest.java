package app.zylos.cart.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.zylos.cart.domain.exception.CartClosedException;

class CartStatusTest {

    @Test
    void activeCanTransitionToTerminalStates() {
        assertThat(CartStatus.ACTIVE.canTransitionTo(CartStatus.CONVERTED)).isTrue();
        assertThat(CartStatus.ACTIVE.canTransitionTo(CartStatus.MERGED)).isTrue();
        assertThat(CartStatus.ACTIVE.isTerminal()).isFalse();
    }

    @Test
    void terminalStatesAllowNoTransitions() {
        assertThat(CartStatus.CONVERTED.isTerminal()).isTrue();
        assertThat(CartStatus.MERGED.isTerminal()).isTrue();
        assertThat(CartStatus.CONVERTED.canTransitionTo(CartStatus.ACTIVE)).isFalse();
    }

    @Test
    void ensureCanTransitionRejectsIllegalTransition() {
        assertThatThrownBy(() -> CartStatus.CONVERTED.ensureCanTransitionTo(CartStatus.ACTIVE))
                .isInstanceOf(CartClosedException.class);
    }
}
