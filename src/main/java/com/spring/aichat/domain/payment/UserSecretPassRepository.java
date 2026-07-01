package com.spring.aichat.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * [Phase 5 Fix] 시간 제한 시크릿 패스 Repository
 */
public interface UserSecretPassRepository extends JpaRepository<UserSecretPass, Long> {

    /** [Phase 6] 환불 회수용 — 주문번호로 패스 조회 */
    Optional<UserSecretPass> findByMerchantUid(String merchantUid);

    /**
     * 유저-캐릭터 조합에 대해 현재 활성(미만료) 패스 조회
     * expires_at이 가장 먼 것을 반환 (복수 구매 시 가장 늦게 만료되는 것)
     */
    @Query("SELECT p FROM UserSecretPass p " +
        "WHERE p.user.id = :userId AND p.character.id = :characterId " +
        "AND p.expiresAt > :now " +
        "ORDER BY p.expiresAt DESC " +
        "LIMIT 1")
    Optional<UserSecretPass> findActivePass(
        @Param("userId") Long userId,
        @Param("characterId") Long characterId,
        @Param("now") LocalDateTime now
    );

    /**
     * 유저-캐릭터에 대한 활성 패스 존재 여부
     */
    @Query("SELECT COUNT(p) > 0 FROM UserSecretPass p " +
        "WHERE p.user.id = :userId AND p.character.id = :characterId " +
        "AND p.expiresAt > :now")
    boolean existsActivePass(
        @Param("userId") Long userId,
        @Param("characterId") Long characterId,
        @Param("now") LocalDateTime now
    );

    /**
     * 만료된 패스 정리 (스케줄러용, 선택적)
     */
    @Modifying
    @Query("DELETE FROM UserSecretPass p WHERE p.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * [V2 Story · Q-10 통합] 유저가 *어떤 캐릭터에라도* 활성 24h 패스를 보유하는가.
     * V2 Story의 user-global 시크릿 게이트에서 사용.
     */
    @Query("SELECT COUNT(p) > 0 FROM UserSecretPass p " +
        "WHERE p.user.id = :userId AND p.expiresAt > :now")
    boolean existsAnyActivePassByUserId(
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );
}