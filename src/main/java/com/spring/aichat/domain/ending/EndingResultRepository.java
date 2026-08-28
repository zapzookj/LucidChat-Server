package com.spring.aichat.domain.ending;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** [블록 D · 극장 엔딩 부활] 생성된 엔딩 결과 조회/저장. */
public interface EndingResultRepository extends MongoRepository<EndingResultDocument, String> {

    /**
     * [D-10 · docs/19_assets/blockd_regressions.md — "ending_results 유니크 인덱스가 prod에서
     * 생성되지 않음"] <b>Optional 반환 금지.</b>
     *
     * <p>prod는 {@code application-prod.yml:20 spring.data.mongodb.auto-index-creation: false}라
     * {@code @Indexed(unique = true)}가 <b>생성되지 않는다</b>(로컬만 true). 유니크 제약이 없는
     * 상태에서 중복 문서가 하나라도 생기면 {@code Optional<EndingResultDocument> findByRoomId}는
     * {@code IncorrectResultSizeDataAccessException}을 던져 GET/POST 양쪽 엔딩 조회가
     * <b>영구 500</b>이 된다. 그래서 List로 받아 서비스에서 최신 1건을 고르고 잉여를 정리한다.
     *
     * <p>운영 런북 — prod Mongo에 인덱스를 수동 생성할 것(필드명이 {@code @Field("room_id")}이므로
     * 인덱스 키는 {@code roomId}가 아니라 {@code room_id}다):
     * <pre>db.ending_results.createIndex({room_id:1},{unique:true})</pre>
     * 단, 이미 중복 문서가 있으면 인덱스 생성이 실패하므로 아래로 중복을 먼저 확인·제거한다:
     * <pre>db.ending_results.aggregate([{$group:{_id:"$room_id",n:{$sum:1}}},{$match:{n:{$gt:1}}}])</pre>
     */
    List<EndingResultDocument> findAllByRoomIdOrderByGeneratedAtDesc(Long roomId);
}
