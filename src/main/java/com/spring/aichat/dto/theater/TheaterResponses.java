package com.spring.aichat.dto.theater;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.spring.aichat.dto.chat.SendChatResponse.SceneResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * [Phase 5.5-Theater] Theater 모드 응답 DTO 모음
 *
 * 프론트엔드로 전달되는 모든 Theater 관련 DTO를 이 파일 하나에 모은다.
 * (응답 DTO는 불변성을 위해 record로 통일)
 */
public final class TheaterResponses {

    private TheaterResponses() {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  세계관 & 로비
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 세계관 카드 (로비 탭용) */
    public record WorldCard(
        String id,
        String displayName,
        String tagline,
        String description,
        String heroImageUrl,
        String thumbnailUrl,
        List<String> moodKeywords,
        boolean secretAllowed,
        int heroineCount,
        /** 이 세계관에 속한 히로인 요약 */
        List<HeroineSummary> heroines
    ) {}

    /** 세계관 카드에 표시할 히로인 요약 */
    public record HeroineSummary(
        Long id,
        String name,
        String slug,
        String tagline,
        String thumbnailUrl
    ) {}

    /** Theater 세션 카드 (Continue 용) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TheaterSessionCard(
        Long roomId,
        String worldId,
        String worldDisplayName,
        String avatarName,
        int currentAct,
        int currentChapter,
        Long leadHeroineId,
        String leadHeroineName,
        int leadHeroineAffection,
        boolean endingReached,
        String endingTitle,
        long totalSceneCount,
        LocalDateTime lastActiveAt
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Theater 상태 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** Theater 방 정보 (진입 시 / 재진입 시) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TheaterRoomInfo(
        Long roomId,
        String worldId,
        String worldDisplayName,
        AvatarSnapshot avatar,
        NarrativeProgress progress,
        List<HeroineAffectionSnapshot> heroines,
        PlaySettings playSettings,
        boolean interventionActive,
        boolean endingReached,
        String endingTitle
    ) {}

    public record AvatarSnapshot(
        String name,
        Map<String, Integer> stats,   // CHARM / WIT / BOLDNESS / INTELLECT / EMPATHY
        AvatarProfile profile,
        String personaText
    ) {}

    public record NarrativeProgress(
        int currentAct,
        String currentActTitle,
        int currentChapter,
        int scenesInCurrentChapter,
        int chapterTargetScenes,
        long totalSceneCount,
        int currentBatchId,
        Long currentHeroineId,
        String currentHeroineName,
        boolean inIntermission,
        int intermissionStamina
    ) {}

    public record HeroineAffectionSnapshot(
        Long characterId,
        String name,
        String slug,
        String thumbnailUrl,
        int affection,
        int lastChapterDelta,
        int totalScenes,
        boolean confirmedMain
    ) {}

    public record PlaySettings(
        boolean autoPlayEnabled,
        String playSpeed
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Scene 배치 응답
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** LLM이 반환한 Scene 배치 (5~8 Scene) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SceneBatch(
        int batchId,
        int actNumber,
        int chapterNumber,
        Long speakerHeroineId,
        String speakerHeroineName,
        List<TheaterScene> scenes,
        /** 이 배치 이후 Chapter가 종료되는지 (디렉터 판단) */
        boolean chapterEndAfter,
        /** 이 배치 말미에 분기가 발생하는지 */
        BranchSignal branchSignal,
        /** 이미 프리페치된 다음 배치가 있는지 (UX 힌트) */
        boolean nextBatchPrefetched,
        /** 이 배치에서 발생한 호감도 변화 (요약) */
        Map<Long, Integer> heroineAffectionDeltas
    ) {}

