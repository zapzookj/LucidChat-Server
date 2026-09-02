package com.spring.aichat.domain.payment;

import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 유저 구독 엔티티
 *
 * [설계]
 * - 유저당 활성 구독은 최대 1개 (Tier 업그레이드 시 기존 구독 비활성화)
 *   — [D-4.4 · V33] DB 레벨 보장은 부분 유니크 인덱스 {@code uq_sub_user_active (user_id) WHERE active}.
 *     JPA @Index로는 부분 인덱스를 표현할 수 없어 여기엔 선언하지 않는다(validate는 인덱스를 검증하지 않는다).
 * - expiresAt: 구독 만료 시각 (결제 시 +30일 — 갱신은 잔여 기간 보존, 업그레이드는 금액 비례 이월: 안건 5)
 * - active: 활성 상태 (만료/해지 시 false)
 * - 스케줄러가 expiresAt 지난 구독을 자동 비활성화
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "user_subscriptions", indexes = {
    @Index(name = "idx_sub_user_active", columnList = "user_id, active"),
    @Index(name = "idx_sub_expires", columnList = "expires_at")
})
public class UserSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SubscriptionType type;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean active = true;

    /** 결제 추적용 주문번호 — 최신 유료 회차. 관리자 지급(merchantUid=null)은 덮지 않는다. */
    @Column(name = "merchant_uid", length = 50)
    private String merchantUid;

    /**
     * [적대적 리뷰 P1 · V35] 최신 회차 적용 <b>직전</b>의 (만료 시각, 주문번호) 스냅샷. D-4.1로 잔여 기간이 보존되면서
     * 한 행이 여러 회차의 유상 기간을 누적하게 됐는데, 최신 회차 환불이 행 전체를 끄면 더블 결제 유저가 이전 회차
     * 30일까지 잃는다. 환불 회수는 이 스냅샷으로 '그 회차분만' 되돌린다({@link #revertLatestRound()}).
     * 신규 행·되돌린 뒤·이력 없는 구 행은 null.
     */
    @Column(name = "prev_expires_at")
    private LocalDateTime prevExpiresAt;

    @Column(name = "prev_merchant_uid", length = 50)
    private String prevMerchantUid;

    /**
     * [적대적 리뷰 P2 · V35] 티어 변경으로 만들어진 행의 이월 출처 — 이전 활성 행 id와 이월 초. 상위 티어 주문을 환불하면
     * 이전 행을 복원하고, 이월 원천(하위 회차) 주문의 환불은 사전에 거부한다(이월분이 회수 없이 유출되는 차익 경로).
     */
    @Column(name = "carried_from_id")
    private Long carriedFromId;

    @Column(name = "carried_seconds")
    private Long carriedSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    /** 회차당 구독 기간. */
    public static final int PERIOD_DAYS = 30;

    /**
     * 신규 구독 행. 만료 시각은 호출측이 확정해 넘긴다 — 신규는 {@code now + 30일}, 업그레이드는
     * 하위 티어 잔여 기간의 금액 비례 이월분을 더한 값(안건 5 (b), {@code SubscriptionService}가 산출).
     * 3-arg(now+30 고정) 팩토리는 두지 않는다 — 남기면 업그레이드 호출부가 조용히 이월 없는 경로로 컴파일된다.
     */
    public static UserSubscription create(User user, SubscriptionType type, String merchantUid,
                                          LocalDateTime expiresAt) {
        UserSubscription sub = new UserSubscription();
        sub.user = user;
        sub.type = type;
        sub.startedAt = LocalDateTime.now();
        sub.expiresAt = expiresAt;
        sub.active = true;
        sub.merchantUid = merchantUid;
        return sub;
    }

    /** 구독 만료 처리 */
    public void deactivate() {
        this.active = false;
    }

    /**
     * 같은 티어 재결제 — 잔여 기간을 보존한 채 +30일.
     *
     * <p>[D-4.1 · docs/17_assets/defect_register.md §D-4.1] 종전 {@code now + 30일}은 잔여 10일이 있는 유저가
     * 재결제하면 40일이 아니라 30일이 되어 유상 기간이 소멸했고 로그는 "Renewed"라 탐지도 안 됐다.
     * 기준을 {@code max(now, expiresAt)}으로 — 만료 후 재가입은 now 기준이라 정상.
     *
     * <p>[D-4.2 · 안건 6 (c)] merchantUid는 최신 회차로 덮는다(이력 테이블 신설은 런칭 전 범위 밖).
     * 그래서 <b>과거 회차 주문번호로는 이 행을 찾을 수 없다</b> — RefundService가 최근 회차만 환불을 허용하고
     * 과거 회차는 사전에 거부한다. 이 계약을 바꾸려면 이력 테이블부터 만들 것.
     *
     * @return 갱신 전 만료 시각 (호출측 로그용 — 잔여 보존이 실제로 일어났는지 운영에서 보이게)
     */
    public LocalDateTime renew(String newMerchantUid) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime before = this.expiresAt;
        LocalDateTime base = (this.expiresAt != null && this.expiresAt.isAfter(now)) ? this.expiresAt : now;
        this.expiresAt = base.plusDays(PERIOD_DAYS);
        if (newMerchantUid != null) {
            // 유료 회차: 직전 (만료, 주문번호)를 스냅샷 — 이 회차 환불 시 그만큼만 되돌린다
            this.prevExpiresAt = before;
            this.prevMerchantUid = this.merchantUid;
            this.merchantUid = newMerchantUid;
        }
        // [적대적 리뷰 P2] 관리자 지급(null)은 유료 회차 키·스냅샷을 건드리지 않는다 — 덮으면 그 유료 회차 환불이
        //   '과거 회차'로 거부된다. 지급 사실은 감사로그(SUBSCRIPTION_GRANT)가 남긴다.
        this.active = true;
        return before;
    }

    /**
     * [적대적 리뷰 P1] 최신 회차 회수 — 스냅샷이 있으면 (만료, 주문번호)를 직전 값으로 되돌린다(더블 결제 환불 시
     * 이전 회차 보존). 되돌린 만료가 이미 지났으면 호출측이 비활성화한다.
     *
     * @return 스냅샷이 있어 되돌렸으면 true, 없으면(단일 회차·구 행) false — 호출측이 행 전체를 비활성화
     */
    public boolean revertLatestRound() {
        if (this.prevExpiresAt == null) return false;
        this.expiresAt = this.prevExpiresAt;
        this.merchantUid = this.prevMerchantUid;
        this.prevExpiresAt = null;
        this.prevMerchantUid = null;
        return true;
    }

    /** 티어 변경 행의 이월 출처 기록 (0초 이월도 출처는 남긴다 — 상위 주문 환불 시 이전 행 복원의 키). */
    public void markCarriedFrom(Long fromSubscriptionId, long seconds) {
        this.carriedFromId = fromSubscriptionId;
        this.carriedSeconds = Math.max(0, seconds);
    }

    /** 이월 출처 행이 되살아날 때(상위 주문 환불) — 활성화. 만료 판단은 호출측. */
    public void reactivate() {
        this.active = true;
    }

    /** 이 행이 주어진 주문번호의 회차(=최신 회차)인가 — 환불 회수의 선검증 술어(안건 6 (c)). */
    public boolean isCurrentRound(String merchantUid) {
        return merchantUid != null && merchantUid.equals(this.merchantUid);
    }

    /** 만료 여부 체크 */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }
}