package com.spring.aichat.domain.character;

import com.spring.aichat.domain.enums.WorldId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CharacterRepository extends JpaRepository<Character, Long> {

    Optional<Character> findByName(String name);

    /** [Phase 5] slug 기반 조회 */
    Optional<Character> findBySlug(String slug);

    /** [Phase 5.5-Theater] 세계관별 캐릭터 조회 — [Phase 6] 관리자 hidden 제외 */
    List<Character> findByWorldIdAndTheaterAvailableTrueAndHiddenFalse(WorldId worldId);

    /** [Phase 5.5-Theater] Theater 가용 캐릭터 전체 */
    List<Character> findByTheaterAvailableTrue();

    /**
     * [V2 Story] 세계관별 활성 캐릭터 ID 오름차순 조회 — [Phase 6] 관리자 hidden 제외.
     * StoryCreateFlow의 히로인 풀 노출에 사용.
     */
    List<Character> findByWorldIdAndStoryAvailableTrueAndHiddenFalseOrderByIdAsc(WorldId worldId);

    // ━━━ [UGC v1] ━━━

    /** 내 UGC 캐릭터 (스튜디오 '내 캐릭터' 섹션). */
    List<Character> findByOwnerUserIdOrderByIdDesc(Long ownerUserId);

    /** 탐색 피드 첫 페이지 — 공개 UGC 최신순. */
    List<Character> findBySourceAndVisibilityAndHiddenFalseOrderByIdDesc(
        com.spring.aichat.domain.enums.CharacterSource source,
        com.spring.aichat.domain.enums.CharacterVisibility visibility,
        org.springframework.data.domain.Pageable pageable);

    /** 탐색 피드 커서 페이지 — id < cursor 최신순. */
    List<Character> findBySourceAndVisibilityAndHiddenFalseAndIdLessThanOrderByIdDesc(
        com.spring.aichat.domain.enums.CharacterSource source,
        com.spring.aichat.domain.enums.CharacterVisibility visibility,
        Long idCursor,
        org.springframework.data.domain.Pageable pageable);

    /** 백오피스 승인 큐 — 공개 심사 대기. */
    List<Character> findByVisibilityOrderByIdAsc(com.spring.aichat.domain.enums.CharacterVisibility visibility);

    /** 백오피스 승인 큐 — Secret 단독 심사 대기. */
    List<Character> findBySecretReviewStatusOrderByIdAsc(com.spring.aichat.domain.enums.SecretReviewStatus status);

    /** UGC slug 충돌 방지용. */
    boolean existsBySlug(String slug);
}