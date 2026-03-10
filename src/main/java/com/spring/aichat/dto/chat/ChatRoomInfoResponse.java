package com.spring.aichat.dto.chat;

/**
 * 채팅방 정보 응답 DTO
 *
 * [Phase 4.1]  씬 상태 필드 추가 — 재접속 시 마지막 씬 복원용
 * [Phase 4.3]  엔딩 상태 필드 추가
 * [Phase 4.5]  chatMode 필드 추가
 * [Phase 5]    characterSlug, characterId 추가
 * [Phase 4 Fix] availableOutfits/Locations 추가
 * [Phase 5.5]  입체적 상태창 필드 추가
 *              - 5개 노말 스탯 + 3개 시크릿 스탯
 *              - 심박수 BPM
 *              - 동적 관계 태그
 *              - 캐릭터의 생각
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
    // [Phase 4.5] 채팅 모드
    String chatMode,
    // [Phase 4.1] 씬 상태
    String currentBgmMode,
    String currentLocation,
    String currentOutfit,
    String currentTimeOfDay,
    // [Phase 5] 캐릭터별 기본값
    String defaultOutfit,
    String defaultLocation,
    // [Phase 4.3] 엔딩 상태
    boolean endingReached,
    String endingType,
    String endingTitle,
    // [Phase 4 Fix] 캐릭터별 독립 세계관
    java.util.List<String> availableOutfits,
    java.util.List<String> availableLocations,
    // ── [Phase 5.5] 입체적 상태창 ──
    SendChatResponse.StatsSnapshot stats,
    int bpm,
    String dynamicRelationTag,
    String characterThought
) {}