package com.spring.aichat.domain.world;

import com.spring.aichat.domain.enums.WorldId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * [V2 Story] World-bound 장소 풀 Repository.
 *
 * <p>주 사용처:
 * - StoryCreateFlow Step 4 (시작 장소 선택) — selectable + 활성 필터
 * - ChatPage 장소 전환 UI — 활성 필터, 전체 풀
 * - 디렉터 prompt [2] WORLD 섹션 빌딩 — 활성 필터
 * - 위치 검증 (currentUserLocationKey가 유효한가)
 */
public interface WorldLocationRepository extends JpaRepository<WorldLocation, Long> {

    /**
     * 특정 World의 활성 장소 전체 — display_order 정렬.
     * ChatPage 장소 전환 UI에서 사용.
     */
    List<WorldLocation> findByWorldIdAndActiveTrueOrderByDisplayOrderAsc(WorldId worldId);

    /**
     * 특정 World의 시작 장소 후보 — selectable + 활성 + display_order 정렬.
     * StoryCreateFlow 시작 장소 선택 단계 전용.
     */
    List<WorldLocation> findByWorldIdAndActiveTrueAndSelectableAsStartTrueOrderByDisplayOrderAsc(WorldId worldId);

    /**
     * 특정 World + locationKey로 단일 조회.
     * 위치 검증 + 디렉터 응답의 location 키 매핑용.
     */
    Optional<WorldLocation> findByWorldIdAndLocationKey(WorldId worldId, String locationKey);

    /**
     * 특정 World에 장소 존재 여부.
     * 시드 검증 / API 입력 검증용.
     */
    boolean existsByWorldIdAndLocationKey(WorldId worldId, String locationKey);
}