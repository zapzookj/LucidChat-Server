package com.spring.aichat.domain.theater;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * [Phase 5.5-Theater-Polish] TheaterSceneLog MongoDB Repository
 */
public interface TheaterSceneLogRepository extends MongoRepository<TheaterSceneLog, String> {

    /** 방의 특정 Chapter 모든 씬 조회 (대화 기록 패널) */
    List<TheaterSceneLog> findByRoomIdAndActNumberAndChapterNumberOrderBySceneSeqInChapterAsc(
        Long roomId, int actNumber, int chapterNumber);

    /** 방의 최근 씬 N개 (최근 기억 주입용) */
    List<TheaterSceneLog> findTop30ByRoomIdOrderByGlobalSceneSeqDesc(Long roomId);

    /** 방의 특정 배치 씬들 */
    List<TheaterSceneLog> findByRoomIdAndBatchIdOrderBySceneIndexInBatchAsc(Long roomId, int batchId);

    /** 방의 모든 씬 페이지네이션 (대화 기록 탐색) */
    Page<TheaterSceneLog> findByRoomIdOrderByGlobalSceneSeqAsc(Long roomId, Pageable pageable);

    /** 특정 씬 순번으로 조회 (이전 버튼용) */
    List<TheaterSceneLog> findByRoomIdAndGlobalSceneSeqBetweenOrderByGlobalSceneSeqAsc(
        Long roomId, long startSeq, long endSeq);

    /** 방 전체 씬 수 */
    long countByRoomId(Long roomId);

    /** 방 삭제 시 정리용 */
    void deleteByRoomId(Long roomId);

    /**
     * [D-5.4] 같은 좌표의 기존 씬 로그 제거 — <b>재생성을 append가 아니라 overwrite로 만든다.</b>
     *
     * <p>이 컬렉션의 복합 인덱스 3개에 unique가 없어서, 같은 (act, chapter, batchId)가 다시
     * 생성되면 같은 자리에 행이 하나 더 쌓였다. 배치 캐시 TTL(6시간)이 만료된 뒤 유저가
     * 이어서 진행하면 그 배치가 재생성되는데, state는 안 움직였으므로 seq까지 동일하다.
     */
    void deleteByRoomIdAndActNumberAndChapterNumberAndBatchId(
        Long roomId, int actNumber, int chapterNumber, int batchId);

    /**
     * [D-5.4] 최근 기억 주입용 — <b>되돌린 지점 이후(유령 장면)를 제외</b>하고 읽는다.
     *
     * <p>세이브 로드는 state를 과거로 되돌리지만 씬 로그는 그대로 남는다. 그 '일어나지 않은
     * 미래' 장면이 프롬프트에 주입되면 <b>캐릭터가 아직 없던 일을 기억한다</b> — 화면과 달리
     * 눈에 안 보여서 진단이 어렵다.
     *
     * <p>seq 경계로 거른다: {@code globalSceneSeq = totalSceneCount + idx}(0-base)로 매겨지므로,
     * 현재 {@code totalSceneCount} <b>미만</b>이 '실제로 일어난 것'이다.
     *
     * <p>⚠ <b>삭제가 아니라 필터인 이유</b> — 한때 여기서 하드 삭제를 했다가 되돌렸다.
     * 삭제는 방 단위라 <b>슬롯을 오가는 유저의 진짜 과거를 지운다</b>: 슬롯1(씬 50)을 로드하면
     * 50~99가 사라지고, 그 뒤 슬롯2(씬 80)를 로드하면 50~79가 없어 캐릭터가
     * <b>실제로 있었던 일을 잊는다</b>. 고치려던 것의 정확한 거울상이다.
     */
    List<TheaterSceneLog> findTop30ByRoomIdAndGlobalSceneSeqLessThanOrderByGlobalSceneSeqDesc(
        Long roomId, long globalSceneSeqExclusive);
}