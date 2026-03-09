package com.spring.aichat.domain.payment;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [Phase 5 Fix] 시간 제한 시크릿 패스 엔티티 (24시간 패스 등)
 *
 * [설계 근거]
 * 기존 24h 패스는 Redis TTL에만 의존하여, Redis 재시작/장애 시
 * 유료 구매 권한이 증발하는 치명적 결함이 있었다.
 *
 * RDB를 Source of Truth로, Redis는 Read-Through 캐시로만 사용한다.
 *
 * [영속화 전략]
 * 1. 결제 완료 시: RDB INSERT + Redis SET(TTL)
 * 2. 접근 체크 시: Redis GET → hit이면 true / miss이면 RDB 조회 → 활성이면 Redis 재캐싱
 * 3. 만료 후: 스케줄러가 RDB 정리 (선택적, 즉시 하지 않아도 무방)
 *
 * [UserSecretUnlock과의 차이]
 * - UserSecretUnlock: 영구 해금 (expiresAt 없음)
 * - UserSecretPass: 시간 제한 패스 (expiresAt 필수)
 * - 동일 유저-캐릭터 조합으로 복수 구매 가능 (히스토리 추적)
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "user_secret_passes", indexes = {
    @Index(name = "idx_pass_user_char_active", columnList = "user_id, character_id, expires_at"),
    @Index(name = "idx_pass_expires", columnList = "expires_at")
})
public class UserSecretPass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    @Column(name = "activated_at", nullable = false)
    private LocalDateTime activatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 결제 추적용 주문번호 */
    @Column(name = "merchant_uid", length = 50)
    private String merchantUid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 24시간 패스 생성
     */
    public static UserSecretPass create24h(User user, Character character, String merchantUid) {
        UserSecretPass pass = new UserSecretPass();
        pass.user = user;
        pass.character = character;
        pass.activatedAt = LocalDateTime.now();
        pass.expiresAt = LocalDateTime.now().plusHours(24);
        pass.merchantUid = merchantUid;
        return pass;
    }

    /**
     * 만료 여부
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }

    /**
     * 남은 TTL (초 단위, Redis 재캐싱용)
     */
    public long remainingTtlSeconds() {
        long remaining = java.time.Duration.between(LocalDateTime.now(), this.expiresAt).getSeconds();
        return Math.max(0, remaining);
    }
}