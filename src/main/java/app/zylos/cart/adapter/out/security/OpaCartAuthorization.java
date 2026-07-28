package app.zylos.cart.adapter.out.security;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import app.zylos.cart.application.exception.ActionDeniedException;
import app.zylos.cart.application.port.out.CartAuthorizationPort;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.security.opa.OpaClient;

@Component
public class OpaCartAuthorization implements CartAuthorizationPort {

    static final String POLICY_PATH = "zylos/authz/cart/decision";

    private final ObjectProvider<OpaClient> opaClientProvider;

    public OpaCartAuthorization(ObjectProvider<OpaClient> opaClientProvider) {
        this.opaClientProvider = opaClientProvider;
    }

    @SuppressWarnings("unchecked")
    private static List<String> currentRealmRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken token) {
            Map<String, Object> realmAccess = token.getToken().getClaimAsMap("realm_access");

            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
                return List.copyOf((List<String>) roles);
            }
        }
        return List.of();
    }

    @Override
    public void requireAllowed(CartOwner subject, String action) {
        OpaClient opa = opaClientProvider.getIfAvailable();
        if (opa == null) {
            return; // OPA disabled for this profile; check() itself fails closed when present
        }

        String subjectType = subject instanceof CartOwner.CustomerOwner ? "CUSTOMER" : "GUEST";
        CartAuthzInput input = new CartAuthzInput(
                new CartAuthzInput.Subject(subject.subjectId(), subjectType, currentRealmRoles()), action);

        if (!opa.check(POLICY_PATH, input)) {
            throw new ActionDeniedException(subject, action);
        }
    }

    record CartAuthzInput(Subject subject, String action) {
        record Subject(String id, String type, List<String> roles) {}
    }
}
