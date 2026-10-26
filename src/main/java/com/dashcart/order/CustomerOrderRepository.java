package com.dashcart.order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    Page<CustomerOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // Moves an order to "to" only if it is currently in one of "from", in a single UPDATE.
    // Returns 1 if it moved, 0 if someone else changed it first (e.g. payment arrived as it expired).
    // This is what keeps the expiry job, cancel and payment from both acting on the same order.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CustomerOrder o set o.status = :to, o.updatedAt = :now
            where o.id = :id and o.status in :from
            """)
    int transition(@Param("id") Long id, @Param("from") Collection<OrderStatus> from,
            @Param("to") OrderStatus to, @Param("now") Instant now);

    @Query("""
            select o.id from CustomerOrder o
            where o.status in :statuses and o.expiresAt < :now
            order by o.expiresAt
            """)
    List<Long> findDueIds(@Param("statuses") Collection<OrderStatus> statuses, @Param("now") Instant now,
            Pageable limit);
}
