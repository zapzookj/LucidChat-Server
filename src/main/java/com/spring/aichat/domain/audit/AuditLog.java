package com.spring.aichat.domain.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관리자 행위 감사 로그.
 *
 * 금전(에너지/구독/환불)·계정 정지·CI 재바인딩·콘텐츠 토글 등 민감 액션을
 * "누가·언제·무엇에" 수행했는지 기록한다. 금전·정지가 걸린 액션이라 P0.
 *
 * actor/target 은 FK가 아닌 문자열/ID 스냅샷으로 저장한다 —
 * 대상 유저가 삭제되어도 감사 이력은 보존되어야 하기 때문이다.
 * (프로젝트 규약: @Getter @NoArgsConstructor, 세터 없음, 정적 팩토리 + @PrePersist)
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_created_at", columnList = "created_at"),
    @Index(name = "idx_audit_actor", columnList = "actor_username"),
    @Index(name = "idx_audit_action", columnList = "action"),
    @Index(name = "idx_audit_target", columnList = "target_type, target_id")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 관리자(행위자) 유저 ID 스냅샷. 유저 삭제 대비 nullable. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** 관리자(행위자) username 스냅샷. */
    @Column(name = "actor_username", nullable = false, length = 100)
    private String actorUsername;

    /** 액션 코드. 예: USER_SUSPEND, ENERGY_GRANT, REFUND_EXECUTE, CI_REBIND, CHARACTER_HIDE. */
    @Column(name = "action", nullable = false, length = 60)
    private String action;

    /** 대상 유형. 예: USER, ORDER, CHARACTER, TICKET. */
    @Column(name = "target_type", length = 40)
    private String targetType;

    /** 대상 식별자(문자열 스냅샷). */
    @Column(name = "target_id", length = 100)
    private String targetId;

    /** 사람이 읽는 요약. */
    @Column(name = "summary", length = 500)
    private String summary;

    /** 선택적 구조화 상세(JSON 문자열). before/after 값 등. */
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public static AuditLog of(Long actorUserId, String actorUsername, String action,
                              String targetType, String targetId, String summary, String detailJson) {
        AuditLog a = new AuditLog();
        a.actorUserId = actorUserId;
        a.actorUsername = actorUsername;
        a.action = action;
        a.targetType = targetType;
        a.targetId = targetId;
        a.summary = summary;
        a.detailJson = detailJson;
        return a;
    }
}
