package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;

import java.time.LocalDateTime;

/**
 * 채팅 로그 조회 응답 DTO
 *
 * [Phase 5] MongoDB 마이그레이션:
 * - logId: Long → String (MongoDB ObjectId)
 *
 * [Phase 5.1] rating 필드 추가:
 * - "LIKE" / "DISLIKE" / null
 * - 프론트엔드에서 좋아요/싫어요 상태를 표시하기 위해 사용
 */
public record ChatLogResponse(
    String logId,
    ChatRole role,
    String rawContent,
    String cleanContent,
    EmotionTag emotionTag,
    LocalDateTime createdAt,
    String rating           // [Phase 5.1] "LIKE" | "DISLIKE" | null
) {}