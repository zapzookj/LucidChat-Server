package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.chat.ChatLogDocument;

import java.time.LocalDateTime;

public record QualityLogResponse(
    String id,
    Long roomId,
    String content,
    String dislikeReason,
    LocalDateTime createdAt
) {
    public static QualityLogResponse from(ChatLogDocument d) {
        return new QualityLogResponse(d.getId(), d.getRoomId(), d.getCleanContent(), d.getDislikeReason(), d.getCreatedAt());
    }
}
