package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.chat.ChatLogDocument;

import java.time.LocalDateTime;

/** CS 로그 뷰어 — 방 로그 행 (USER/ASSISTANT/SYSTEM, 속마음/평가 포함). */
public record AdminChatLogResponse(
    String id,
    String role,
    String content,
    String innerThought,
    String rating,
    String dislikeReason,
    boolean hidden,
    LocalDateTime createdAt
) {
    public static AdminChatLogResponse from(ChatLogDocument d) {
        return new AdminChatLogResponse(
            d.getId(),
            d.getRole() != null ? d.getRole().name() : null,
            d.getCleanContent(),
            d.getInnerThought(),
            d.getRating(),
            d.getDislikeReason(),
            d.isHidden(),
            d.getCreatedAt());
    }
}
