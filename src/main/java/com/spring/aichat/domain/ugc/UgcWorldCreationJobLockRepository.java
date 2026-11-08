package com.spring.aichat.domain.ugc;

import java.util.Optional;

/** 월드 잡 비관적 락 조회 — 항상 DB 최신 상태로 재적재한다({@link FreshLock}). 반드시 TX 안에서 호출한다. */
public interface UgcWorldCreationJobLockRepository {
    Optional<UgcWorldCreationJob> findByIdForUpdate(Long id);
}
