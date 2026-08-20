package com.spring.aichat.domain.ending;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** [블록 D · 극장 엔딩 부활] 생성된 엔딩 결과 조회/저장. */
public interface EndingResultRepository extends MongoRepository<EndingResultDocument, String> {

    Optional<EndingResultDocument> findByRoomId(Long roomId);
}
