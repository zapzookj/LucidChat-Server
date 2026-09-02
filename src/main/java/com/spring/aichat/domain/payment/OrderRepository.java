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

    // ─────────────────────────────────────────────
    // [적대적 리뷰 P1] 미지급 감시 — UndeliveredPaymentScheduler 전용
    //   웹훅 재시도가 다 소진된 뒤에도 '아무도 모르는 미지급'이 남지 않게 주기 스캔한다.
    //   저장소에 EntityManager 직접 사용 선례가 0건이라 JPQL은 여기로 모은다.
    // ─────────────────────────────────────────────

    /**
     * 결제 식별자(imp_uid)는 붙었는데 지급도 환불도 되지 않은 주문 — 주문 상태 불변식 위반.
     *
     * <p>REFUNDED 제외가 필수다: 환불 주문은 impUid를 보존한 채 PAID→REFUNDED로 전이하므로,
     * 빼지 않으면 정상 환불 건이 전부 오탐으로 올라온다.
     */
    @Query("SELECT o FROM Order o WHERE o.impUid IS NOT NULL "
        + "AND o.status NOT IN ('PAID', 'REFUNDED') AND o.paidAt < :settledBefore ORDER BY o.createdAt DESC")
    List<Order> findImpUidWithoutDelivery(@Param("settledBefore") LocalDateTime settledBefore,
                                          org.springframework.data.domain.Pageable pageable);

    /**
     * [안건 4 (b)] 지급 실패 주문 — 관리자 재지급 큐·감시 스케줄러용. paidAt 기준 정렬(오래된 미지급이 먼저).
     * {@code settledBefore}로 '지급 TX가 아직 진행 중인' 직전 결제(ms~초)를 거른다.
     */
    @Query("SELECT o FROM Order o WHERE o.status = 'PAID_UNDELIVERED' AND o.paidAt < :settledBefore ORDER BY o.paidAt ASC")
    List<Order> findUndelivered(@Param("settledBefore") LocalDateTime settledBefore,
                                org.springframework.data.domain.Pageable pageable);

    /**
     * 검증 실패로 FAILED 확정됐지만 PortOne 쪽에는 실제 결제가 존재하는 주문.
     *
     * <p>사유 접두어는 {@code PaymentService.verifyAndDeliver}가 {@code markFailed}에 넣는
     * 문자열과 맞춰야 한다. {@code "PortOne status: "}는 <b>의도적으로 제외</b> — 그쪽은 애초에
     * 돈이 나가지 않았고 결제창 이탈이 흔해 오탐이 스캔 전체를 무의미하게 만든다.
     */
    @Query("SELECT o FROM Order o WHERE o.status = 'FAILED' AND o.createdAt >= :since "
        + "AND (o.failedReason LIKE 'Amount mismatch%' "
        + "  OR o.failedReason LIKE 'merchant_uid mismatch%') ORDER BY o.createdAt DESC")
    List<Order> findFailedWithRealPayment(@Param("since") LocalDateTime since,
                                          org.springframework.data.domain.Pageable pageable);

    // [Phase 6] 매출 집계 (paidAt 기준). status 문자열 리터럴은 기존 JPQL 관례를 따름.
    //   [안건 4 · 적대적 리뷰 P3] 매출 = '확정된 돈' 기준 — PAID_UNDELIVERED도 paidAt이 찍힌 확정 결제이므로 포함.
    //   빼면 그 주문의 환불은 환불액에 잡혀 순매출(paid - refunded)이 그만큼 음수로 기운다.
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM Order o WHERE o.status IN ('PAID', 'PAID_UNDELIVERED') AND o.paidAt >= :from AND o.paidAt < :to")
    long sumPaidBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM Order o WHERE o.status = 'REFUNDED' AND o.paidAt >= :from AND o.paidAt < :to")
    long sumRefundedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT o.productType, COUNT(o), COALESCE(SUM(o.amount), 0) FROM Order o " +
        "WHERE o.status IN ('PAID', 'PAID_UNDELIVERED') AND o.paidAt >= :from AND o.paidAt < :to GROUP BY o.productType")
    List<Object[]> revenueByProductBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}