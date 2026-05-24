package com.spring.aichat.domain.world;

import com.spring.aichat.domain.enums.WorldId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * [V2 Story] World 종속 사전 정의 페르소나 Repository.
 *
 * <p>주 사용처:
 * - StoryCreateFlow Step 2 (페르소나 단계) — 활성 페르소나 풀 노출
 * - 디렉터 prompt [7] USER ACTOR PERSONA 빌딩 시 fallback (페르소나 key 저장 시)
 */
public interface UserPersonaPresetRepository extends JpaRepository<UserPersonaPreset, Long> {

    /**
     * 특정 World의 활성 사전 정의 페르소나 — display_order 정렬.
     * StoryCreateFlow Step 2 UI 노출용.
     */
    List<UserPersonaPreset> findByWorldIdAndActiveTrueOrderByDisplayOrderAsc(WorldId worldId);

    /**
     * 특정 World + presetKey로 단일 조회.
     * 유저가 preset을 선택한 경우 본문 텍스트를 ChatRoom.userPersona에 저장 시 사용.
     */
    Optional<UserPersonaPreset> findByWorldIdAndPresetKey(WorldId worldId, String presetKey);
}