package com.spring.aichat.domain.support;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** CS 티켓 답변 스레드 항목 (유저 후속 문의 또는 관리자 답변). */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "support_ticket_replies", indexes = {
    @Index(name = "idx_reply_ticket", columnList = "ticket_id")
})
public class SupportTicketReply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private SupportTicket ticket;

    /** USER / ADMIN */
    @Column(name = "author_type", nullable = false, length = 20)
    private String authorType;

    @Column(name = "author_name", nullable = false, length = 100)
    private String authorName;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public static SupportTicketReply of(SupportTicket ticket, String authorType, String authorName, String body) {
        SupportTicketReply r = new SupportTicketReply();
        r.ticket = ticket;
        r.authorType = authorType;
        r.authorName = authorName;
        r.body = body;
        return r;
    }
}
