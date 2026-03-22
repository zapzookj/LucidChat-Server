package com.spring.aichat.domain.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * [Phase 5.5-Perf] 장기 기억 요약 JPA Repository
 *
 * 쿼리 전략:
 * - findByRoomId~: 방별 기억 조회 (캐시 미스 시 사용)
 * - deleteByRoomId: 대화 초기화 시 기억 일괄 삭제
 */
public interface MemorySummaryRepository extends JpaRepository<MemorySummary, Long> {

    /**
     * 방별 장기 기억 전체 조회 (시간순 오름차순)
     * Redis 캐시 미스 시 폴백으로 사용.
     */
    List<MemorySummary> findByRoomIdOrderByCreatedAtAsc(Long roomId);

    /**
     * 방별 기억 삭제 (대화 초기화 시)
     */
    void deleteByRoomId(Long roomId);

    /**
     * 방별 기억 개수 조회
     */
    long countByRoomId(Long roomId);
}