package com.spring.aichat.domain.payment;

import com.spring.aichat.domain.enums.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByMerchantUid(String merchantUid);

    List<Order> findByUser_IdOrderByCreatedAtDesc(Long userId);

    /** PENDING 상태에서 일정 시간 경과한 주문을 EXPIRED로 전환 */
    @Modifying
    @Query("UPDATE Order o SET o.status = 'EXPIRED' WHERE o.status = 'PENDING' AND o.createdAt < :cutoff")
    int expireOldPendingOrders(@Param("cutoff") LocalDateTime cutoff);

    /** 특정 유저의 특정 상태 주문 수 조회 */
    long countByUser_IdAndStatus(Long userId, OrderStatus status);
}