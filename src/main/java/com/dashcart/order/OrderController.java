package com.dashcart.order;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dashcart.auth.AuthUser;
import com.dashcart.common.PageResponse;
import com.dashcart.order.OrderDtos.CheckoutRequest;
import com.dashcart.order.OrderDtos.ExpireResponse;
import com.dashcart.order.OrderDtos.OrderResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
public class OrderController {

    private final CheckoutService checkoutService;
    private final OrderService orderService;

    public OrderController(CheckoutService checkoutService, OrderService orderService) {
        this.checkoutService = checkoutService;
        this.orderService = orderService;
    }

    @PostMapping("/orders/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse checkout(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CheckoutRequest request) {
        return checkoutService.checkout(user, request);
    }

    @GetMapping("/orders")
    public PageResponse<OrderResponse> list(@AuthenticationPrincipal AuthUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return orderService.list(user.id(), page, size);
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long orderId) {
        return orderService.get(orderId, user);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal AuthUser user, @PathVariable Long orderId) {
        return orderService.cancel(orderId, user);
    }

    @PostMapping("/admin/orders/{orderId}/advance")
    public OrderResponse advance(@PathVariable Long orderId) {
        return orderService.advance(orderId);
    }

    // Runs the expiry job now instead of waiting for its schedule (demos and tests).
    @PostMapping("/admin/orders/expire-due")
    public ExpireResponse expireDue() {
        return new ExpireResponse(orderService.expireDue());
    }
}
