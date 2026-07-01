package com.spring.aichat.dto.support;

import com.spring.aichat.domain.support.SupportTicketReply;

import java.time.LocalDateTime;

public record SupportTicketReplyResponse(
    Long id,
    String authorType,
    String authorName,
    String body,
    LocalDateTime createdAt
) {
    public static SupportTicketReplyResponse from(SupportTicketReply r) {
        return new SupportTicketReplyResponse(
            r.getId(), r.getAuthorType(), r.getAuthorName(), r.getBody(), r.getCreatedAt());
    }
}
