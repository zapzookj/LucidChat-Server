package com.spring.aichat.dto.theater;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * [Phase 5.5-Theater] LLM이 반환하는 Scene Batch JSON 스키마
 *
 * 기존 Dialogue 모드의 AiJsonOutput과 구분되는 Theater 전용 응답 포맷.
 *
 * Theater의 씬은 3인칭 나레이션 + 속내(주인공/히로인) + dialogue로 구성된다.
 * 한 배치는 5~8개의 Scene을 포함.
 *
 * ─────────────────────────────────────────────────────────────────────
 *  [Phase 5.5 UX Polish · R1] 스키마 진화
 * ─────────────────────────────────────────────────────────────────────
 *
 *  기존: `inner_narration` 단일 필드 — 화자 혼동 빈번 (히로인 속마음이
 *        주인공 속마음 자리에 나오는 빈도 0이 아님)
 *
 *  신규:
 *    - `protagonist_inner`: 주인공(아바타)의 1인칭 속내. UI에 표시.
 *    - `heroine_inner`:    이번 씬의 화자 히로인의 속내. UI 미노출 (백엔드 자산).
 *                          AUTO_MOMENT 트리거 / 메모 시너지 / 향후 디렉터스 컷
 *                          등 미래 기능에서 활용 가능.
 *    - `scene_type`:       narration / heroine_speaks / avatar_speaks /
 *                          dialogue_exchange — 배치 구성 균형 검증용.
 *
 *  하위 호환:
 *    - 구버전 `inner_narration` 필드도 그대로 받는다 (LLM이 가끔 옛 키로 응답).
 *    - 백엔드는 protagonist_inner가 비었고 inner_narration이 차있으면
 *      후자를 전자로 매핑 (TheaterBatchGenerator에서 처리).
 *
 * 예시 응답:
 * {
 *   "batch_meta": {
 *     "chapter_title": "...",
 *     "chapter_target_scenes": 30,
 *     "scene_count_in_batch": 6,
 *     "speaker_heroine_slug": "taeri",
 *     "chapter_end_after": false
 *   },
 *   "scenes": [
 *     {
 *       "narration": "...",
 *       "protagonist_inner": "...",
 *       "heroine_inner": "...",
 *       "dialogue": "...",
 *       "speaker": "AVATAR",
 *       "scene_type": "dialogue_exchange",
 *       ...
 *     }
 *   ],
 *   "heroine_affection_deltas": { "taeri": 3, "luna": -1 },
 *   "rolling_summary": "이 배치의 핵심 전개를 1~2문장으로 요약.",
 *   "branch_signal": { "level": "MAJOR", "context": "..." }  // optional
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmSceneBatchOutput(
    @JsonProperty("batch_meta") BatchMeta batchMeta,
    @JsonProperty("scenes") List<LlmScene> scenes,
    @JsonProperty("heroine_affection_deltas") Map<String, Integer> heroineAffectionDeltas,
    @JsonProperty("rolling_summary") String rollingSummary,
    @JsonProperty("branch_signal") BranchSignal branchSignal,
    /**
     * [Phase 5.5 UX Polish · R3] 사용된 감독 명령어 ID 회수.
     * LLM이 활성 명령어를 반영했다면 그 ID들을 여기에 적어 응답.
     * 백엔드는 이 목록의 명령어를 wasUsed=true 마킹.
     * (LLM이 비워서 보내도 무방 — 백엔드가 단일 명령어 큐에서 직접 추적)
     */
    @JsonProperty("used_director_commands") List<Long> usedDirectorCommands
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchMeta(
        @JsonProperty("chapter_title") String chapterTitle,
        @JsonProperty("chapter_target_scenes") Integer chapterTargetScenes,
        @JsonProperty("scene_count_in_batch") Integer sceneCountInBatch,
        @JsonProperty("speaker_heroine_slug") String speakerHeroineSlug,
        @JsonProperty("chapter_end_after") Boolean chapterEndAfter
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LlmScene(
        @JsonProperty("narration") String narration,

        /**
         * [R1] 주인공(아바타)의 1인칭 속내. UI 표시.
         * LLM이 신규 키로 응답할 때 사용.
         */
        @JsonProperty("protagonist_inner") String protagonistInner,

        /**
         * [R1] 화자 히로인의 속내. UI 미노출 — 백엔드 자산.
         * heroine speaks/dialogue_exchange 씬에서만 채워진다.
         */
        @JsonProperty("heroine_inner") String heroineInner,

        /**
         * [Legacy / Fallback] 구버전 inner_narration.
         * LLM이 옛 키로 보낼 때를 대비. TheaterBatchGenerator가
         * protagonist_inner가 비었고 이 필드가 차있으면 매핑.
         */
        @JsonProperty("inner_narration") String innerNarration,

        @JsonProperty("dialogue") String dialogue,
        @JsonProperty("speaker") String speaker,       // 히로인 슬러그 or "AVATAR" or "" (나레이션 only)

        /**
         * [R1] 씬 타입 — 배치 구성 균형 검증용.
         *  - narration:         나레이션만 (대사 없음)
         *  - heroine_speaks:    히로인 단독 발화
         *  - avatar_speaks:     아바타 단독 발화
         *  - dialogue_exchange: 양방향 티키타카 (배치당 최소 1)
         */
        @JsonProperty("scene_type") String sceneType,

        @JsonProperty("emotion") String emotion,
        @JsonProperty("location") String location,
        @JsonProperty("time") String time,
        @JsonProperty("outfit") String outfit,
        @JsonProperty("bgm_mode") String bgmMode,
        @JsonProperty("stat_reflection_hint") String statReflectionHint
    ) {
        /**
         * 하위 호환 헬퍼 — protagonist_inner가 비었고 inner_narration이
         * 차있으면 후자를 반환 (legacy → new 매핑).
         */
        public String resolvedProtagonistInner() {
            if (protagonistInner != null && !protagonistInner.isBlank()) return protagonistInner;
            if (innerNarration != null && !innerNarration.isBlank()) return innerNarration;
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BranchSignal(
        @JsonProperty("level") String level,           // MINOR / MAJOR / CLIMAX
        @JsonProperty("context") String context
    ) {}
}