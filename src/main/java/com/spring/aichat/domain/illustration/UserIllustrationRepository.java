package com.spring.aichat.domain.illustration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * [Phase 5.5-Illust] 유저 일러스트 레포지토리
 */
public interface UserIllustrationRepository extends JpaRepository<UserIllustration, Long> {

    /** Fal.ai requestId로 조회 (폴링/웹훅 콜백용) */
    Optional<UserIllustration> findByFalRequestId(String falRequestId);

    /** 특정 유저의 완료된 일러스트 목록 (갤러리용, 최신순) */
    List<UserIllustration> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    /** 특정 유저 + 특정 캐릭터의 완료된 일러스트 */
    List<UserIllustration> findByUserIdAndCharacterIdAndStatusOrderByCreatedAtDesc(
        Long userId, Long characterId, String status);

    /** 특정 유저의 PENDING/GENERATING 상태 일러스트 (폴링 대상) */
    List<UserIllustration> findByUserIdAndStatusIn(Long userId, List<String> statuses);

    /** 특정 유저의 전체 일러스트 수 */
    long countByUserIdAndStatus(Long userId, String status);
}