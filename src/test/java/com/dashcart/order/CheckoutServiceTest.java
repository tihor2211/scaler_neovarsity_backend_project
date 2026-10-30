package com.dashcart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.dashcart.auth.AuthUser;
import com.dashcart.auth.Role;
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
import com.dashcart.store.StoreDtos.ServiceabilityResponse;
import com.dashcart.store.StoreService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// The checkout flow without a database: every collaborator is a mock, and the transaction template simply
// runs the callback. The integration tests cover the real SQL; these cover the decisions CheckoutService
// makes around it (retries, statement order, metrics).
@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    private static final long STORE = 2L;
    private static final AuthUser BUYER = new AuthUser(7L, "buyer@example.com", Role.CUSTOMER);
    private static final CheckoutRequest REQUEST = new CheckoutRequest("1 Test Road", "560034");

    @Mock TransactionTemplate tx;
    @Mock CartRepository carts;
    @Mock CartItemRepository cartItems;
    @Mock StoreService storeService;
    @Mock ReservationStrategies strategies;
    @Mock StockReservationStrategy strategy;
    @Mock CustomerOrderRepository orders;
    @Mock OrderItemRepository orderItems;
    @Mock OrderService orderService;
    @Mock OrderEventPublisher events;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private CheckoutService checkout;

    @BeforeEach
    void setUp() {
        checkout = new CheckoutService(tx, carts, cartItems, storeService, strategies, orders, orderItems,
                orderService, events, meters, Duration.ofMinutes(10), 5);
        lenient().when(tx.execute(any())).thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        lenient().when(strategies.active()).thenReturn(strategy);
        lenient().when(carts.findByUserId(BUYER.id())).thenReturn(Optional.of(new Cart(BUYER.id(), STORE)));
        lenient().when(cartItems.findLines(any())).thenReturn(List.of(line(15L, "Toor Dal", 2)));
        lenient().when(storeService.serviceability("560034"))
                .thenReturn(new ServiceabilityResponse("560034", STORE, "DashCart Koramangala"));
        lenient().when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(orderService.get(any(), eq(BUYER))).thenReturn(response());
    }

    @Test
    void optimisticConflictsAreRetriedInANewTransactionUntilOneSucceeds() {
        using(ReservationStrategyType.OPTIMISTIC);
        doThrow(new ReservationConflictException(15L)).doThrow(new ReservationConflictException(15L)).doNothing()
                .when(strategy).reserve(eq(STORE), anyList());

        assertThat(checkout.checkout(BUYER, REQUEST)).isEqualTo(response());

        verify(tx, times(3)).execute(any());
        assertThat(conflicts()).isEqualTo(2);
        assertThat(checkouts("success")).isEqualTo(1);
    }

    @Test
    void optimisticGivesUpWith409AfterFiveAttempts() {
        using(ReservationStrategyType.OPTIMISTIC);
        doThrow(new ReservationConflictException(15L)).when(strategy).reserve(eq(STORE), anyList());

        assertThatThrownBy(() -> checkout.checkout(BUYER, REQUEST))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(strategy, times(5)).reserve(eq(STORE), anyList());
        assertThat(conflicts()).isEqualTo(5);
        assertThat(checkouts("conflict_retries_exhausted")).isEqualTo(1);
    }

    @Test
    void otherStrategiesNeverRetry() {
        using(ReservationStrategyType.PESSIMISTIC);
        doThrow(new ReservationConflictException(15L)).when(strategy).reserve(eq(STORE), anyList());

        assertThatThrownBy(() -> checkout.checkout(BUYER, REQUEST)).isInstanceOf(ApiException.class);

        verify(tx, times(1)).execute(any());
    }

    @Test
    void outOfStockIsA409WithoutRetryAndIsCountedSeparately() {
        using(ReservationStrategyType.OPTIMISTIC);
        doThrow(new InsufficientStockException(new StockRequest(15L, 2, "Toor Dal")))
                .when(strategy).reserve(eq(STORE), anyList());

        assertThatThrownBy(() -> checkout.checkout(BUYER, REQUEST))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("Not enough Toor Dal left for this order");

        verify(tx, times(1)).execute(any());
        assertThat(checkouts("out_of_stock")).isEqualTo(1);
    }

    @Test
    void pincodeOfAnotherStoreIsRejectedBeforeAnyStockOrOrderWork() {
        using(ReservationStrategyType.CONDITIONAL_UPDATE);
        when(storeService.serviceability("560034"))
                .thenReturn(new ServiceabilityResponse("560034", 3L, "DashCart Indiranagar"));

        assertThatThrownBy(() -> checkout.checkout(BUYER, REQUEST))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(strategy, never()).precheck(any(Long.class), anyList());
        verify(strategy, never()).reserve(any(Long.class), anyList());
        verify(orders, never()).save(any());
    }

    @Test
    void stockIsCheckedFirstAndLockedLastInProductIdOrder() {
        using(ReservationStrategyType.CONDITIONAL_UPDATE);
        when(cartItems.findLines(any())).thenReturn(List.of(line(30L, "Rice", 1), line(9L, "Bread", 2),
                line(15L, "Dal", 1)));

        checkout.checkout(BUYER, REQUEST);

        // Lock late: the non-locking precheck, then the order rows, and the locking reserve just before commit.
        InOrder steps = inOrder(strategy, orders, orderItems, cartItems, events);
        steps.verify(strategy).precheck(eq(STORE), anyList());
        steps.verify(orders).save(any());
        steps.verify(orderItems).saveAllAndFlush(anyList());
        steps.verify(cartItems).deleteAllInCart(any());
        steps.verify(strategy).reserve(eq(STORE), anyList());
        steps.verify(events).publish(any(), eq(OrderStatus.CREATED));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StockRequest>> reserved = ArgumentCaptor.forClass(List.class);
        verify(strategy).reserve(eq(STORE), reserved.capture());
        assertThat(reserved.getValue()).extracting(StockRequest::productId).containsExactly(9L, 15L, 30L);
    }

    private void using(ReservationStrategyType type) {
        when(strategy.type()).thenReturn(type);
    }

    private double conflicts() {
        var counter = meters.find("dashcart.checkout.conflicts").counter();
        return counter == null ? 0 : counter.count();
    }

    private long checkouts(String outcome) {
        var timer = meters.find("dashcart.checkout").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private static CartLine line(long productId, String name, int quantity) {
        return new CartLine(productId, name, "1 pc", new BigDecimal("100.00"), new BigDecimal("90.00"), quantity, 50,
                true);
    }

    private static OrderResponse response() {
        return new OrderResponse(1L, OrderStatus.CREATED, STORE, new BigDecimal("180.00"), "1 Test Road", "560034",
                ReservationStrategyType.CONDITIONAL_UPDATE, null, null, List.of());
    }
}
