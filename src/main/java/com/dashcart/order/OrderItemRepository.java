package com.dashcart.order;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dashcart.order.OrderDtos.OrderItemView;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    // Items of several orders with product names, in one query (used for order lists).
    @Query("""
            select new com.dashcart.order.OrderDtos$OrderItemView(
                oi.orderId, p.id, p.name, p.unit, oi.quantity, oi.unitPrice)
            from OrderItem oi
            join Product p on p.id = oi.productId
            where oi.orderId in :orderIds
            order by oi.orderId, oi.id
            """)
    List<OrderItemView> findViews(@Param("orderIds") Collection<Long> orderIds);
}
