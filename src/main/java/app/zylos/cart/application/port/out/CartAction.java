package app.zylos.cart.application.port.out;

/** Policy action identifiers for cart commands, mirroring the catalog {@code ProductAction} convention. */
public final class CartAction {

    public static final String LINE_ADD = "cart:add-line";
    public static final String LINE_CHANGE_QUANTITY = "cart:change-line-quantity";
    public static final String LINE_REMOVE = "cart:remove-line";
    public static final String CART_CLEAR = "cart:clear";
    public static final String CART_READ = "cart:read";

    private CartAction() {}
}
