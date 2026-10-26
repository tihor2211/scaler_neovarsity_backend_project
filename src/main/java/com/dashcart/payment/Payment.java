package com.dashcart.payment;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// One payment attempt for an order, backed by one Razorpay order.
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "razorpay_order_id", nullable = false, unique = true, length = 64)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 64)
    private String razorpayPaymentId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(Long orderId, String razorpayOrderId, long amountPaise, String currency) {
        this.orderId = orderId;
        this.razorpayOrderId = razorpayOrderId;
        this.amountPaise = amountPaise;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
    }

    public void captured(String razorpayPaymentId, boolean orderAccepted) {
        this.razorpayPaymentId = razorpayPaymentId;
        this.status = orderAccepted ? PaymentStatus.CAPTURED : PaymentStatus.REFUND_REQUIRED;
    }

    public void failed(String razorpayPaymentId) {
        this.razorpayPaymentId = razorpayPaymentId;
        this.status = PaymentStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getRazorpayOrderId() {
        return razorpayOrderId;
    }

    public String getRazorpayPaymentId() {
        return razorpayPaymentId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }
}
