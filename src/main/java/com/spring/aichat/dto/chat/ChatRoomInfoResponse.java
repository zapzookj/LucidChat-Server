package com.spring.aichat.dto.chat;

/**
 * 채팅방 정보 응답 DTO
 *
 * [Phase 4.1] 씬 상태 필드 추가 — 재접속 시 마지막 씬 복원용
 * [Phase 4.3] 엔딩 상태 필드 추가 — 재접속 시 엔딩 도달 여부 확인
 */
public record ChatRoomInfoResponse(
    Long id,
    String characterName,
    String defaultImageUrl,
    String backgroundImageUrl,
    int affectionScore,
    String statusLevel,
    // [Phase 4.1] 씬 상태
    String currentBgmMode,
    String currentLocation,
    String currentOutfit,
    String currentTimeOfDay,
    // [Phase 4.3] 엔딩 상태
    boolean endingReached,
    String endingType,      // HAPPY | BAD | null
    String endingTitle      // 동적 엔딩 제목 | null
) {}