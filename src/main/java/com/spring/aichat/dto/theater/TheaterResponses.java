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
        String characterSlug,   // [Polish-v2] slug → characterSlug
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
        String leadHeroineSlug,            // [Polish-v2] 추가 — 세션 카드에 리드 히로인 썸네일 렌더용
        String leadHeroineThumbnailUrl,    // [Polish-v2] 추가
        int leadHeroineAffection,
        boolean endingReached,
        String endingTitle,
        long totalSceneCount,
        LocalDateTime lastActiveAt,
        /**
         * [Phase 5.5 UX Polish · R4] 세션 상태 — "ACTIVE" / "ARCHIVED" / "ENDED".
         * null/legacy는 ACTIVE로 간주.
         */
        String sessionStatus,
        /** [R4] ARCHIVED/ENDED로 전환된 시각 — 아카이브 정렬용 */
        LocalDateTime sessionStatusChangedAt
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
        String avatarName,                           // [Polish-v2] flat 편의 필드 (avatar.name 동일값)
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
        int actTotalChapters,           // [Polish-v2] 추가 — 현재 Act의 총 Chapter 수
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
        String characterSlug,    // [Polish-v2] slug → characterSlug (프로젝트 전체 네이밍 통일)
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

    /**
     * Theater용 Scene
     *
     * [Phase 5.5 UX Polish · R1] 스키마 진화
     *  - protagonistInner:  주인공(아바타)의 1인칭 속내. UI 표시.
     *  - heroineInner:      히로인의 속내. UI 미노출 — 백엔드 자산.
     *  - sceneType:         narration / heroine_speaks / avatar_speaks / dialogue_exchange
     *
     *  하위 호환:
     *  - innerNarration 필드는 protagonistInner와 동일 값을 담아 응답 (구버전 클라이언트 보호).
     *    프론트는 protagonistInner를 우선 사용하도록 마이그레이션됨.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TheaterScene(
        Integer sequenceInBatch,
        String speakerName,
        String narration,
        /** [R1] 주인공 속내 — 신규 정식 필드 */
        String protagonistInner,
        /** [R1] 히로인 속내 — UI 미노출 (옵션) */
        String heroineInner,
        /** @deprecated protagonistInner와 동일 값. 구버전 클라이언트 호환용. */
        @Deprecated String innerNarration,
        String dialogue,
        /** [R1] 씬 타입 — 배치 구성 통계용 */
        String sceneType,
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
        String characterSlug,      // [Polish-v2] 추가 — 리포트 모달 캐릭터 이미지 렌더용
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
        /** [Phase 5.5 UX Polish · R3] 명령어 메타 — null이면 일반 메모 */
        String commandType,
        String validationVerdict,
        Boolean wasUsed,
        Integer usedInBatchId,
        LocalDateTime createdAt
    ) {}

    /**
     * [Phase 5.5 UX Polish · R3] 감독 명령어 발동 결과
     *
     *  - accepted=true:  검증 통과 → activeDirectorCommand에 활성화 → 다음 배치 영향
     *  - accepted=false: 거부됨 → 기록 보관, 활성화 안 됨
     *  - rejectedReason: UI 표시용 사용자 친화 메시지 (REJECTED_*.userMessage())
     *  - note:           생성된 DirectorNoteView (활성화 또는 거부 모두 보관)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DirectorCommandResult(
        boolean accepted,
        String verdict,
        String userMessage,
        DirectorNoteView note
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
        /** [R1] 주인공 속내 — 신규 정식 필드 */
        String protagonistInner,
        /** [R1] 히로인 속내 — 미노출 (옵션) */
        String heroineInner,
        /** @deprecated protagonistInner와 동일. 구버전 클라이언트 호환용. */
        @Deprecated String innerNarration,
        String dialogue,
        String speakerType,         // HEROINE / AVATAR / null
        String speakerName,
        Long heroineId,
        /** [R1] scene_type */
        String sceneType,
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