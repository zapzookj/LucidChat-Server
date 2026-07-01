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
}