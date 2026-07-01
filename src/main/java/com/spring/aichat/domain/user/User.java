package com.spring.aichat.domain.user;

import com.spring.aichat.domain.enums.AuthProvider;
import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.domain.enums.UserStatus;
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
 *
 * [Phase 5.1] refundEnergy 추가 — LLM 호출 실패 시 에너지 롤백
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

    // ── 계정 상태 (Phase 6: 관리자 수동 정지/차단) ──

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "status_reason", length = 300)
    private String statusReason;

    @Column(name = "status_changed_at")
    private LocalDateTime statusChangedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * [Phase6/Tier4 / H-19] Optimistic Lock — Lost Update 차단.
     *   채팅 -2 + 일러스트 -10 동시 차감, 스케줄러 +1 + 채팅 -2 동시 등에서 한쪽이
     *   덮어쓰여 음수/이중차감되던 결함 차단. OptimisticLockingFailureException 발생 시
     *   호출처에서 retry해야 한다(예: AuthGuard 캐시-aside 패턴은 영향 없음, 채팅 흐름은
     *   ChatStreamService 등에서 재시도 정책 검토 — H-19 회귀 검증 권고 참조).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

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

    /**
     * [Phase 5.1] 에너지 환불 (LLM 호출 실패 시 롤백용)
     *
     * consumeEnergy의 역연산: freeEnergy 우선 복구 (max까지) → 초과분은 paidEnergy로.
     * 차감 직후 동일 요청 내에서만 호출되므로, 정확한 복원이 보장됨.
     */
    public void refundEnergy(int amount) {
        if (amount <= 0) return;
        int freeSpace = getFreeEnergyMax() - this.freeEnergy;
        int toFree = Math.min(amount, freeSpace);
        this.freeEnergy += toFree;
        this.paidEnergy += (amount - toFree);
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

    public static User kakao(String username, String nickname, String email, String providerId) {
        User u = new User();
        u.username = username;
        u.nickname = nickname;
        u.email = email;
        u.provider = AuthProvider.KAKAO;
        u.providerId = providerId;
        return u;
    }

    public static User naver(String username, String nickname, String email, String providerId) {
        User u = new User();
        u.username = username;
        u.nickname = nickname;
        u.email = email;
        u.provider = AuthProvider.NAVER;
        u.providerId = providerId;
        return u;
    }

    // ── 기존 ──

    public void updateNickName(String nickname) { this.nickname = nickname; }
    public void updateProfileDescription(String s) { this.profileDescription = s; }
    public void updateIsSecretMode(boolean b) { this.isSecretMode = b; }

    // ── 계정 상태 (Phase 6) ──

    public boolean isActive() { return this.status == UserStatus.ACTIVE; }

    /** 접근이 차단되어야 하는 상태(정지/차단)인지. */
    public boolean isAccessBlocked() { return this.status != null && this.status.blocksAccess(); }

    public void suspend(String reason) {
        this.status = UserStatus.SUSPENDED;
        this.statusReason = reason;
        this.statusChangedAt = LocalDateTime.now();
    }

    public void ban(String reason) {
        this.status = UserStatus.BANNED;
        this.statusReason = reason;
        this.statusChangedAt = LocalDateTime.now();
    }

    public void reactivate() {
        this.status = UserStatus.ACTIVE;
        this.statusReason = null;
        this.statusChangedAt = LocalDateTime.now();
    }

    // ── 유료 에너지 차감 (Phase 6: 관리자 조정 / 환불 회수) ──

    /** 유료 에너지 차감(0 미만으로 내려가지 않도록 클램핑). 관리자 조정·환불 회수에서 사용. */
    public void deductPaidEnergy(int amount) {
        if (amount <= 0) return;
        this.paidEnergy = Math.max(0, this.paidEnergy - amount);
    }

    // ── 성인 인증 해제 (Phase 6: 관리자 CI 재바인딩) ──

    /** 성인 인증 강제 해제 — CI 해시 반납(재인증/타 계정 재바인딩이 가능해짐). */
    public void releaseAdultVerification() {
        this.isAdult = false;
        this.ciHash = null;
        this.adultVerifiedAt = null;
    }
}