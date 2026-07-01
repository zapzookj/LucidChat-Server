package com.spring.aichat.domain.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByIdDesc(Pageable pageable);

    Page<AuditLog> findByActorUsernameOrderByIdDesc(String actorUsername, Pageable pageable);

    Page<AuditLog> findByActionOrderByIdDesc(String action, Pageable pageable);

    Page<AuditLog> findByTargetTypeAndTargetIdOrderByIdDesc(String targetType, String targetId, Pageable pageable);
}
