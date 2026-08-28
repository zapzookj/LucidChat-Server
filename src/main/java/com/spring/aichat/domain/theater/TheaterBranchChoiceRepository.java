package com.spring.aichat.domain.theater;

import com.spring.aichat.domain.enums.BranchLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TheaterBranchChoiceRepository extends JpaRepository<TheaterBranchChoice, Long> {

    List<TheaterBranchChoice> findByRoom_IdOrderByChosenAtAsc(Long roomId);

    List<TheaterBranchChoice> findByRoom_IdAndActNumberOrderByChosenAtAsc(Long roomId, int actNumber);

    /**
     * [Polish · LOCATION fix] 특정 방의 특정 (Act, Chapter)에 특정 분기 레벨 선택 기록이 있는지.
     *
     * 사용처:
     *  - TheaterLobbyService.buildRoomInfo: requiresLocationChoice 결정
     *  - TheaterService.requestNextBatch: LOCATION choice 가드 통과 여부
     *
     * 인덱스 idx_theater_branch_act(room_id, act_number, chapter_number)가 존재해서
     * lookup 비용은 무시할 만함.
     */
    boolean existsByRoom_IdAndActNumberAndChapterNumberAndBranchLevel(
        Long roomId, int actNumber, int chapterNumber, BranchLevel branchLevel
    );

    /**
     * [적대적 리뷰 P1-2 · 확정 이력을 소비 게이트로] 특정 (Act, Chapter)에서 <b>특정 배치가 실은
     * 분기</b>가 이미 확정됐는지.
     *
     * <p>왜 branchLevel이 아니라 sourceBatchId인가 — MINOR는 한 Chapter에 정상적으로 3~4회
     * 발생하므로 레벨 기준으로는 "이미 확정함"을 판정할 수 없다. 분기 1회를 유일하게 식별하는
     * 축은 그 분기를 실은 배치다.
     *
     * <p>사용처:
     * <ul>
     *   <li>{@code TheaterBranchService.applyBranchChoice} — 재확정 거부(무한 재발급 루프 차단)</li>
     *   <li>{@code TheaterBranchService.resolveServerBranchSignal} backstop ③ — 이미 확정된
     *       배치를 다시 집어 오퍼를 재발급하지 않도록 좁힌다</li>
     *   <li>{@code TheaterProgressGateService} — 미확정 분기 가드</li>
     * </ul>
     *
     * 인덱스 idx_theater_branch_act(room_id, act_number, chapter_number)가 prefix로 커버한다.
     */
    boolean existsByRoom_IdAndActNumberAndChapterNumberAndSourceBatchId(
        Long roomId, int actNumber, int chapterNumber, Integer sourceBatchId
    );

    void deleteByRoom_Id(Long roomId);

    /**
     * [적대적 리뷰 P2 — 세이브 로드 후 분기 소멸] 되돌린 지점 <b>이후</b>의 확정 기록을 폐기한다.
     *
     * <p>{@code load}는 TheaterState의 act/chapter/batchId를 스냅샷 시점으로 되돌리고 캐시만 비운다.
     * 확정 기록이 남아 있으면 재진행 시 {@code alreadyResolved} 게이트가 그 챕터의 씬 분기를
     * <b>전부 소멸</b>시킨다 — 유저는 같은 챕터를 다시 플레이하는데 분기가 하나도 안 나온다.
     * 되돌린 시점 이후는 '일어나지 않은 일'이므로 기록도 함께 되돌리는 것이 맞다.
     */
    @Modifying
    @Query("DELETE FROM TheaterBranchChoice c WHERE c.room.id = :roomId "
        + "AND (c.actNumber > :act OR (c.actNumber = :act AND c.chapterNumber >= :chapter))")
    int deleteFromPosition(@Param("roomId") Long roomId,
                           @Param("act") int actNumber,
                           @Param("chapter") int chapterNumber);
}