package com.spring.aichat.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.enums.SupportTicketStatus;
import com.spring.aichat.domain.enums.SupportTicketType;
import com.spring.aichat.domain.support.SupportTicket;
import com.spring.aichat.domain.support.SupportTicketReply;
import com.spring.aichat.domain.support.SupportTicketReplyRepository;
import com.spring.aichat.domain.support.SupportTicketRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.support.ReportRequest;
import com.spring.aichat.dto.support.SupportTicketDetail;
import com.spring.aichat.dto.support.SupportTicketSummary;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.audit.AuditLogService;
import com.spring.aichat.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CS 티켓 파이프라인 (Phase 6). 유저 제출 → 큐 → 관리자 답변 → 인앱 알림.
 * 제출 시 로그인 컨텍스트(에너지/구독/성인/provider)를 스냅샷으로 첨부한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupportTicketService {

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketReplyRepository replyRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    // ─────────────── 유저 ───────────────

    @Transactional
    public SupportTicketDetail createTicket(String username, SupportTicketType type,
                                            String category, String subject, String body) {
        User user = loadUser(username);
        SupportTicket ticket = ticketRepository.save(
            SupportTicket.create(user, type, category, subject, body, buildUserContext(user, null)));
        return SupportTicketDetail.from(ticket, List.of(), user);
    }

    @Transactional
    public SupportTicketDetail report(String username, ReportRequest req) {
        User user = loadUser(username);
        Map<String, Object> logCtx = new LinkedHashMap<>();
        logCtx.put("kind", "CHAT_REPORT");
        logCtx.put("roomId", req.roomId());
        logCtx.put("logId", req.logId());
        logCtx.put("role", req.role());
        logCtx.put("speaker", req.speaker());
        logCtx.put("message", req.message());

        String subject = "채팅 응답 신고" + (req.roomId() != null ? " (방 " + req.roomId() + ")" : "");
        String body = (req.reason() != null && !req.reason().isBlank()) ? req.reason() : "채팅 응답 신고";
        SupportTicket ticket = ticketRepository.save(
            SupportTicket.create(user, SupportTicketType.BUG, "CHAT_REPORT", subject, body, buildUserContext(user, logCtx)));
        return SupportTicketDetail.from(ticket, List.of(), user);
    }

    @Transactional(readOnly = true)
    public List<SupportTicketSummary> listUserTickets(String username) {
        Long userId = loadUser(username).getId();
        return ticketRepository.findByUser_IdOrderByIdDesc(userId).stream()
            .map(SupportTicketSummary::from).toList();
    }

    @Transactional(readOnly = true)
    public SupportTicketDetail getUserTicket(String username, Long ticketId) {
        User user = loadUser(username);
        SupportTicket ticket = loadTicket(ticketId);
        requireOwner(ticket, user);
        return SupportTicketDetail.from(ticket, replyRepository.findByTicket_IdOrderByIdAsc(ticketId), ticket.getUser());
    }

    @Transactional
    public SupportTicketDetail userReply(String username, Long ticketId, String body) {
        User user = loadUser(username);
        SupportTicket ticket = loadTicket(ticketId);
        requireOwner(ticket, user);
        replyRepository.save(SupportTicketReply.of(ticket, "USER", user.getNickname(), body));
        if (ticket.getStatus() == SupportTicketStatus.RESOLVED) ticket.changeStatus(SupportTicketStatus.OPEN);
        else ticket.touch();
        return SupportTicketDetail.from(ticket, replyRepository.findByTicket_IdOrderByIdAsc(ticketId), ticket.getUser());
    }

    // ─────────────── 관리자 ───────────────

    @Transactional(readOnly = true)
    public Page<SupportTicketSummary> adminList(SupportTicketStatus status, SupportTicketType type, Pageable pageable) {
        Page<SupportTicket> page;
        if (status != null) page = ticketRepository.findByStatusOrderByIdDesc(status, pageable);
        else if (type != null) page = ticketRepository.findByTypeOrderByIdDesc(type, pageable);
        else page = ticketRepository.findAllByOrderByIdDesc(pageable);
        return page.map(SupportTicketSummary::from);
    }

    @Transactional(readOnly = true)
    public SupportTicketDetail adminGet(Long ticketId) {
        SupportTicket ticket = loadTicket(ticketId);
        return SupportTicketDetail.from(ticket, replyRepository.findByTicket_IdOrderByIdAsc(ticketId), ticket.getUser());
    }

    @Transactional
    public SupportTicketDetail adminReply(String actor, Long ticketId, String body) {
        SupportTicket ticket = loadTicket(ticketId);
        replyRepository.save(SupportTicketReply.of(ticket, "ADMIN", actor, body));
        if (ticket.getStatus() == SupportTicketStatus.OPEN) ticket.changeStatus(SupportTicketStatus.IN_PROGRESS);
        else ticket.touch();
        notificationService.notify(ticket.getUser().getId(), "TICKET_REPLY",
            "문의에 답변이 등록되었습니다", ticket.getSubject(), "TICKET", String.valueOf(ticketId));
        auditLogService.record(actor, "TICKET_REPLY", "TICKET", String.valueOf(ticketId), "관리자 답변 등록");
        return SupportTicketDetail.from(ticket, replyRepository.findByTicket_IdOrderByIdAsc(ticketId), ticket.getUser());
    }

    @Transactional
    public SupportTicketDetail adminAssign(String actor, Long ticketId, String assignee) {
        SupportTicket ticket = loadTicket(ticketId);
        ticket.assign(assignee);
        ticket.touch();
        auditLogService.record(actor, "TICKET_ASSIGN", "TICKET", String.valueOf(ticketId), "담당자=" + assignee);
        return SupportTicketDetail.from(ticket, replyRepository.findByTicket_IdOrderByIdAsc(ticketId), ticket.getUser());
    }

    @Transactional
    public SupportTicketDetail adminChangeStatus(String actor, Long ticketId, SupportTicketStatus status) {
        SupportTicket ticket = loadTicket(ticketId);
        ticket.changeStatus(status);
        ticket.touch();
        if (status == SupportTicketStatus.RESOLVED) {
            notificationService.notify(ticket.getUser().getId(), "TICKET_RESOLVED",
                "문의가 처리 완료되었습니다", ticket.getSubject(), "TICKET", String.valueOf(ticketId));
        }
        auditLogService.record(actor, "TICKET_STATUS_" + status.name(), "TICKET", String.valueOf(ticketId), "상태 변경");
        return SupportTicketDetail.from(ticket, replyRepository.findByTicket_IdOrderByIdAsc(ticketId), ticket.getUser());
    }

    // ─────────────── helpers ───────────────

    private User loadUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "유저를 찾을 수 없습니다."));
    }

    private SupportTicket loadTicket(Long id) {
        return ticketRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "티켓을 찾을 수 없습니다: " + id));
    }

    private void requireOwner(SupportTicket ticket, User user) {
        if (!ticket.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 티켓만 접근할 수 있습니다.");
        }
    }

    private String buildUserContext(User user, Map<String, Object> extra) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("energy", user.getEnergy());
        ctx.put("paidEnergy", user.getPaidEnergy());
        ctx.put("subscriptionTier", user.getSubscriptionTier() != null ? user.getSubscriptionTier().name() : null);
        ctx.put("isAdult", Boolean.TRUE.equals(user.getIsAdult()));
        ctx.put("provider", user.getProvider() != null ? user.getProvider().name() : null);
        if (extra != null) ctx.putAll(extra);
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            log.warn("[SUPPORT] context 직렬화 실패", e);
            return null;
        }
    }
}
