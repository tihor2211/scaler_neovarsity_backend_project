package com.dashcart.cart;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dashcart.auth.AuthUser;
import com.dashcart.cart.CartDtos.CartResponse;
import com.dashcart.cart.CartDtos.SetItemRequest;

import jakarta.validation.Valid;

@RestController
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping("/cart")
    public CartResponse get(@AuthenticationPrincipal AuthUser user) {
        return cartService.get(user.id());
    }

    @PutMapping("/cart/items/{productId}")
    public CartResponse setItem(@AuthenticationPrincipal AuthUser user, @PathVariable Long productId,
            @Valid @RequestBody SetItemRequest request) {
        return cartService.setItem(user.id(), productId, request.storeId(), request.quantity());
    }

    @DeleteMapping("/cart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@AuthenticationPrincipal AuthUser user) {
        cartService.clear(user.id());
    }
}
