package com.spring.aichat.service.audit;

import com.spring.aichat.domain.audit.AuditLog;
import com.spring.aichat.domain.audit.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 감사 로그 기록 서비스.
 *
 * 각 admin 뮤테이션 서비스는 액션 수행 직후 이 서비스로 감사 이력을 남긴다.
 * 기록은 호출자의 트랜잭션에 참여한다(default REQUIRED) — 액션이 롤백되면 감사도 함께
 * 롤백되어 "실제로 일어난 일"만 기록된다. 민감(금전/정지) 액션의 감사는 P0 요구사항이므로
 * best-effort 스왈로우 대신 원자성을 택한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public void record(Long actorUserId, String actorUsername, String action,
                       String targetType, String targetId, String summary, String detailJson) {
        auditLogRepository.save(AuditLog.of(actorUserId, actorUsername, action, targetType, targetId, summary, detailJson));
        log.info("[AUDIT] actor={}({}) action={} target={}/{} :: {}",
            actorUsername, actorUserId, action, targetType, targetId, summary);
    }

    /** actorUserId/detailJson 없이 간단 기록. */
    @Transactional
    public void record(String actorUsername, String action, String targetType, String targetId, String summary) {
        record(null, actorUsername, action, targetType, targetId, summary, null);
    }
}
