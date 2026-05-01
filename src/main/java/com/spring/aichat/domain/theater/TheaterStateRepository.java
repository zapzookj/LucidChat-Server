package com.spring.aichat.domain.theater;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TheaterStateRepository extends JpaRepository<TheaterState, Long> {

    Optional<TheaterState> findByRoom_Id(Long roomId);

    boolean existsByRoom_Id(Long roomId);

    void deleteByRoom_Id(Long roomId);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5 UX Polish · R4] 활성 / 아카이브 / 엔딩 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 유저당 활성(ACTIVE) Theater 세션 1개 조회.
     * 정책상 동시에 최대 1개만 ACTIVE.
     *
     * 조건: 세션 status가 'ACTIVE'이거나 null(legacy 데이터는 활성 간주).
     */
    @Query("""
        SELECT s FROM TheaterState s
        WHERE s.room.user.id = :userId
          AND (s.sessionStatus = 'ACTIVE' OR s.sessionStatus IS NULL)
        """)
    Optional<TheaterState> findActiveByUserId(@Param("userId") Long userId);

    /**
     * 유저의 모든 아카이브 세션 (ARCHIVED + ENDED) — 다이어리/아카이브 UI용.
     * 최근 변경순으로 정렬.
     */
    @Query("""
        SELECT s FROM TheaterState s
        WHERE s.room.user.id = :userId
          AND s.sessionStatus IN ('ARCHIVED', 'ENDED')
        ORDER BY s.sessionStatusChangedAt DESC NULLS LAST,
                 s.id DESC
        """)
    List<TheaterState> findArchivedByUserId(@Param("userId") Long userId);
}