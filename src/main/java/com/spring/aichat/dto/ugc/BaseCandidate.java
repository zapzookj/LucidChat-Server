package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * [UGC 개편 2026-07-20] 베이스 스탠딩 후보 — {@code CharacterCreationJob.baseCandidatesJson}의 항목.
 *
 * @param key  WF-2 리파인 완료본 서비스 S3 키 (BASE_WAIT 표시·베이스 확정 대상)
 * @param seed 해당 후보의 Qwen 패스2 seed — 확정 시 감정 파생 seed로 고정
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BaseCandidate(String status, String key, Long seed, int retryCount) {

    public static final String DERIVING = "DERIVING";
    public static final String REFINING = "REFINING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";

    public static BaseCandidate deriving(int retryCount) {
        return new BaseCandidate(DERIVING, null, null, retryCount);
    }

    public BaseCandidate refining(String editKey, Long usedSeed) {
        return new BaseCandidate(REFINING, editKey, usedSeed, retryCount);
    }

    public BaseCandidate readyWith(String refinedKey) {
        return new BaseCandidate(READY, refinedKey, seed, retryCount);
    }

    public BaseCandidate failed() {
        return new BaseCandidate(FAILED, key, seed, retryCount);
    }

    public BaseCandidate withRetry(int newRetryCount) {
        return new BaseCandidate(status, key, seed, newRetryCount);
    }

    public boolean is(String s) {
        return s.equals(status);
    }
}
