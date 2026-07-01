package com.spring.aichat.domain.payment;

import com.spring.aichat.domain.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 일반 조회 (읽기 전용, 락 없음) */
    Optional<Order> findByMerchantUid(String merchantUid);

    /**
     * 비관적 락(SELECT ... FOR UPDATE) 조회
     *
     * [동시성 방어 핵심]
     * confirmPayment / webhook 에서 이 메서드를 사용.
     * 동일 merchantUid에 대해 두 스레드가 동시에 진입하면:
     *   Thread A: SELECT ... FOR UPDATE -> row lock 획득 -> PENDING 확인 -> PAID 처리
     *   Thread B: SELECT ... FOR UPDATE -> row lock 대기 (blocking)
     *   Thread A: COMMIT -> lock 해제
     *   Thread B: lock 획득 -> 이미 PAID 상태 -> 멱등성에 의해 조용히 리턴
     *
     * 이로써 에너지 중복 지급이 원천 차단됨.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.merchantUid = :merchantUid")
    Optional<Order> findByMerchantUidForUpdate(@Param("merchantUid") String merchantUid);

    List<Order> findByUser_IdOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("UPDATE Order o SET o.status = 'EXPIRED' WHERE o.status = 'PENDING' AND o.createdAt < :cutoff")
    int expireOldPendingOrders(@Param("cutoff") LocalDateTime cutoff);

    long countByUser_IdAndStatus(Long userId, OrderStatus status);

    // [Phase 6] 매출 집계 (paidAt 기준). status 문자열 리터럴은 기존 JPQL 관례를 따름.
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM Order o WHERE o.status = 'PAID' AND o.paidAt >= :from AND o.paidAt < :to")
    long sumPaidBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM Order o WHERE o.status = 'REFUNDED' AND o.paidAt >= :from AND o.paidAt < :to")
    long sumRefundedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT o.productType, COUNT(o), COALESCE(SUM(o.amount), 0) FROM Order o " +
        "WHERE o.status = 'PAID' AND o.paidAt >= :from AND o.paidAt < :to GROUP BY o.productType")
    List<Object[]> revenueByProductBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}