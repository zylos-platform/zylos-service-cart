package app.zylos.cart.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import app.zylos.cart.application.exception.SkuNotFoundException;
import app.zylos.cart.application.exception.SkuNotPurchasableException;
import app.zylos.cart.application.port.in.CartCommandPort;
import app.zylos.cart.application.port.out.CurrentOwnerProvider;
import app.zylos.cart.application.port.out.IdempotencyStore;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.cart.domain.vo.CartId;
import app.zylos.cart.domain.vo.Sku;

@WebMvcTest(
        controllers = CartCommandController.class,
        excludeAutoConfiguration = {
            SecurityAutoConfiguration.class,
            OAuth2ClientAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class
        })
class CartCommandControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartCommandPort commands;

    @MockitoBean
    private IdempotencyStore idempotencyStore;

    @MockitoBean
    private CurrentOwnerProvider currentOwnerProvider;

    @BeforeEach
    void setUpIdempotencyFilterMocks() {
        CartOwner mockOwner = new CartOwner.CustomerOwner("user-123");
        when(currentOwnerProvider.currentOwner()).thenReturn(mockOwner);
        when(idempotencyStore.reserve(anyString(), anyString(), any()))
                .thenReturn(mock(IdempotencyStore.Reservation.Fresh.class));
    }

    @Test
    void addLineReturnsTheCartId() throws Exception {
        CartId cartId = CartId.newId();
        when(commands.addLine(any())).thenReturn(cartId);

        mockMvc.perform(post("/api/v1/cart/lines")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").value(cartId.toString()));
    }

    @Test
    void anUnknownSkuBecomesA404Problem() throws Exception {
        when(commands.addLine(any())).thenThrow(new SkuNotFoundException(Sku.of("SKU-X")));

        mockMvc.perform(post("/api/v1/cart/lines")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-X\",\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void anUnpurchasableSkuBecomesA422Problem() throws Exception {
        when(commands.addLine(any())).thenThrow(new SkuNotPurchasableException(Sku.of("SKU-X")));

        mockMvc.perform(post("/api/v1/cart/lines")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-X\",\"quantity\":1}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aZeroQuantityIsRejectedBeforeReachingTheApplication() throws Exception {
        mockMvc.perform(post("/api/v1/cart/lines")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());

        verify(commands, never()).addLine(any());
    }
}
