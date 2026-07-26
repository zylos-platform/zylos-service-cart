package app.zylos.cart.adapter.out.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import app.zylos.cart.application.port.out.CurrentOwnerProvider;
import app.zylos.cart.domain.model.CartOwner;

/**
 * Derives the cart owner from the validated JWT subject.
 */
@Component
public class JwtCurrentOwnerProvider implements CurrentOwnerProvider {

    @Override
    public CartOwner currentOwner() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken token) {
            return new CartOwner.CustomerOwner(token.getToken().getSubject());
        }
        throw new IllegalStateException("No authenticated principal for the current request");
    }
}
