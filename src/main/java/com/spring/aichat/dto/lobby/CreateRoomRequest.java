package com.spring.aichat.dto.lobby;

import jakarta.validation.constraints.NotNull;

/**
 * [Phase 4.5] 채팅방 생성 요청 DTO
 *
 * 캐릭터 + 모드 선택 후 입장 시 사용. 페르소나는 [블록 B] 피커 없이
 * 서버가 현재 활성 프로필을 자동 스냅샷한다(신규 방에만, 기존 방 재입장은 무변).
 */
public record CreateRoomRequest(
    @NotNull(message = "캐릭터 ID는 필수입니다.")
    Long characterId,

    @NotNull(message = "채팅 모드는 필수입니다.")
    String chatMode   // "STORY" or "SANDBOX"
) {}
