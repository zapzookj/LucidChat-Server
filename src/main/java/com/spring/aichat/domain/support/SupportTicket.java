package com.spring.aichat.domain.support;

import com.spring.aichat.domain.enums.SupportTicketStatus;
import com.spring.aichat.domain.enums.SupportTicketType;
import com.spring.aichat.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * CS 티켓 (Phase 6). 문의/피드백/버그신고가 티켓으로 흘러 백오피스 큐에서 처리된다.
 * 답변은 SupportTicketReply 스레드 + 유저 인앱 알림으로 회신.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "support_tickets", indexes = {
    @Index(name = "idx_ticket_user", columnList = "user_id"),
    @Index(name = "idx_ticket_status", columnList = "status"),
    @Index(name = "idx_ticket_type", columnList = "type")
})
public class SupportTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private SupportTicketType type;

    @Column(name = "category", length = 60)
    private String category;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SupportTicketStatus status = SupportTicketStatus.OPEN;

    /** 담당 관리자 username (미배정 시 null). */
    @Column(name = "assignee", length = 100)
    private String assignee;

    /** 제출 시 로그인 컨텍스트 또는 신고 로그 컨텍스트 스냅샷(JSON). */
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public static SupportTicket create(User user, SupportTicketType type, String category,
                                       String subject, String body, String contextJson) {
        SupportTicket t = new SupportTicket();
        t.user = user;
        t.type = type;
        t.category = category;
        t.subject = subject;
        t.body = body;
        t.contextJson = contextJson;
        t.status = SupportTicketStatus.OPEN;
        return t;
    }

    public void assign(String assignee) {
        this.assignee = assignee;
    }

    public void changeStatus(SupportTicketStatus status) {
        this.status = status;
    }

    /** 답변 등록 등으로 갱신 시각을 밀어 올린다(@PreUpdate 트리거용). */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
