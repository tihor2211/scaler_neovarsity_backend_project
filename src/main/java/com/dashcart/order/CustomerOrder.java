package com.dashcart.order;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.dashcart.inventory.reservation.ReservationStrategyType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Named CustomerOrder because ORDER is a reserved word in SQL and JPQL.
// While the order is CREATED or PAYMENT_PENDING it is also the stock reservation: its items' quantities
// are held as reserved_qty until it's paid, cancelled or expires at expiresAt.
@Entity
@Table(name = "orders")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "delivery_address", nullable = false, length = 500)
    private String deliveryAddress;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "delivery_pincode", nullable = false, length = 6)
    private String deliveryPincode;

    // Which strategy reserved the stock, so release later knows whether Redis must be updated too.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reservation_strategy", nullable = false, length = 20)
    private ReservationStrategyType reservationStrategy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerOrder() {
    }

    public CustomerOrder(Long userId, Long storeId, BigDecimal totalAmount, String deliveryAddress,
            String deliveryPincode, ReservationStrategyType reservationStrategy, Instant expiresAt) {
        this.userId = userId;
        this.storeId = storeId;
        this.status = OrderStatus.CREATED;
        this.totalAmount = totalAmount;
        this.deliveryAddress = deliveryAddress;
        this.deliveryPincode = deliveryPincode;
        this.reservationStrategy = reservationStrategy;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getStoreId() {
        return storeId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public String getDeliveryPincode() {
        return deliveryPincode;
    }

    public ReservationStrategyType getReservationStrategy() {
        return reservationStrategy;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
