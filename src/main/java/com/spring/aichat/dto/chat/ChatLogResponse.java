package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;

import java.time.LocalDateTime;

/**
 * 채팅 로그 조회 응답 DTO
 *
 * [Phase 5] MongoDB 마이그레이션:
 * - logId: Long → String (MongoDB ObjectId)
 * - 프론트엔드에서는 logId를 식별자로만 사용하므로 타입 변경에 따른 영향 없음
 */
public record ChatLogResponse(
    String logId,
    ChatRole role,
    String rawContent,
    String cleanContent,
    EmotionTag emotionTag,
    LocalDateTime createdAt
) {}