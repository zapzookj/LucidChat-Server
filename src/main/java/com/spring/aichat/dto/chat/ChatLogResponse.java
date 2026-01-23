package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;

import java.time.LocalDateTime;

/**
 * 채팅 로그 조회 응답 DTO
 */
public record ChatLogResponse(
    Long logId,
    ChatRole role,
    String rawContent,
    String cleanContent,
    EmotionTag emotionTag,
    LocalDateTime createdAt
) {}
