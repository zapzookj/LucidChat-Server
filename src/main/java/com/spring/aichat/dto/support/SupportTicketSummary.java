package com.spring.aichat.dto.support;

import com.spring.aichat.domain.support.SupportTicket;

import java.time.LocalDateTime;

/** CS 티켓 목록 행(유저/관리자 공용). */
public record SupportTicketSummary(
    Long id,
    String type,
    String category,
    String subject,
    String status,
    String assignee,
    Long userId,
    String username,
    String nickname,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static SupportTicketSummary from(SupportTicket t) {
        return new SupportTicketSummary(
            t.getId(), t.getType().name(), t.getCategory(), t.getSubject(),
            t.getStatus().name(), t.getAssignee(),
            t.getUser().getId(), t.getUser().getUsername(), t.getUser().getNickname(),
            t.getCreatedAt(), t.getUpdatedAt());
    }
}
