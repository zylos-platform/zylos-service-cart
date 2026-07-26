package app.zylos.cart.application.port.in;

import app.zylos.cart.application.command.AddLineToCartCommand;
import app.zylos.cart.application.command.ChangeLineQuantityCommand;
import app.zylos.cart.domain.vo.CartId;
import app.zylos.cart.domain.vo.Sku;

/**
 * Inbound port for cart write use cases. Implemented by the application service, driven by adapters.
 */
public interface CartCommandPort {

    CartId addLine(AddLineToCartCommand command);

    CartId changeLineQuantity(ChangeLineQuantityCommand command);

    CartId removeLine(Sku sku);

    CartId clearCart();
}
