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
    String chatMode   // "STORY" or "SANDBOX"
) {}