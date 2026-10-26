package com.dashcart.events;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop50ByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    boolean existsByOrderIdAndStatus(Long orderId, String status);
}
