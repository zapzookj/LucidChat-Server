package com.spring.aichat.service.ugc;

import com.spring.aichat.dto.ugc.EmotionAssetState;
import com.spring.aichat.external.UgcComfyClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [D-3 · docs/17_assets/defect_register.md §D-3] UGC 좀비 잡 회수·이중 과금 가드의 순수 판정 계약.
 *
 * <p>스케줄러·워커는 외부 I/O가 두꺼워 여기서는 판정 함수만 고정한다 —
 * D-3.2a(404 = 영구 실패 vs 일시 오류) · D-3.4(리롤 수용 상태 화이트리스트) · D-3.5(in-flight 선점).
 */
class UgcZombieJobGuardsTest {

    // ━━━━━━━━━━ D-3.2a: 폴러가 영구/일시 실패를 구분한다 ━━━━━━━━━━

    @Test
    @DisplayName("NOT_FOUND는 영구 실패, ERROR는 일시 오류 — 서로 다른 술어이고 completed/failed/inFlight 어디에도 안 걸린다")
    void syntheticStatusesAreDistinct() {
        var notFound = new UgcComfyClient.JobStatus("j", UgcComfyClient.JobStatus.NOT_FOUND, java.util.List.of(), "404", null, null);
        var transientErr = new UgcComfyClient.JobStatus("j", UgcComfyClient.JobStatus.TRANSIENT_ERROR, java.util.List.of(), "timeout", null, null);

        assertThat(notFound.notFound()).isTrue();
        assertThat(notFound.transientError()).isFalse();
        assertThat(transientErr.transientError()).isTrue();
        assertThat(transientErr.notFound()).isFalse();
        for (var s : new UgcComfyClient.JobStatus[]{notFound, transientErr}) {
            assertThat(s.completed()).isFalse();
            assertThat(s.failed()).isFalse();
            assertThat(s.inFlight()).isFalse();
        }
    }

    @Test
    @DisplayName("lost()는 파이프라인 정상 실패 경로(failed=true)로 주입되는 합성 FAILED다")
    void lostIsInjectedAsFailed() {
        var lost = UgcComfyClient.JobStatus.lost("dead-id", "외부 잡 결과 소실(404)");
        assertThat(lost.failed()).isTrue();
        assertThat(lost.completed()).isFalse();
        assertThat(lost.images()).isEmpty();
        assertThat(lost.error()).contains("404");
    }

    // ━━━━━━━━━━ D-3.4: 감정 리롤은 READY/FAILED에서만 ━━━━━━━━━━

    @Test
    @DisplayName("READY(유료)·FAILED(무료)만 리롤 수용 — DERIVING/REFINING/CUTTING/DONE은 거부")
    void rerollWhitelist() {
        assertThat(EmotionAssetState.ready("k").isRerollable()).isTrue();
        assertThat(EmotionAssetState.ready("k").failed().isRerollable()).isTrue();

        assertThat(EmotionAssetState.deriving(0).isRerollable()).isFalse();
        assertThat(EmotionAssetState.ready("k").derivingAgain(0).isRerollable()).isFalse();   // 리롤 진행 중 재클릭
        assertThat(EmotionAssetState.ready("k").refining().isRerollable()).isFalse();
        assertThat(EmotionAssetState.ready("k").cutting().isRerollable()).isFalse();
        assertThat(EmotionAssetState.ready("k").doneWith("cut").isRerollable()).isFalse();
    }

    // ━━━━━━━━━━ D-3.1b/d: 스테일 회수 — 외부 키 없는 미결 항목만 재제출 대상 ━━━━━━━━━━

    @Test
    @DisplayName("누끼 부분 제출 유실: DONE·키 있는 컷은 제외, 미제출(키 없음) 컷만 재제출 대상")
    void pendingCutoutTags_partialSubmission() {
        var emotions = new java.util.EnumMap<com.spring.aichat.domain.enums.EmotionTag, EmotionAssetState>(
            com.spring.aichat.domain.enums.EmotionTag.class);
        var T = com.spring.aichat.domain.enums.EmotionTag.class.getEnumConstants();
        emotions.put(T[0], EmotionAssetState.ready("k0").cutting().doneWith("cut0"));   // 완료 — 제외
        emotions.put(T[1], EmotionAssetState.ready("k1").cutting());                    // 제출됨(키 있음) — 폴러 담당
        emotions.put(T[2], EmotionAssetState.ready("k2").cutting());                    // 미제출 — 재제출
        emotions.put(T[3], EmotionAssetState.ready("k3"));                              // runCutoutStage 자체 유실 — 재제출
        emotions.put(T[4], EmotionAssetState.deriving(0));                              // 원본 없음 — 제출 불가, 제외
        var scratch = new java.util.HashMap<String, String>();
        scratch.put("CUTOUT:" + T[1].name(), "runpod-1");
        scratch.put("K_APPEARANCE_EDIT", "…");   // 내부 키는 무관

        assertThat(UgcPipelineWorker.pendingCutoutTags(emotions, scratch)).containsExactlyInAnyOrder(T[2], T[3]);
    }

