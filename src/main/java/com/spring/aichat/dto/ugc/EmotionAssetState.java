package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * [UGC v1] 감정 컷 개별 상태 — {@code CharacterCreationJob.emotionAssetsJson}의 값 타입.
 *
 * <p>상태 흐름: DERIVING(Qwen 편집) → REFINING(WF-2) → READY(검수 그리드 표시 가능)
 * → CUTTING(WF-3) → DONE. 재시도 소진 시: 이전 버전이 있으면 READY로 복귀, 없으면 FAILED.
 * NEUTRAL은 베이스 자체이므로 EMOTIONS 진입 시 즉시 READY.
 *
 * <p>[2026-07-20 리롤 누적] {@code history} = 지금까지 READY로 완성된 모든 버전의 키(누적).
 * {@code key}는 그중 현재 선택본. 리롤/재시도 중에도 key·history를 유지해 이전 버전 표시·선택이 가능하다.
 *
 * @param key       현재 선택본(WF-2 완성) 서비스 S3 키 — 검수 그리드 표시·누끼 대상
 * @param cutoutKey 누끼 완료본(WF-3) 서비스 S3 키 — 확정 승격 원본
 * @param history   완성본 버전 누적 리스트 (선택 후보)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmotionAssetState(String status, String key, String cutoutKey, int retryCount, List<String> history) {

    public static final String DERIVING = "DERIVING";
    public static final String REFINING = "REFINING";
    public static final String READY = "READY";
    public static final String CUTTING = "CUTTING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    public EmotionAssetState {
        // 하위 호환: 리롤 누적 도입 이전 JSON(history 없음) → 현 key를 유일 버전으로 승격
        if (history == null) {
            history = key != null ? List.of(key) : List.of();
        }
    }

    public static EmotionAssetState deriving(int retryCount) {
        return new EmotionAssetState(DERIVING, null, null, retryCount, List.of());
    }

    public static EmotionAssetState ready(String key) {
        return new EmotionAssetState(READY, key, null, 0, List.of(key));
    }

    /** 리롤/자동 재시도 재진입 — 기존 선택본·히스토리를 유지한 채 파생 상태로. */
    public EmotionAssetState derivingAgain(int newRetryCount) {
        return new EmotionAssetState(DERIVING, key, null, newRetryCount, history);
    }

    public EmotionAssetState refining() {
        return new EmotionAssetState(REFINING, key, cutoutKey, retryCount, history);
    }

    /** 새 완성본 — 히스토리에 누적하고 최신본을 선택본으로. */
    public EmotionAssetState readyWith(String refinedKey) {
        List<String> next = new ArrayList<>(history);
        if (!next.contains(refinedKey)) {
            next.add(refinedKey);
        }
        return new EmotionAssetState(READY, refinedKey, null, retryCount, List.copyOf(next));
    }

    /** 버전 골라잡기 (검수 그리드 — 무과금). */
    public EmotionAssetState selectVersion(int index) {
        if (index < 0 || index >= history.size()) {
            throw new IllegalArgumentException("잘못된 버전 인덱스: " + index);
        }
        return new EmotionAssetState(READY, history.get(index), null, retryCount, history);
    }

    /** 재시도 소진 시 이전 완성본으로 복귀 (리롤 실패가 기존 결과를 파괴하지 않도록). */
    public EmotionAssetState revertToReady() {
        return new EmotionAssetState(READY, key, null, retryCount, history);
    }

    public EmotionAssetState cutting() {
        return new EmotionAssetState(CUTTING, key, cutoutKey, retryCount, history);
    }

    public EmotionAssetState doneWith(String cutKey) {
        return new EmotionAssetState(DONE, key, cutKey, retryCount, history);
    }

    public EmotionAssetState failed() {
        return new EmotionAssetState(FAILED, key, cutoutKey, retryCount, history);
    }

    public EmotionAssetState withRetry(int newRetryCount) {
        return new EmotionAssetState(status, key, cutoutKey, newRetryCount, history);
    }

    /** 이전 완성본 보유 여부 — 재시도 소진 시 FAILED 대신 복귀 판단용. */
    public boolean hasCompletedVersion() {
        return key != null && !history.isEmpty();
    }

    public boolean is(String s) {
        return s.equals(status);
    }
}
