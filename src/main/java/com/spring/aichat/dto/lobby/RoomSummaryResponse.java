package com.spring.aichat.dto.lobby;

import java.time.LocalDateTime;

/**
 * [Phase 4.5] 로비 채팅방 요약 응답 DTO
 *
 * '기억의 끈' (Continue / Load Game) 화면에서 사용
 * - 각 카드: 캐릭터 썸네일, 이름, 호감도, 마지막 대화 시간, 모드 뱃지
 */
public record RoomSummaryResponse(
    Long roomId,
    // 캐릭터 정보
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
    String endingTitle
) {}