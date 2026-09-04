package com.spring.aichat.domain.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * [E-6.3.a · E-6.3.b] 파생 메서드 4종을 제거하고 Specification 단일 경로로 바꿨다.
 *
 * <p>종전엔 컨트롤러가 else-if 사슬로 하나만 골라 호출해서
 * ① actor+action 동시 지정 시 action이 조용히 무시되고
 * ② targetType/targetId는 actor·action이 있으면 아예 도달하지 못했으며
 * ③ targetType만(또는 targetId만) 주면 필터가 통째로 사라져 전체 목록으로 침묵 강등됐다.
 *
 * <p>Specification은 non-null 조건만 AND로 쌓으므로 세 증상이 한꺼번에 사라진다.
 * {@code @Query}의 {@code (:p is null or ...)} 패턴을 쓰지 않은 이유는 PostgreSQL이
 * 파라미터 타입을 추론하지 못해 런타임에만 터질 수 있고, 이 저장소엔 리포지토리 테스트가
 * 0건이라 부팅으로도 안 잡히기 때문이다.
 *
 * <p>§2-6대로 하위호환 파생 메서드는 남기지 않았다 — 남기면 다음 호출부가 조용히
 * 낡은 단일 조건 경로로 컴파일된다.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}
