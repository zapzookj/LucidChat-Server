package com.spring.aichat.dto.theater;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * [Phase 5.5-Theater] Theater 전용 SSE 이벤트 페이로드
 *
 * 기존 ChatStreamService의 SSE 이벤트 네이밍 컨벤션을 따른다:
 *   event: batch_meta       — 배치 메타 (batchId, Act/Chapter 정보)
 *   event: first_scene      — 첫 Scene 완성 (기존과 동일, 렌더링 즉시 시작)
 *   event: scene            — 추가 Scene 도착 (배치 내 2번째 이후)
 *   event: batch_complete   — 배치 전체 완료 + 프리페치 힌트
 *   event: prefetch_ready   — 백그라운드 배치 prefetch 완료 알림
 *   event: chapter_end      — Chapter 종료 + 리포트 데이터
 *   event: intermission     — 인터미션 진입
 *   event: branch_ready     — 분기 발생
 *   event: error            — 오류
 */
public final class TheaterStreamEvents {

    private TheaterStreamEvents() {}

    /** SSE 이벤트 이름 상수 */
    public static final String EVENT_BATCH_META      = "batch_meta";
    public static final String EVENT_FIRST_SCENE     = "first_scene";
    public static final String EVENT_SCENE           = "scene";
    public static final String EVENT_BATCH_COMPLETE  = "batch_complete";
    public static final String EVENT_PREFETCH_READY  = "prefetch_ready";
    public static final String EVENT_CHAPTER_END     = "chapter_end";
    public static final String EVENT_INTERMISSION    = "intermission";
    public static final String EVENT_BRANCH_READY    = "branch_ready";
    public static final String EVENT_ERROR           = "error";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Event Payloads
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchMeta(
        int batchId,
        int actNumber,
        int chapterNumber,
        int targetSceneCount,
        Long speakerHeroineId,
        String speakerHeroineName,
        String sceneLocation,
        String sceneTime,
        String sceneBgm
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScenePayload(
        int sequenceInBatch,
        Long speakerHeroineId,
        String speakerName,
        String narration,
        String innerNarration,
        String dialogue,
        String emotion,
        String location,
        String time,
        String outfit,
        String bgmMode,
        String illustrationUrl,
        String statReflectionHint
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchComplete(
        int batchId,
        int totalScenesInBatch,
        /** 이 배치 이후 chapter 종료 여부 */
        boolean chapterEndAfter,
        /** 다음 배치 prefetch 권장 여부 */
        boolean recommendPrefetch,
        /** 이 배치에서 변경된 히로인 호감도 */
        Map<Long, Integer> heroineAffectionDeltas,
        /** 이 배치에서 발동된 아바타 스탯 노출량 (UI 힌트) */
        Map<String, Integer> avatarStatReflection
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PrefetchReady(
        int batchId,
        int sceneCount
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChapterEndPayload(
        int actNumber,
        int chapterNumber,
        String chapterTitle,
        TheaterResponses.ChapterReport report,
        /** 인터미션 진입 여부 */
        boolean leadsToIntermission,
        /** 다음 Act로 전환되는지 */
        boolean transitioningToNewAct
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IntermissionPayload(
        int actNumber,
        int maxStamina,
        List<TheaterResponses.IntermissionActivity> activities
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BranchReadyPayload(
        String branchLevel,
        String branchToken,
        TheaterResponses.BranchOptions options
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorPayload(
        String errorCode,
        String message
    ) {}
}