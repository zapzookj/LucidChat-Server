package com.spring.aichat.dto.lobby;

import com.spring.aichat.domain.enums.WorldId;

import java.time.LocalDateTime;

/**
 * [Phase 4.5] 로비 채팅방 요약 응답 DTO
 *
 * '기억의 끈' (Continue / Load Game) 화면에서 사용
 * - 각 카드: 캐릭터 썸네일, 이름, 호감도, 마지막 대화 시간, 모드 뱃지
 *
 * [Phase 5.5-Fix] 지배 스탯 정보 추가
 * - dominantStatName: 5종 스탯 중 가장 높은 스탯의 키
 * - dominantStatValue: 해당 스탯의 현재 수치
 * - dynamicRelationTag: 동적 관계 태그 텍스트
 *
 * [Phase 7-V2 / Chunk D] V2 STORY 방 식별·표시용 필드 추가 (additive, V1 호출 시 null/0):
 * - worldId: V2 STORY 방의 World FK (V1 Sandbox/Story 방은 null)
 * - currentDay: V2 진행 일차 (V1 Sandbox는 0)
 * - heroineCount: V2 방의 ChatRoomHeroine 수 (V1 Sandbox는 0)
 *
 * 프론트엔드는 worldId !== null 또는 (chatMode === "STORY" && characterId === null) 으로
 * V2 방을 식별하여 StoryV2RoomCard로 렌더링.
 */
public record RoomSummaryResponse(
    Long roomId,
    // 캐릭터 정보 (V2 방에선 World 이름/썸네일로 매핑됨)
    Long characterId,
    String characterName,
    String characterThumbnailUrl,
    // 방 상태
    int affectionScore,
    String statusLevel,        // STRANGER, ACQUAINTANCE, FRIEND, LOVER
    String chatMode,           // STORY, SANDBOX
    String lastEmotion,
    LocalDateTime lastActiveAt,
    // 엔딩 상태
    boolean endingReached,
    String endingType,         // HAPPY, BAD, null
    String endingTitle,
    // [Phase 5.5-Fix] 지배 스탯 정보
    String dominantStatName,   // intimacy, affection, dependency, playfulness, trust
    int dominantStatValue,
    String dynamicRelationTag // 동적 관계 태그
    // [Phase 7-V2 / Chunk D] V2 STORY 식별·표시 필드 (additive)
//    WorldId worldId,              // V2 World FK (V1은 null)
//    int currentDay,            // V2 진행 일차 (V1 Sandbox는 0)
//    int heroineCount           // V2 ChatRoomHeroine 수 (V1 Sandbox는 0)
) {}