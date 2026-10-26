package com.dashcart.order;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.dashcart.auth.AuthUser;
import com.dashcart.auth.Role;
import com.dashcart.common.ApiException;
import com.dashcart.common.PageResponse;
import com.dashcart.events.OrderEventPublisher;
import com.dashcart.inventory.reservation.StockLedger;
import com.dashcart.inventory.reservation.StockRequest;
import com.dashcart.order.OrderDtos.OrderItemResponse;
import com.dashcart.order.OrderDtos.OrderResponse;

// Everything that happens to an order after checkout: viewing, cancelling, expiring, payment, fulfilment.
// Every status change goes through CustomerOrderRepository.transition, so two actors (say the expiry job
// and a late payment) can't both win.
@Service
public class OrderService {

    private static final int EXPIRY_BATCH = 100;

    private final CustomerOrderRepository orders;
    private final OrderItemRepository orderItems;
    private final StockLedger ledger;
    private final OrderEventPublisher events;
    private final TransactionTemplate tx;

    public OrderService(CustomerOrderRepository orders, OrderItemRepository orderItems, StockLedger ledger,
            OrderEventPublisher events, TransactionTemplate tx) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.ledger = ledger;
        this.events = events;
        this.tx = tx;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long orderId, AuthUser user) {
        return toResponse(load(orderId, user));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(Long userId, int page, int size) {
        Page<CustomerOrder> result = orders.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        List<Long> ids = result.getContent().stream().map(CustomerOrder::getId).toList();
        Map<Long, List<OrderItemResponse>> itemsByOrder = ids.isEmpty() ? Map.of()
                : orderItems.findViews(ids).stream().collect(Collectors.groupingBy(
                        OrderDtos.OrderItemView::orderId,
                        Collectors.mapping(OrderItemResponse::from, Collectors.toList())));
        return PageResponse.from(result.map(o -> OrderResponse.from(o, itemsByOrder.getOrDefault(o.getId(), List.of()))));
    }

    // Customers can cancel until the order is paid; the reserved stock goes back on sale.
    @Transactional
    public OrderResponse cancel(Long orderId, AuthUser user) {
        CustomerOrder order = load(orderId, user);
        if (orders.transition(orderId, OrderStatus.HOLDING_STOCK, OrderStatus.CANCELLED, Instant.now()) == 0) {
            throw ApiException.conflict("Order " + orderId + " can't be cancelled now (it is " + order.getStatus() + ")");
        }
        release(order);
        events.publish(order, OrderStatus.CANCELLED);
        return toResponse(orders.findById(orderId).orElseThrow());
    }

    // Expires unpaid orders past their expiresAt and releases their stock. Each order in its own
    // transaction, so one failure doesn't block the rest. Returns how many were expired.
    public int expireDue() {
        List<Long> due = orders.findDueIds(OrderStatus.HOLDING_STOCK, Instant.now(), PageRequest.of(0, EXPIRY_BATCH));
        int expired = 0;
        for (Long orderId : due) {
            Boolean done = tx.execute(status -> {
                CustomerOrder order = orders.findById(orderId).orElseThrow();
                if (orders.transition(orderId, OrderStatus.HOLDING_STOCK, OrderStatus.EXPIRED, Instant.now()) == 0) {
                    return false;
                }
                release(order);
                events.publish(order, OrderStatus.EXPIRED);
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                expired++;
            }
        }
        return expired;
    }

    @Transactional
    public void markPaymentPending(Long orderId) {
        orders.transition(orderId, EnumSet.of(OrderStatus.CREATED), OrderStatus.PAYMENT_PENDING, Instant.now());
    }

    // Payment captured: reserved units become sold. Returns false if this payment didn't make the order
    // PAID: it had expired or been cancelled (stock already released), or another payment already paid it.
    // Either way the money has to be refunded.
    @Transactional
    public boolean markPaid(Long orderId) {
        CustomerOrder order = orders.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
        if (orders.transition(orderId, OrderStatus.HOLDING_STOCK, OrderStatus.PAID, Instant.now()) == 0) {
            return false;
        }
        ledger.confirm(order.getStoreId(), stockOf(orderId));
        events.publish(order, OrderStatus.PAID);
        return true;
    }

    // Admin: PAID -> PACKED -> OUT_FOR_DELIVERY -> DELIVERED.
    @Transactional
    public OrderResponse advance(Long orderId) {
        CustomerOrder order = orders.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
        OrderStatus next = order.getStatus().nextFulfilmentStep();
        if (next == null
                || orders.transition(orderId, EnumSet.of(order.getStatus()), next, Instant.now()) == 0) {
            throw ApiException.conflict("Order " + orderId + " can't move on from " + order.getStatus());
        }
        events.publish(order, next);
        return toResponse(orders.findById(orderId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public CustomerOrder getOwned(Long orderId, AuthUser user) {
        return load(orderId, user);
    }

    private void release(CustomerOrder order) {
        ledger.release(order.getStoreId(), stockOf(order.getId()), order.getReservationStrategy());
    }

    private List<StockRequest> stockOf(Long orderId) {
        return orderItems.findByOrderId(orderId).stream()
                .map(item -> new StockRequest(item.getProductId(), item.getQuantity(), null))
                .toList();
    }

    // Customers only see their own orders; someone else's order id looks the same as a missing one.
    private CustomerOrder load(Long orderId, AuthUser user) {
        return orders.findById(orderId)
                .filter(o -> user.role() == Role.ADMIN || o.getUserId().equals(user.id()))
                .orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
    }

    private OrderResponse toResponse(CustomerOrder order) {
        List<OrderItemResponse> items = orderItems.findViews(List.of(order.getId())).stream()
                .map(OrderItemResponse::from).toList();
        return OrderResponse.from(order, items);
    }
}
