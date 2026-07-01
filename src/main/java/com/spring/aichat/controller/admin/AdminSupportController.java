package com.spring.aichat.controller.admin;

import com.spring.aichat.domain.enums.SupportTicketStatus;
import com.spring.aichat.domain.enums.SupportTicketType;
import com.spring.aichat.dto.support.AssignRequest;
import com.spring.aichat.dto.support.SupportTicketDetail;
import com.spring.aichat.dto.support.SupportTicketSummary;
import com.spring.aichat.dto.support.TicketReplyRequest;
import com.spring.aichat.dto.support.TicketStatusRequest;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.support.SupportTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 관리자 CS 티켓 큐. /api/v1/admin/** ROLE_ADMIN 게이트. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/support/tickets")
public class AdminSupportController {

    private final SupportTicketService supportTicketService;

    @GetMapping
    public Page<SupportTicketSummary> list(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String type,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        SupportTicketStatus st = (status != null && !status.isBlank()) ? parseStatus(status) : null;
        SupportTicketType ty = (type != null && !type.isBlank()) ? parseType(type) : null;
        return supportTicketService.adminList(st, ty, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    @GetMapping("/{id}")
    public SupportTicketDetail get(@PathVariable Long id) {
        return supportTicketService.adminGet(id);
    }

    @PostMapping("/{id}/replies")
    public SupportTicketDetail reply(@PathVariable Long id, @RequestBody @Valid TicketReplyRequest req, Authentication auth) {
        return supportTicketService.adminReply(auth.getName(), id, req.body());
    }

    @PostMapping("/{id}/assign")
    public SupportTicketDetail assign(@PathVariable Long id, @RequestBody @Valid AssignRequest req, Authentication auth) {
        return supportTicketService.adminAssign(auth.getName(), id, req.assignee());
    }

    @PostMapping("/{id}/status")
    public SupportTicketDetail status(@PathVariable Long id, @RequestBody @Valid TicketStatusRequest req, Authentication auth) {
        return supportTicketService.adminChangeStatus(auth.getName(), id, parseStatus(req.status()));
    }

    private SupportTicketStatus parseStatus(String s) {
        try {
            return SupportTicketStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "알 수 없는 상태: " + s);
        }
    }

    private SupportTicketType parseType(String s) {
        try {
            return SupportTicketType.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "알 수 없는 유형: " + s);
        }
    }
}
