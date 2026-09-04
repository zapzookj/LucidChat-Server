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
    /**
     * ⚠ [E-4.15] <b>호출부 0</b>. 사문이던 ModelsLab 배경 웹훅 핸들러를 제거하면서 유일 사용처가 사라졌다.
     * 지우지 않고 남기는 이유는 pending 행 도입(그 파일 주석의 (a)안)을 다시 검토할 때 재사용 지점이기 때문이다 —
     * 다만 그때는 {@code BackgroundCache}에 imageUrl 변경 API를 먼저 만들어야 한다(현재 없다).
     * 그 계획이 폐기되면 이 메서드도 함께 지워라.
     */
    Optional<BackgroundCache> findByFalRequestId(String falRequestId);

    /** 특정 캐릭터의 캐시된 배경 수 */
    long countByCharacterId(Long characterId);
}