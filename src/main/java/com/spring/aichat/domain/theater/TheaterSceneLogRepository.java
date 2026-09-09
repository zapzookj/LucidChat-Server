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
     * [D-5.4] 되돌린 지점 이후의 씬 로그 폐기 — 세이브 로드 전용.
     *
     * <p>분기 확정 기록은 이미 되돌리고 있었는데(branchChoiceRepository.deleteFromPosition)
     * <b>씬 로그만 남겼다.</b> 그래서 로드 후 대화 기록에 '일어나지 않은 장면'이 그대로 보이고,
     * 최근 기억 주입(findTop30…OrderByGlobalSceneSeqDesc)이 그 미래 장면을 프롬프트에 넣었다.
     */
    void deleteByRoomIdAndGlobalSceneSeqGreaterThanEqual(Long roomId, long globalSceneSeq);
}