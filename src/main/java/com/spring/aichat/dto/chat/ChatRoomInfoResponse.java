package com.spring.aichat.dto.chat;

/**
 * 채팅방 정보 응답 DTO
 *
 * [Phase 5.5-EV] 이벤트 시스템 강화 필드 추가:
 *   - topicConcluded: 주제 종료 플래그
 *   - eventActive: 디렉터 모드 이벤트 진행 중 여부
 *   - eventStatus: "ONGOING" | "RESOLVED" | null
 */
public record ChatRoomInfoResponse(
    Long id,
    String characterName,
    String characterSlug,
    Long characterId,
    String defaultImageUrl,
    String backgroundImageUrl,
    int affectionScore,
    String statusLevel,
    String chatMode,
    String currentBgmMode,
    String currentLocation,
    String currentOutfit,
    String currentTimeOfDay,
    String defaultOutfit,
    String defaultLocation,
    boolean endingReached,
    String endingType,
    String endingTitle,
    java.util.List<String> availableOutfits,
    java.util.List<String> availableLocations,
    // ── [Phase 5.5] 입체적 상태창 ──
    SendChatResponse.StatsSnapshot stats,
    int bpm,
    String dynamicRelationTag,
    String characterThought,
    // ── [Phase 5.5-EV] 이벤트 시스템 강화 ──
    boolean topicConcluded,
    boolean eventActive,
    String eventStatus,
    // ── [Phase 5.5-Fix] 동적 배경 영속화 ──
    String currentDynamicLocationName,
    String currentDynamicBgUrl
) {}