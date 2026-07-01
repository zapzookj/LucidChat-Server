package com.spring.aichat.dto.support;

import com.spring.aichat.domain.support.SupportTicket;
import com.spring.aichat.domain.support.SupportTicketReply;
import com.spring.aichat.domain.user.User;

import java.time.LocalDateTime;
import java.util.List;

/** CS 티켓 상세 + 답변 스레드 + 유저 스냅샷. */
public record SupportTicketDetail(
    Long id,
    String type,
    String category,
    String subject,
    String body,
    String status,
    String assignee,
    String contextJson,
    Long userId,
    String username,
    String nickname,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<SupportTicketReplyResponse> replies
) {
    public static SupportTicketDetail from(SupportTicket t, List<SupportTicketReply> replies, User user) {
        return new SupportTicketDetail(
            t.getId(), t.getType().name(), t.getCategory(), t.getSubject(), t.getBody(),
            t.getStatus().name(), t.getAssignee(), t.getContextJson(),
            user.getId(), user.getUsername(), user.getNickname(),
            t.getCreatedAt(), t.getUpdatedAt(),
            replies.stream().map(SupportTicketReplyResponse::from).toList());
    }
}
