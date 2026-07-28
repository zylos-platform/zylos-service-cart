package app.zylos.cart.application.exception;

import java.io.Serial;

import app.zylos.cart.domain.model.CartOwner;

/**
 * Policy denied the action for this principal. Maps to HTTP 403.
 */
public class ActionDeniedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ActionDeniedException(CartOwner subject, String action) {
        super("Action %s denied for %s".formatted(action, subject.subjectId()));
    }
}
