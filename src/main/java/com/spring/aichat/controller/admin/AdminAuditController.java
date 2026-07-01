package com.spring.aichat.controller.admin;

import com.spring.aichat.domain.audit.AuditLog;
import com.spring.aichat.domain.audit.AuditLogRepository;
import com.spring.aichat.dto.admin.AuditLogResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 감사 로그 조회 API.
 *
 * 경로 /api/v1/admin/** 은 SecurityConfig 에서 hasRole('ADMIN') 으로 게이트된다.
 * (별도 admin SPA가 ROLE_ADMIN JWT로 호출)
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/audit-logs")
public class AdminAuditController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public Page<AuditLogResponse> list(
        @RequestParam(required = false) String actor,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String targetType,
        @RequestParam(required = false) String targetId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "30") int size
    ) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));

        Page<AuditLog> result;
        if (actor != null && !actor.isBlank()) {
            result = auditLogRepository.findByActorUsernameOrderByIdDesc(actor.trim(), pageable);
        } else if (action != null && !action.isBlank()) {
            result = auditLogRepository.findByActionOrderByIdDesc(action.trim(), pageable);
        } else if (targetType != null && !targetType.isBlank() && targetId != null && !targetId.isBlank()) {
            result = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc(targetType.trim(), targetId.trim(), pageable);
        } else {
            result = auditLogRepository.findAllByOrderByIdDesc(pageable);
        }
        return result.map(AuditLogResponse::from);
    }
}
