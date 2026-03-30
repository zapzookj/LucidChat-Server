package com.spring.aichat.domain.illustration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * [Phase 5.5-Illust] 배경 캐시 레포지토리
 */
public interface BackgroundCacheRepository extends JpaRepository<BackgroundCache, Long> {

    /** 해시로 캐시 히트 조회 */
    Optional<BackgroundCache> findByCacheHash(String cacheHash);

    /** Fal.ai requestId로 조회 (웹훅 콜백용) */
    Optional<BackgroundCache> findByFalRequestId(String falRequestId);

    /** 특정 캐릭터의 캐시된 배경 수 */
    long countByCharacterId(Long characterId);
}