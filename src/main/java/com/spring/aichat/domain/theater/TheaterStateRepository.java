package com.spring.aichat.domain.theater;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TheaterStateRepository extends JpaRepository<TheaterState, Long> {

    Optional<TheaterState> findByRoom_Id(Long roomId);

    /**
     * [D-10 · docs/19_assets/blockd_regressions.md — "triggerEnding에 잠금·멱등성 없음"]
     * 엔딩 발동 전용 <b>비관적 쓰기 잠금</b> 조회.
     *
     * <p>기존 {@link #findByRoom_Id}는 락이 없어서 {@code triggerEnding}이
     * {@code isEndingReached()} 검사 → LLM 20~60초 → {@code markEnded()} 사이에
     * 완전히 열린 TOCTOU 창을 남겼다. 블록 D에서 FE가 'GET 우선 → 404면 POST'로 바뀌며
     * 새로고침 1회만으로 POST 두 개가 동시에 통과하고, LLM 과금 2회 + ending_results
     * 중복 문서(= prod에 유니크 인덱스가 없으므로 이후 조회가 영구 500)로 이어졌다.
     *
     * <p>두 번째 요청은 첫 요청의 트랜잭션이 커밋될 때까지 이 행에서 대기했다가
     * {@code endingReached=true}가 된 상태를 읽고 저장된 결과를 돌려주게 된다.
     * (대기 시간이 LLM 생성 시간만큼 길어질 수 있다 — 정상 동작이다.)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM TheaterState s WHERE s.room.id = :roomId")
    Optional<TheaterState> findByRoomIdForUpdate(@Param("roomId") Long roomId);

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