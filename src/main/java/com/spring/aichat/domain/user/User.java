package com.spring.aichat.domain.user;

import com.spring.aichat.domain.enums.AuthProvider;
import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.exception.InsufficientEnergyException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Getter @NoArgsConstructor
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_username", columnList = "username", unique = true),
    @Index(name = "idx_user_email", columnList = "email", unique = true),
    @Index(name = "idx_user_ci_hash", columnList = "ci_hash", unique = true)
})
/**
 * Phase 5 BM: 구독/부스트 모드 필드 추가
 *
 * [에너지 모델]
 * - freeEnergy: 자연 회복 (비구독: max 30 / 구독: max 100)
 * - paidEnergy: 유료 충전 (상한 없음)
 * - 차감 우선순위: free -> paid
 *
 * [구독]
 * - subscriptionTier: 현재 활성 구독 (null=미구독)
 * - 구독 혜택은 이 필드 기반으로 ChatMode, EnergyRegenScheduler에서 분기
 *
 * [부스트 모드]
 * - boostMode: Pro 모델 사용 토글
 * - 비구독자: 5배 에너지 소모 / 구독자: 일반 비용으로 Pro 모델
 */
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String username;

    @Column(length = 100)
    private String password;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(length = 120, unique = true)
    private String email;

    @Column(name = "profile_description", columnDefinition = "TEXT")
    private String profileDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider = AuthProvider.LOCAL;

    @Column(length = 120)
    private String providerId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 30)
    private Set<String> roles = new HashSet<>();

    // ── 에너지 ──

    @Column(name = "free_energy", nullable = false)
    private int freeEnergy = 30;

    @Column(name = "paid_energy", nullable = false)
    private int paidEnergy = 0;

    // ── 성인 인증 ──

    @Column(name = "is_adult", nullable = false)
    private Boolean isAdult = false;

    @Column(name = "ci_hash", length = 64, unique = true)
    private String ciHash;

    @Column(name = "adult_verified_at")
    private LocalDateTime adultVerifiedAt;

    // ── 구독 ──

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_tier", length = 30)
    private SubscriptionType subscriptionTier;

    // ── 부스트 모드 ──

    @Column(name = "boost_mode", nullable = false)
    private Boolean boostMode = false;

    // ── 기존 ──

    @Column(name = "is_secret_mode", nullable = false)
    private Boolean isSecretMode = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.roles.isEmpty()) this.roles.add("ROLE_USER");
    }

    // ── 팩토리 ──

    public static User local(String username, String passwordHash, String nickname, String email) {
        User u = new User();
        u.username = username; u.password = passwordHash;
        u.nickname = nickname; u.email = email;
        u.provider = AuthProvider.LOCAL;
        return u;
    }

    public static User google(String username, String nickname, String email, String providerId) {
        User u = new User();
        u.username = username; u.nickname = nickname;
        u.email = email; u.provider = AuthProvider.GOOGLE; u.providerId = providerId;
        return u;
    }

    // ── 에너지 로직 ──

    /** 총 에너지 (UI 표시용) */
    public int getEnergy() { return this.freeEnergy + this.paidEnergy; }

    /** freeEnergy 최대치 (구독자: 100, 비구독: 30) */
    public int getFreeEnergyMax() {
        return this.subscriptionTier != null ? 100 : 30;
    }

    /** 에너지 차감: free 우선 -> paid 폴백 */
    public void consumeEnergy(int amount) {
        int total = this.freeEnergy + this.paidEnergy;
        if (total < amount) {
            throw new InsufficientEnergyException(
                "에너지가 부족합니다. (보유: " + total + ", 필요: " + amount + ")");
        }
        if (this.freeEnergy >= amount) {
            this.freeEnergy -= amount;
        } else {
            int remaining = amount - this.freeEnergy;
            this.freeEnergy = 0;
            this.paidEnergy -= remaining;
        }
    }

    /** 자연 에너지 회복 (구독 티어에 따른 max 적용) */
    public void regenEnergy(int amount) {
        this.freeEnergy = Math.min(getFreeEnergyMax(), this.freeEnergy + amount);
    }

    /** 유료 에너지 충전 */
    public void chargePaidEnergy(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("충전량은 0보다 커야 합니다.");
        this.paidEnergy += amount;
    }

    // ── 성인 인증 ──

    public void completeAdultVerification(String ciHash) {
        this.isAdult = true;
        this.ciHash = ciHash;
        this.adultVerifiedAt = LocalDateTime.now();
    }

    // ── 구독 ──

    public void activateSubscription(SubscriptionType tier) {
        this.subscriptionTier = tier;
    }

    public void clearSubscription() {
        this.subscriptionTier = null;
        // 구독 해제 시 freeEnergy가 max 초과하면 클램핑
        if (this.freeEnergy > 30) {
            this.freeEnergy = 30;
        }
    }

    public boolean isSubscriber() {
        return this.subscriptionTier != null;
    }

    // ── 부스트 모드 ──

    public void updateBoostMode(boolean enabled) {
        this.boostMode = enabled;
    }

    // ── 기존 ──

    public void updateNickName(String nickname) { this.nickname = nickname; }
    public void updateProfileDescription(String s) { this.profileDescription = s; }
    public void updateIsSecretMode(boolean b) { this.isSecretMode = b; }
}