    /** Theater용 Scene — 기존 SceneResponse에 inner_narration 추가 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TheaterScene(
        Integer sequenceInBatch,
        String speakerName,
        String narration,
        String innerNarration,
        String dialogue,
        String emotion,
        String location,
        String time,
        String outfit,
        String bgmMode,
        /** 이 씬에서 자동 생성된 일러스트 (프롬프트 큐 → 비동기 로드) */
        String illustrationUrl,
        /** 스탯 효과 키워드 ("매력이 낮은 주인공의 어색함이 드러난다" 등) */
        String statReflectionHint
    ) {}

    public record BranchSignal(
        String level,              // MINOR / MAJOR / CLIMAX / LOCATION
        String contextSummary      // 유저에게 보여주지 않고, 다음 분기 API 호출 시 재사용
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  분기 응답
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BranchOptions(
        String branchLevel,
        String contextNarration,    // 분기 시점의 상황 묘사
        List<BranchOption> options,
        int actNumber,
        int chapterNumber,
        long sceneSequence
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BranchOption(
        int index,
        String label,
        String detail,
        String tone,                // normal / affection / bold / witty / introspective
        int energyCost,
        /** 장소 선택일 경우, 어떤 히로인에게 속하는지 (nullable) */
        Long heroineId,
        String heroineName,
        /** 장소 이름 (LOCATION 분기의 경우) */
        String locationName,
        /** 스탯 잠금: 필요한 스탯과 최소값 (null이면 잠금 없음) */
        StatGate statGate,
        /** 현재 유저 스탯 기준으로 잠금이 해제되었는지 */
        boolean unlocked,
        /** 이 분기가 시크릿 유도인지 */
        boolean isSecret
    ) {}

    public record StatGate(
        String requiredStat,        // CHARM / WIT / BOLDNESS / INTELLECT / EMPATHY
        int requiredValue
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  인터미션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IntermissionView(
        int stamina,
        int maxStamina,
        Map<String, Integer> currentStats,
        List<IntermissionActivity> activities,
        /** 이번 인터미션에서 이미 선택한 활동 ID (중복 방지 선택) */
        List<String> completedActivityIds
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IntermissionActivity(
        String id,                  // "workout" / "reading" / ...
        String title,
        String description,
        String icon,
        String targetStat,          // 이 활동이 올릴 스탯
        String animationKey,        // 프론트 애니메이션 매핑 키
        boolean special,            // 특별 이벤트 여부
        int staminaCost,
        int extraEnergyCost         // 피로도 소진 후 추가 선택 시 비용
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IntermissionResult(
        String activityId,
        String outcome,             // FAIL / SUCCESS / GREAT_SUCCESS
        String targetStat,
        int statDelta,
        int newStatValue,
        int remainingStamina,
        String narrationLine,       // 결과 나레이션 (애니메이션과 함께 표시)
        boolean staminaExhausted
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Chapter 종료 리포트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChapterReport(
        int actNumber,
        int chapterNumber,
        String chapterTitle,
        int scenesConsumed,
        int branchesTaken,
        Map<String, Integer> statDeltas,     // 이번 Chapter의 스탯 변화량
        List<HeroineReportItem> heroines,
        List<ReportBadge> badges,
        boolean transitioningToNewAct,
        String nextActTitle,
        boolean leadsToIntermission
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HeroineReportItem(
        Long characterId,
        String name,
        String thumbnailUrl,
        int previousAffection,
        int currentAffection,
        int delta,
        String highlightQuote,
        boolean isLeader,
        boolean justBecameLeader
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReportBadge(
        String code,          // NEW_BRANCH_UNLOCKED / LEAD_SWAPPED / ILLUSTRATION_CREATED / ...
        String title,
        String description,
        String icon,
        String thumbnailUrl   // 관련 미디어 (일러스트 등)
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  난입 (Intervention)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record InterventionStart(
        Long roomId,
        String checkpointToken,     // 복귀 시 검증용 (서버가 Redis에 동일 토큰 저장)
        String transitionNarration, // "당신의 차례입니다"
        Long currentHeroineId,
        String currentHeroineName
    ) {}

    public record InterventionResume(
        Long roomId,
        boolean resumed,
        /** 유저 개입을 반영한 재디렉팅 힌트 */
        String redirectHint
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  세이브/로드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SaveSlotView(
        int slotNumber,
        String label,
        String previewText,
        int actNumber,
        int chapterNumber,
        Long leadHeroineId,
        String leadHeroineName,
        boolean quickSave,
        LocalDateTime savedAt,
        /** 슬롯이 비어있으면 empty=true */
        boolean empty
    ) {}

    public record SaveResult(
        int slotNumber,
        LocalDateTime savedAt,
        String previewText
    ) {}

    public record LoadResult(
        Long roomId,
        int slotNumber,
        boolean success,
        String message
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  감독 노트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DirectorNoteView(
        Long id,
        String noteType,
        String content,
        Integer actNumber,
        Integer chapterNumber,
        Long relatedHeroineId,
        String relatedHeroineName,
        String relatedIllustrationUrl,
        LocalDateTime createdAt
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔딩
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TheaterEnding(
        String endingType,
        String moodCategory,           // HAPPY / NEUTRAL / BAD
        String title,
        Long mainHeroineId,
        String mainHeroineName,
        int mainHeroineAffection,
        String dominantStat,
        int dominantStatValue,
        List<SceneResponse> endingScenes,
        String closingQuote,
        List<String> memoryHighlights, // RAG 기반 추억 회상
        EndingStats stats
    ) {}

    public record EndingStats(
        int totalActs,
        long totalScenes,
        int totalBranchChoices,
        int totalInterventions,
        int totalIllustrationsGenerated,
        int totalPlayMinutes,
        Map<String, Integer> finalStats,
        Map<Long, Integer> finalAffections
    ) {}

    /** 대화 기록 한 항목 */
    public record SceneHistoryItem(
        String id,
        int actNumber,
        int chapterNumber,
        int batchId,
        int sceneIndexInBatch,
        int sceneSeqInChapter,
        long globalSceneSeq,
        String narration,
        String innerNarration,
        String dialogue,
        String speakerType,         // HEROINE / AVATAR / null
        String speakerName,
        Long heroineId,
        String emotion,             // EmotionTag 이름
        String location,
        String timeOfDay,
        String outfit,
        String bgmMode,
        String illustrationUrl,
        java.time.LocalDateTime createdAt
    ) {}

    /** 대화 기록 페이지 응답 */
    public record SceneHistoryPage(
        java.util.List<SceneHistoryItem> items,
        int page,
        int size,
        int totalPages,
        long totalElements
    ) {}
}