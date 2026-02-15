package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.EmotionTag;

import java.util.List;

/**
 * 채팅 전송 응답 DTO
 *
 * [Phase 4] SceneResponse에 location, time, outfit, bgmMode 추가
 */
public record SendChatResponse(
    Long roomId,
    List<SceneResponse> scenes,
    int currentAffection,
    String relationStatus
) {
    public record SceneResponse(
        String narration,
        String dialogue,
        EmotionTag emotion,
        String location,   // [Phase 4] null이면 프론트에서 이전 값 유지
        String time,       // [Phase 4] null이면 프론트에서 이전 값 유지
        String outfit,     // [Phase 4] null이면 프론트에서 이전 값 유지
        String bgmMode     // [Phase 4] null이면 프론트에서 이전 값 유지
    ) {}
}
