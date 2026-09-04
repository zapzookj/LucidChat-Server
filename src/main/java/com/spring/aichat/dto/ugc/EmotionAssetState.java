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
 * @param freeRetry [D-2.l] 다음 리롤 1회 무료 자격. 유료 리롤이 재시도를 다 쓰고 실패해
 *                  {@link #revertToReady()}로 되돌아갔을 때 부여된다 — 유저는 2E를 냈는데
 *                  아무것도 못 받았으므로 그 값어치를 돌려준다. 구 JSON에는 이 필드가 없고
 *                  Jackson이 원시 boolean에 false를 넣으므로 하위호환은 자동이다(= 자격 없음).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmotionAssetState(String status, String key, String cutoutKey, int retryCount, List<String> history,
                                boolean freeRetry) {

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
        return new EmotionAssetState(DERIVING, null, null, retryCount, List.of(), false);
    }

    public static EmotionAssetState ready(String key) {
        return new EmotionAssetState(READY, key, null, 0, List.of(key), false);
    }

    /** 리롤/자동 재시도 재진입 — 기존 선택본·히스토리를 유지한 채 파생 상태로. */
    public EmotionAssetState derivingAgain(int newRetryCount) {
        return new EmotionAssetState(DERIVING, key, null, newRetryCount, history, false);
    }

    public EmotionAssetState refining() {
        return new EmotionAssetState(REFINING, key, cutoutKey, retryCount, history, freeRetry);
    }

    /** 새 완성본 — 히스토리에 누적하고 최신본을 선택본으로. */
    public EmotionAssetState readyWith(String refinedKey) {
        List<String> next = new ArrayList<>(history);
        if (!next.contains(refinedKey)) {
            next.add(refinedKey);
        }
        return new EmotionAssetState(READY, refinedKey, null, retryCount, List.copyOf(next), false);
    }

    /** 버전 골라잡기 (검수 그리드 — 무과금). */
    public EmotionAssetState selectVersion(int index) {
        if (index < 0 || index >= history.size()) {
            throw new IllegalArgumentException("잘못된 버전 인덱스: " + index);
        }
        return new EmotionAssetState(READY, history.get(index), null, retryCount, history, freeRetry);
    }

    /**
     * 재시도 소진 시 이전 완성본으로 복귀 (리롤 실패가 기존 결과를 파괴하지 않도록).
     *
     * <p>[D-2.l] 여기서 <b>다음 1회 무료 자격</b>을 준다. 종전엔 이 분기가 유일하게 보상이 없었다 —
     * 완성본이 없는 컷은 {@link #failed()}로 가서 다음 리롤이 {@code is(FAILED)} 판정으로 무료가 되는데,
     * 완성본이 있는 컷(= 돈 내고 리롤한 바로 그 케이스)만 READY로 돌아가 다음 리롤이 또 2E였다.
     * 유저는 2E를 내고 아무것도 못 받은 채 재시도하려면 또 2E를 내야 했다.
     *
     * <p>에너지 환불이 아니라 무료 자격인 이유: ① FAILED 분기와 정책이 대칭이 된다
     * ② 에너지 원장을 건드리지 않아 {@code job.energyCharged} 정합·{@code failAndRefund}와의
     * 이중 환불·프로필 캐시 무효화 세 가지 위험이 통째로 소멸한다 ③ 유저가 원한 것은 에너지가 아니라
     * 컷이므로 '한 번 더 무료'가 체감상 오히려 낫다.
     */
    public EmotionAssetState revertToReady() {
        return new EmotionAssetState(READY, key, null, retryCount, history, true);
    }

    public EmotionAssetState cutting() {
        return new EmotionAssetState(CUTTING, key, cutoutKey, retryCount, history, freeRetry);
    }

    public EmotionAssetState doneWith(String cutKey) {
        return new EmotionAssetState(DONE, key, cutKey, retryCount, history, freeRetry);
    }

    public EmotionAssetState failed() {
        return new EmotionAssetState(FAILED, key, cutoutKey, retryCount, history, freeRetry);
    }

    public EmotionAssetState withRetry(int newRetryCount) {
        return new EmotionAssetState(status, key, cutoutKey, newRetryCount, history, freeRetry);
    }

    /** 이전 완성본 보유 여부 — 재시도 소진 시 FAILED 대신 복귀 판단용. */
    public boolean hasCompletedVersion() {
        return key != null && !history.isEmpty();
    }

    /**
     * [D-3.4] 유저 리롤 수용 가능 상태 — READY(유료 리롤)·FAILED(무료 재시도)만. DERIVING/REFINING(진행 중)은
     * 거부한다: 진행 중 재리롤은 2E 이중 과금 + fal/WF-2 중복 제출 + 동일 스크래치 키 덮어쓰기로 선발 체인을
     * 유실시킨다(월드 rerollAsset의 GENERATING 가드와 동형 — 캐릭터 트랙만 빠져 있었다). CUTTING/DONE은
     * REVIEW_WAIT에서 나타나지 않지만 화이트리스트라 함께 거부된다.
     */
    public boolean isRerollable() {
        return is(READY) || is(FAILED);
    }

    /**
     * [D-2.l] 이 리롤을 무과금으로 수용해야 하는가 — 서버 귀책으로 앞선 시도가 아무것도 남기지 못한 경우.
     * {@code FAILED}(완성본 없이 소진)와 {@code freeRetry}(완성본 있는 유료 리롤이 소진)를 함께 덮는다.
     * <b>과금 판정은 이 술어 하나로 통일한다</b> — 서비스마다 술어를 따로 심으면 다시 갈린다.
     */
    public boolean isFreeReroll() {
        return is(FAILED) || freeRetry;
    }

    public boolean is(String s) {
        return s.equals(status);
    }
}
