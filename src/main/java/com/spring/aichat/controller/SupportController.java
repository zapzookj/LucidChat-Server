package com.spring.aichat.controller;

import com.spring.aichat.domain.enums.SupportTicketType;
import com.spring.aichat.dto.support.CreateTicketRequest;
import com.spring.aichat.dto.support.ReportRequest;
import com.spring.aichat.dto.support.SupportTicketDetail;
import com.spring.aichat.dto.support.SupportTicketSummary;
import com.spring.aichat.dto.support.TicketReplyRequest;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.support.SupportTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 유저 대면 CS — 문의/피드백/버그 제출, 내 티켓 조회, 채팅 응답 신고. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support")
public class SupportController {

    private final SupportTicketService supportTicketService;

    @PostMapping("/tickets")
    public SupportTicketDetail create(@RequestBody @Valid CreateTicketRequest req, Authentication auth) {
        return supportTicketService.createTicket(auth.getName(), parseType(req.type()), req.category(), req.subject(), req.body());
    }

    @GetMapping("/tickets")
    public List<SupportTicketSummary> myTickets(Authentication auth) {
        return supportTicketService.listUserTickets(auth.getName());
    }

    @GetMapping("/tickets/{id}")
    public SupportTicketDetail detail(@PathVariable Long id, Authentication auth) {
        return supportTicketService.getUserTicket(auth.getName(), id);
    }

    @PostMapping("/tickets/{id}/replies")
    public SupportTicketDetail reply(@PathVariable Long id, @RequestBody @Valid TicketReplyRequest req, Authentication auth) {
        return supportTicketService.userReply(auth.getName(), id, req.body());
    }

    /** 채팅 내 "이 응답 신고" — 로그 컨텍스트가 티켓에 자동 첨부됨. */
    @PostMapping("/reports")
    public SupportTicketDetail report(@RequestBody ReportRequest req, Authentication auth) {
        return supportTicketService.report(auth.getName(), req);
    }

    private SupportTicketType parseType(String s) {
        try {
            return SupportTicketType.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "알 수 없는 티켓 유형: " + s);
        }
    }
}
