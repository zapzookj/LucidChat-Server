package com.spring.aichat.domain.ugc;

import java.util.Optional;

/** 잡 단위 직렬화용 비관적 락 조회 — 항상 DB 최신 상태로 재적재한다({@link FreshLock}). */
public interface CharacterCreationJobLockRepository {
    /**
     * 감정·누끼 webhook/폴링/제출 루프 경합의 lost update 방지용. 반드시 TX 안에서 호출한다.
     * 이미 영속성 컨텍스트에 있는 잡도 락과 함께 다시 읽으므로, 호출 전에 읽어 둔 값은 믿지 않는다.
     */
    Optional<CharacterCreationJob> findByIdForUpdate(Long id);
}
