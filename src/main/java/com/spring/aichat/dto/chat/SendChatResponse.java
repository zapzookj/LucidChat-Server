package com.spring.aichat.dto.chat;

/**
 * 채팅 전송 응답 DTO
 */
public record SendChatResponse(
    String rawResponse,
    String cleanResponse,
    String stageDirection,
    String emotion,
    int currentAffection
) {}
