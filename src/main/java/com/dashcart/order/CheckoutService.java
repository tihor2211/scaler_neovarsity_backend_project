package com.dashcart.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.dashcart.auth.AuthUser;
import com.dashcart.cart.Cart;
import com.dashcart.cart.CartDtos.CartLine;
import com.dashcart.cart.CartItemRepository;
import com.dashcart.cart.CartRepository;
import com.dashcart.common.ApiException;
import com.dashcart.events.OrderEventPublisher;
import com.dashcart.inventory.reservation.InsufficientStockException;
import com.dashcart.inventory.reservation.ReservationConflictException;
import com.dashcart.inventory.reservation.ReservationStrategies;
import com.dashcart.inventory.reservation.ReservationStrategyType;
import com.dashcart.inventory.reservation.StockRequest;
import com.dashcart.inventory.reservation.StockReservationStrategy;
import com.dashcart.order.OrderDtos.CheckoutRequest;
import com.dashcart.order.OrderDtos.OrderResponse;
import com.dashcart.store.StoreService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

// Turns the customer's cart into an order. In one transaction: check the cart and pincode, run the
// strategy's early stock check, create the order and its items, empty the cart, and reserve the stock last.
// If anything fails, the whole transaction rolls back and nothing is reserved.
@Service
public class CheckoutService {

    private final TransactionTemplate tx;
    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final StoreService storeService;
    private final ReservationStrategies strategies;
    private final CustomerOrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderService orderService;
    private final OrderEventPublisher events;
    private final MeterRegistry meters;
    private final Duration reservationTtl;
    private final int optimisticMaxAttempts;

    public CheckoutService(TransactionTemplate tx, CartRepository carts, CartItemRepository cartItems,
            StoreService storeService, ReservationStrategies strategies, CustomerOrderRepository orders,
            OrderItemRepository orderItems, OrderService orderService, OrderEventPublisher events,
            MeterRegistry meters,
            @Value("${dashcart.checkout.reservation-ttl:PT10M}") Duration reservationTtl,
            @Value("${dashcart.checkout.optimistic-max-attempts:5}") int optimisticMaxAttempts) {
        this.tx = tx;
        this.carts = carts;
        this.cartItems = cartItems;
        this.storeService = storeService;
        this.strategies = strategies;
        this.orders = orders;
        this.orderItems = orderItems;
        this.orderService = orderService;
        this.events = events;
        this.meters = meters;
        this.reservationTtl = reservationTtl;
        this.optimisticMaxAttempts = optimisticMaxAttempts;
    }

    public OrderResponse checkout(AuthUser user, CheckoutRequest request) {
        StockReservationStrategy strategy = strategies.active();
        String strategyName = strategy.type().name();
        int maxAttempts = strategy.type() == ReservationStrategyType.OPTIMISTIC ? optimisticMaxAttempts : 1;
        Timer.Sample timer = Timer.start(meters);
        String outcome = "rejected";
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    Long orderId = tx.execute(status -> placeOrder(user.id(), request, strategy));
                    outcome = "success";
                    return orderService.get(orderId, user);
                } catch (ReservationConflictException e) {
                    meters.counter("dashcart.checkout.conflicts", "strategy", strategyName).increment();
                    if (attempt >= maxAttempts) {
                        outcome = "conflict_retries_exhausted";
                        throw ApiException.conflict("Lots of people are buying this right now. Please try again.");
                    }
                    backOff(attempt);
                }
            }
        } catch (InsufficientStockException e) {
            outcome = "out_of_stock";
            throw e;
        } finally {
            timer.stop(meters.timer("dashcart.checkout", "strategy", strategyName, "outcome", outcome));
        }
    }

    private Long placeOrder(Long userId, CheckoutRequest request, StockReservationStrategy strategy) {
        Cart cart = carts.findByUserId(userId).orElseThrow(() -> ApiException.badRequest("Your cart is empty"));
        List<CartLine> lines = cartItems.findLines(cart.getId());
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Your cart is empty");
        }
        Long storeId = cart.getStoreId();
        if (!storeService.serviceability(request.deliveryPincode()).storeId().equals(storeId)) {
            throw ApiException.badRequest("Your cart is from a store that doesn't deliver to "
                    + request.deliveryPincode() + ". Clear the cart and shop again.");
        }
        storeService.getActive(storeId);
        for (CartLine line : lines) {
            if (!line.active()) {
                throw ApiException.conflict(line.name() + " is no longer sold");
            }
        }

        List<StockRequest> items = lines.stream()
                .map(line -> new StockRequest(line.productId(), line.quantity(), line.name()))
                .sorted(Comparator.comparing(StockRequest::productId))
                .toList();

        // "Lock late": cheap early rejection first, then the order rows, and the stock-row lock last so
        // it's held only until the commit that follows (see StockReservationStrategy).
        strategy.precheck(storeId, items);

        BigDecimal total = lines.stream().map(CartLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        CustomerOrder order = orders.save(new CustomerOrder(userId, storeId, total, request.deliveryAddress().trim(),
                request.deliveryPincode(), strategy.type(), Instant.now().plus(reservationTtl)));
        orderItems.saveAllAndFlush(lines.stream()
                .map(line -> new OrderItem(order.getId(), line.productId(), line.quantity(), line.price()))
                .toList());
        cartItems.deleteAllInCart(cart.getId());

        strategy.reserve(storeId, items);
        events.publish(order, OrderStatus.CREATED);
        return order.getId();
    }

    // Random, growing pause so retrying buyers don't all collide again at the same instant.
    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 15) * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("Checkout interrupted, please try again");
        }
    }
}
