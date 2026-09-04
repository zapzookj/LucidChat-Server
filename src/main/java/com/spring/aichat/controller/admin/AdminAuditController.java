package com.spring.aichat.controller.admin;

import com.spring.aichat.domain.audit.AuditLog;
import com.spring.aichat.domain.audit.AuditLogRepository;
import com.spring.aichat.dto.admin.AuditLogResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
        // [E-6.3.a · E-6.3.b] else-if 사슬 제거 — 네 조건을 각각 독립 AND로 쌓는다.
        //   종전엔 actor가 있으면 action이, actor·action이 있으면 target 조건이 조용히 버려졌고,
        //   targetType만(또는 targetId만) 주면 필터가 통째로 사라져 전체 목록이 나왔다.
        //   정렬은 파생 메서드 이름(OrderByIdDesc)이 아니라 Pageable이 진다.
        Pageable pageable = PageRequest.of(
            Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "id"));

        Page<AuditLog> result = auditLogRepository.findAll(
            eq("actorUsername", actor)
                .and(eq("action", action))
                .and(eq("targetType", targetType))
                .and(eq("targetId", targetId)),
            pageable);
        return result.map(AuditLogResponse::from);
    }

    /**
     * 값이 비어 있으면 항상 참(=조건 미적용), 아니면 해당 컬럼 일치.
     * 빈 문자열·공백만 들어온 경우도 '미지정'으로 본다(어드민 SPA가 빈 칸을 그대로 보낸다).
     */
    private static Specification<AuditLog> eq(String field, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return (root, query, cb) -> cb.conjunction();
        String value = rawValue.trim();
        return (root, query, cb) -> cb.equal(root.get(field), value);
    }
}
