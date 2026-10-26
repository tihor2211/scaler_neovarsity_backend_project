package com.dashcart.payment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // Locked, so the browser's verify call and Razorpay's webhook arriving together are handled one
    // after the other instead of both capturing the same payment.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.razorpayOrderId = :razorpayOrderId")
    Optional<Payment> findForUpdate(@Param("razorpayOrderId") String razorpayOrderId);

    List<Payment> findByOrderIdOrderByIdDesc(Long orderId);
}
