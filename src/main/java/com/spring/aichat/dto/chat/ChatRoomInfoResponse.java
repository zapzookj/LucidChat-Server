package com.spring.aichat.dto.chat;

/**
 * 채팅방 정보 응답 DTO
 *
 * [Phase 4.1] 씬 상태 필드 추가 — 재접속 시 마지막 씬 복원용
 * [Phase 4.3] 엔딩 상태 필드 추가 — 재접속 시 엔딩 도달 여부 확인
 * [Phase 4.5] chatMode 필드 추가 — STORY / SANDBOX 분기 처리용
 * [Phase 5]   characterSlug 추가 — 프론트엔드 에셋 경로 resolve용
 *             defaultOutfit/defaultLocation 추가 — 캐릭터별 초기 상태
 */
public record ChatRoomInfoResponse(
    Long id,
    String characterName,
    String characterSlug,          // [Phase 5] 에셋 경로 key (예: "airi", "yeonhwa")
    String defaultImageUrl,
    String backgroundImageUrl,
    int affectionScore,
    String statusLevel,
    // [Phase 4.5] 채팅 모드
    String chatMode,               // STORY | SANDBOX
    // [Phase 4.1] 씬 상태
    String currentBgmMode,
    String currentLocation,
    String currentOutfit,
    String currentTimeOfDay,
    // [Phase 5] 캐릭터별 기본값
    String defaultOutfit,          // 캐릭터 기본 복장 (MAID, HANBOK 등)
    String defaultLocation,        // 캐릭터 기본 장소 (ENTRANCE, FOREST 등)
    // [Phase 4.3] 엔딩 상태
    boolean endingReached,
    String endingType,             // HAPPY | BAD | null
    String endingTitle             // 동적 엔딩 제목 | null
) {}