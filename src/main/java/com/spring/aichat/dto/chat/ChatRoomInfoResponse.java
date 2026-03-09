package com.spring.aichat.dto.chat;

/**
 * 채팅방 정보 응답 DTO
 *
 * [Phase 4.1]  씬 상태 필드 추가 — 재접속 시 마지막 씬 복원용
 * [Phase 4.3]  엔딩 상태 필드 추가 — 재접속 시 엔딩 도달 여부 확인
 * [Phase 4.5]  chatMode 필드 추가 — STORY / SANDBOX 분기 처리용
 * [Phase 5]    characterSlug 추가 — 프론트엔드 에셋 경로 resolve용
 * [Phase 5 Fix] characterId 추가 — 시크릿 모드 토글 시 캐릭터별 권한 검증용
 * [Phase 4 Fix] availableOutfits/Locations 추가 — 프론트엔드 가드용
 */
public record ChatRoomInfoResponse(
    Long id,
    String characterName,
    String characterSlug,
    Long characterId,                // [Phase 5 Fix] 시크릿 모드 토글용 캐릭터 ID
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
    java.util.List<String> availableLocations
) {}