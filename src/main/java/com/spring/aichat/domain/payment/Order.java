package com.spring.aichat.domain.payment;

import com.spring.aichat.domain.enums.OrderStatus;
import com.spring.aichat.domain.enums.ProductType;
import com.spring.aichat.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter @NoArgsConstructor
@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_order_merchant_uid", columnList = "merchant_uid", unique = true),
    // [버그픽스 B-1.2 · docs/17_assets/defect_register.md §B-1.2 · docs/19 D-17]
    //   imp_uid 재사용(결제 1건 → 지급 N건)의 DB 레벨 최후 방어선.
    //   ⚠ prod는 ddl-auto=validate이므로 이 인덱스는 반드시 V28 마이그레이션과 세트여야 한다.
    //   PostgreSQL은 unique 인덱스에서 NULL을 서로 다른 값으로 취급하므로 PENDING 주문의
    //   impUid=NULL 다중 행은 문제없다.
    @Index(name = "uk_order_imp_uid", columnList = "imp_uid", unique = true),
    @Index(name = "idx_order_user_id", columnList = "user_id"),
    @Index(name = "idx_order_status", columnList = "status")
})
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_uid", nullable = false, unique = true, length = 50)
    private String merchantUid;

    // [버그픽스 B-1.2] 유니크 선언은 위 @Table의 uk_order_imp_uid *한 곳에만* 둔다.
    //   여기에 @Column(unique = true)를 겹쳐 주면 로컬(ddl-auto=update)에서 Hibernate가
    //   익명 UK_xxxx 제약을 하나 더 만들어 V28의 명명 인덱스와 스키마가 갈린다.
    @Column(name = "imp_uid", length = 50)
    private String impUid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProductType productType;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "target_character_id")
    private Long targetCharacterId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "failed_reason", length = 200)
    private String failedReason;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }

    public static Order create(String merchantUid, User user, ProductType productType, Long targetCharacterId) {
        Order o = new Order();
        o.merchantUid = merchantUid;
        o.user = user;
        o.productType = productType;
        o.amount = productType.getPriceKrw();
        o.status = OrderStatus.PENDING;
        o.targetCharacterId = targetCharacterId;
        return o;
    }

    /**
     * 결제 대금 확정 — PortOne 검증을 통과한 순간. [안건 4 (b)] 상태는 {@code PAID_UNDELIVERED}로 들어간다:
     * "돈은 확정, 지급은 아직". 지급 트랜잭션이 {@link #markDelivered()}로 PAID를 찍는다.
     * 그래서 PAID = 지급까지 완료가 단일 진실이고, 지급 실패는 롤백돼 사라지지 않고 이 상태로 남는다.
     */
    public void markPaid(String impUid) {
        if (this.status != OrderStatus.PENDING) throw new IllegalStateException("Only PENDING can be PAID. Current: " + this.status);
        this.impUid = impUid;
        this.status = OrderStatus.PAID_UNDELIVERED;
        this.paidAt = LocalDateTime.now();
    }

    public void markFailed(String reason) {
        // [적대적 리뷰 P1] PENDING에서만 — 종전엔 가드가 없어 환불된 주문에 재시도·취소 웹훅이 오면 REFUNDED→FAILED로 덮였다
        if (this.status != OrderStatus.PENDING) throw new IllegalStateException("Only PENDING can be FAILED. Current: " + this.status);
        this.status = OrderStatus.FAILED;
        this.failedReason = reason;
    }

    public void markExpired() {
        if (this.status == OrderStatus.PENDING) this.status = OrderStatus.EXPIRED;
    }

    public void markRefunded() {
        // [안건 4] PAID_UNDELIVERED도 환불 가능 — 돈은 확정됐고 지급만 안 된 주문은 회수 없이 취소만 하면 된다
        if (!this.status.isPaidMoney()) throw new IllegalStateException("Only PAID/PAID_UNDELIVERED can be REFUNDED. Current: " + this.status);
        this.status = OrderStatus.REFUNDED;
    }

    /**
     * [안건 4 (b)] 지급 실패 사유 기록 — 상태는 PAID_UNDELIVERED 그대로(지급 TX가 롤백된 뒤 별도 TX에서 호출).
     * 사유는 failed_reason에 200자 절삭(컬럼 상한). 관리자 미지급 큐·감시 스케줄러가 읽는다.
     */
    public void recordDeliveryFailure(String reason) {
        if (this.status != OrderStatus.PAID_UNDELIVERED) throw new IllegalStateException("Delivery failure only on PAID_UNDELIVERED. Current: " + this.status);
        this.failedReason = reason == null ? null : reason.substring(0, Math.min(200, reason.length()));
    }

    /** [안건 4 (b)] 지급 완료 — PAID_UNDELIVERED → PAID (최초 지급·재지급 공용). */
    public void markDelivered() {
        if (this.status != OrderStatus.PAID_UNDELIVERED) throw new IllegalStateException("Only PAID_UNDELIVERED can be delivered. Current: " + this.status);
        this.status = OrderStatus.PAID;
        this.failedReason = null;
    }
}