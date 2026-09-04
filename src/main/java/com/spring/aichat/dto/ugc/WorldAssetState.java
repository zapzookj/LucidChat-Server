package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * [UGC 세계관 빌더] 일러 컷(썸네일/장소 배경) 개별 상태 —
 * {@code UgcWorldCreationJob.illustrationAssetsJson}의 값 타입.
 * {@link EmotionAssetState}의 리롤 누적 패턴 축소판 (파생/누끼 중간 단계 없음).
 *
 * <p>상태 흐름: GENERATING(fal flux-2) → READY. 재시도 소진 시:
 * 이전 버전이 있으면 READY 복귀, 없으면 FAILED(무료 재시도 대상).
 *
 * @param key     현재 선택본 서비스 S3 키
 * @param history 완성본 버전 누적 리스트 (무료 골라잡기 후보)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorldAssetState(String status, String key, int retryCount, List<String> history, boolean freeRetry) {

    public static final String GENERATING = "GENERATING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";

    public WorldAssetState {
        if (history == null) {
            history = key != null ? List.of(key) : List.of();
        }
    }

    public static WorldAssetState generating(int retryCount) {
        return new WorldAssetState(GENERATING, null, retryCount, List.of(), false);
    }

    /** 리롤/자동 재시도 재진입 — 기존 선택본·히스토리를 유지한 채 생성 상태로. */
    public WorldAssetState generatingAgain(int newRetryCount) {
        return new WorldAssetState(GENERATING, key, newRetryCount, history, false);
    }

    /** 새 완성본 — 히스토리에 누적하고 최신본을 선택본으로. */
    public WorldAssetState readyWith(String newKey) {
        List<String> next = new ArrayList<>(history);
        if (!next.contains(newKey)) {
            next.add(newKey);
        }
        return new WorldAssetState(READY, newKey, retryCount, List.copyOf(next), false);
    }

    /** 버전 골라잡기 (무과금). */
    public WorldAssetState selectVersion(int index) {
        if (index < 0 || index >= history.size()) {
            throw new IllegalArgumentException("잘못된 버전 인덱스: " + index);
        }
        return new WorldAssetState(READY, history.get(index), retryCount, history, freeRetry);
    }

    /**
     * 재시도 소진 시 이전 완성본으로 복귀 (리롤 실패가 기존 결과를 파괴하지 않도록).
     * [D-2.m] 다음 1회 무료 자격 부여 — 근거·대안 검토는 {@link EmotionAssetState#revertToReady()} 참조.
     * 캐릭터 트랙과 <b>복붙 계보</b>라 한쪽만 고치면 반드시 다른 쪽이 잊힌다(docs/13에서 실제로 그랬다).
     */
    public WorldAssetState revertToReady() {
        return new WorldAssetState(READY, key, retryCount, history, true);
    }

    public WorldAssetState failed() {
        return new WorldAssetState(FAILED, key, retryCount, history, freeRetry);
    }

    /** 이전 완성본 보유 여부 — 재시도 소진 시 FAILED 대신 복귀 판단용. */
    public boolean hasCompletedVersion() {
        return key != null && !history.isEmpty();
    }

    public boolean is(String s) {
        return s.equals(status);
    }

    public boolean isSettled() {
        return is(READY) || is(FAILED);
    }

    /**
     * [D-2.m] 이 리롤을 무과금으로 수용해야 하는가 — {@link EmotionAssetState#isFreeReroll()}과 동일 계약.
     * 과금 판정은 이 술어 하나로 통일한다.
     */
    public boolean isFreeReroll() {
        return is(FAILED) || freeRetry;
    }
}
