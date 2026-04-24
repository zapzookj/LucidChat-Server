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
}