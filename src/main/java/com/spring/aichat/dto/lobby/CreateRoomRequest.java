package com.spring.aichat.dto.lobby;

import jakarta.validation.constraints.NotNull;

/**
 * [Phase 4.5] 채팅방 생성 요청 DTO
 *
 * '새로운 만남' 화면에서 캐릭터 + 모드를 선택한 후 입장 시 사용
 */
public record CreateRoomRequest(
    @NotNull(message = "캐릭터 ID는 필수입니다.")
    Long characterId,

    @NotNull(message = "채팅 모드는 필수입니다.")
    String chatMode,   // "STORY" or "SANDBOX"

    /** [2026-08-04 페르소나] 카드 선택 — 신규 방 생성 시에만 스냅샷 적용(기존 방 재입장은 무변). */
    Long userPersonaId
) {}