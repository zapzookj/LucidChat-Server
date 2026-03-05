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
 * - expiresAt: 구독 만료 시각 (결제 시 +30일)
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

    /** 결제 추적용 주문번호 */
    @Column(name = "merchant_uid", length = 50)
    private String merchantUid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public static UserSubscription create(User user, SubscriptionType type, String merchantUid) {
        UserSubscription sub = new UserSubscription();
        sub.user = user;
        sub.type = type;
        sub.startedAt = LocalDateTime.now();
        sub.expiresAt = LocalDateTime.now().plusDays(30);
        sub.active = true;
        sub.merchantUid = merchantUid;
        return sub;
    }

    /** 구독 만료 처리 */
    public void deactivate() {
        this.active = false;
    }

    /** 구독 갱신 (+30일) */
    public void renew(String newMerchantUid) {
        this.expiresAt = LocalDateTime.now().plusDays(30);
        this.merchantUid = newMerchantUid;
        this.active = true;
    }

    /** 만료 여부 체크 */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }
}