    @Test
    @DisplayName("감정 파생 유실: DERIVING/REFINING인데 EMOTION_REFINE 키가 없는 것만 — READY/FAILED·키 있는 것 제외")
    void pendingEmotionTags_lostQwenFutures() {
        var emotions = new java.util.EnumMap<com.spring.aichat.domain.enums.EmotionTag, EmotionAssetState>(
            com.spring.aichat.domain.enums.EmotionTag.class);
        var T = com.spring.aichat.domain.enums.EmotionTag.class.getEnumConstants();
        emotions.put(T[0], EmotionAssetState.deriving(0));                   // Qwen future 유실 — 재파생
        emotions.put(T[1], EmotionAssetState.ready("k").derivingAgain(0));   // 리롤 future 유실 — 재파생
        emotions.put(T[2], EmotionAssetState.ready("k").refining());         // WF-2 제출됨(키 있음) — 폴러
        emotions.put(T[3], EmotionAssetState.ready("k"));                    // 완료 — 제외
        emotions.put(T[4], EmotionAssetState.deriving(0).failed());          // 소진 — 제외
        var scratch = java.util.Map.of("EMOTION_REFINE:" + T[2].name(), "runpod-2");

        assertThat(UgcPipelineWorker.pendingEmotionTags(emotions, scratch)).containsExactlyInAnyOrder(T[0], T[1]);
    }

    @Test
    @DisplayName("스탠딩 후보 유실: 미정착(READY/FAILED 아님)이면서 BASE_REFINE 키 없는 인덱스만")
    void pendingBaseIndices_lostCandidates() {
        var candidates = java.util.List.of(
            com.spring.aichat.dto.ugc.BaseCandidate.deriving(0),                        // 0: 유실 — 재파생
            com.spring.aichat.dto.ugc.BaseCandidate.deriving(0).refining("edit1", 7L),  // 1: WF-2 제출됨(키) — 폴러
            com.spring.aichat.dto.ugc.BaseCandidate.deriving(0).readyWith("base2"),     // 2: 완료
            com.spring.aichat.dto.ugc.BaseCandidate.deriving(0).failed());              // 3: 소진
        var scratch = java.util.Map.of("BASE_REFINE:1", "runpod-b1");

        assertThat(UgcPipelineWorker.pendingBaseIndices(candidates, scratch)).containsExactly(0);
    }

    @Test
    @DisplayName("externalJobs 키 해석: 스테이지·토큰 분리, 내부 K_ 키·규약 밖 키는 null")
    void parseExternalKey() {
        assertThat(UgcPipelineWorker.parseExternalKey("GOLDEN"))
            .isEqualTo(new UgcPipelineWorker.ExternalKey(UgcStage.GOLDEN, null));
        assertThat(UgcPipelineWorker.parseExternalKey("EMOTION_REFINE:JOY"))
            .isEqualTo(new UgcPipelineWorker.ExternalKey(UgcStage.EMOTION_REFINE, "JOY"));
        assertThat(UgcPipelineWorker.parseExternalKey("K_APPEARANCE_EDIT")).isNull();
        assertThat(UgcPipelineWorker.parseExternalKey("WHATEVER:1")).isNull();
        assertThat(UgcPipelineWorker.parseExternalKey(null)).isNull();
    }

    // ━━━━━━━━━━ D-3.5: 장소 배경 in-flight 선점 ━━━━━━━━━━

    @Test
    @DisplayName("tryAcquire는 원자적 선점 — 두 번째는 false, release 후 다시 true")
    void inFlightRegistryAcquireRelease() {
        var reg = new UgcLocationInFlightRegistry();
        assertThat(reg.tryAcquire(7L)).isTrue();
        assertThat(reg.tryAcquire(7L)).isFalse();     // 진행 중 재시도 → 거부 근거
        assertThat(reg.isInFlight(7L)).isTrue();
        assertThat(reg.tryAcquire(8L)).isTrue();      // 다른 장소는 독립

        reg.release(7L);
        assertThat(reg.isInFlight(7L)).isFalse();
        assertThat(reg.tryAcquire(7L)).isTrue();      // 유실·타임아웃 후 '멈춘 GENERATING' 재시도 허용
    }

    @Test
    @DisplayName("null id는 선점되지 않고 예외도 없다")
    void inFlightRegistryNullSafe() {
        var reg = new UgcLocationInFlightRegistry();
        assertThat(reg.tryAcquire(null)).isFalse();
        assertThat(reg.isInFlight(null)).isFalse();
        reg.release(null);
    }
}
