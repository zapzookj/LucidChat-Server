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
 * Theater의 씬은 3인칭 나레이션 + inner_narration + dialogue로 구성된다.
 * 한 배치는 5~8개의 Scene을 포함.
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
 *   "scenes": [ ... ],
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
    @JsonProperty("branch_signal") BranchSignal branchSignal
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
        @JsonProperty("inner_narration") String innerNarration,
        @JsonProperty("dialogue") String dialogue,
        @JsonProperty("speaker") String speaker,       // 히로인 슬러그 or "AVATAR" or "" (나레이션 only)
        @JsonProperty("emotion") String emotion,
        @JsonProperty("location") String location,
        @JsonProperty("time") String time,
        @JsonProperty("outfit") String outfit,
        @JsonProperty("bgm_mode") String bgmMode,
        @JsonProperty("stat_reflection_hint") String statReflectionHint
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BranchSignal(
        @JsonProperty("level") String level,           // MINOR / MAJOR / CLIMAX
        @JsonProperty("context") String context
    ) {}
}