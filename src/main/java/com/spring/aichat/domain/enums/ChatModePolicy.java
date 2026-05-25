package com.spring.aichat.domain.enums;

/**
 * [Phase 5.5-Separation] 모드별 기능 분리 정책 중앙화
 * [Phase 5.5-Theater]   THEATER 모드 추가
 * [Story V2]            V1 STORY → V2 디렉터 패러다임 전환 + V1 자산을 SANDBOX로 이관
 *
 * <p>모든 모드 의존적 판단(기능 on/off, 주기 상수 등)은 이 클래스를 경유한다.
 * 새로운 모드나 기능이 추가되어도 이 파일만 수정하면 된다.
 *
 * <p>[V2 패치 — 결정 사항 / 자산 이관]
 * V1 STORY 전용이었던 promotion/event/director/easter-eggs/NPC/inner-thought 등은
 * V2 패치 시 STORY의 정체성이 *디렉터 시점 World 탐험*으로 전환되면서 *호환 불능*.
 * 그러나 폐기 대신 *SANDBOX로 이관* — 자산 보존 + 추후 PoC로 검증.
 *
 * <p>[V2 STORY의 신규 패턴]
 * - 멀티 히로인 + 위치 기반 라우팅 (4종 액션 UI: 다음씬/시간넘기기/디렉터옵션/장소이동)
 * - 디렉터 메인 엔진 — V1의 별도 "디렉터 모드"가 아니라 *모든 응답이 디렉터*
 * - LLM 자율 발동 게이트 — promotion/ending 모두 백엔드 자격 활성 + LLM trigger 이중 게이트
 * - DayPart (5단계) 시간 시스템 — V1의 TimeOfDay 4단계 enum과 별개
 *
 * <pre>
 * ┌─────────────────────────┬────────────┬─────────────┬─────────┐
 * │ Feature                 │ STORY (V2) │ SANDBOX     │ THEATER │
 * ├─────────────────────────┼────────────┼─────────────┼─────────┤
 * │ Scene Direction         │     ✅     │  ✅ (이관)  │   ✅    │
 * │ Multi-Scene             │  ✅ (4~5)  │ ✅ 이관 2~3 │  ✅ 5~8 │
 * │ Promotion Event         │   ❌ V2별도│  ✅ (이관)  │   ❌    │ V2는 RelationPromotionEligibility
 * │ Ending Credits          │   ✅ V2별도│  ✅         │   ✅    │ V2는 EndingEligibilityService
 * │ Event Trigger (3-branch)│     ❌     │  ✅ (이관)  │   ❌    │
 * │ Director Mode           │   ❌ 메인  │  ✅ (이관)  │   ❌    │ V2 STORY는 *모든 응답*이 디렉터
 * │ Topic Concluded         │     ✅     │  ✅ (이관)  │   ❌    │
 * │ Easter Eggs             │     ❌     │  ✅ (이관)  │   ❌    │
 * │ NPC Summoning           │  ✅ 멀티H  │  ✅ (이관)  │   ❌    │
 * │ Inner Thought           │     ✅     │  ✅ (이관)  │   ❌    │
 * │ Inner Narration         │     ❌     │     ❌      │   ✅    │ (Theater 고유)
 * │ Cinematic Intro         │     ✅     │     ✅      │   ✅    │
 * │ 5-Axis Heroine Stats    │ ✅ ChatRoom│     ✅      │   ❌    │ V2: ChatRoomHeroine 캐릭터별
 * │                         │  Heroine별 │             │         │
 * │ BPM Heartbeat           │     ✅     │     ✅      │   ❌    │
 * │ Dynamic Relation Tag    │     ✅     │     ✅      │   ❌    │
 * │ RAG Memory              │     ✅     │     ✅      │   ✅    │
 * │ Secret Mode             │     ✅     │     ✅      │   ✅    │
 * │ Boost Mode              │     ✅     │     ✅      │   ❌    │
 * │ Character Thought       │     ✅     │  ✅ (이관)  │   ❌    │
 * │ Memory Summarization    │     ✅     │     ✅      │   ✅    │
 * │ BGM (Dynamic)           │     ✅     │  ✅ (이관)  │   ✅    │ Sandbox: V1 고정 → 이관 후 동적
 * │ Multi-Heroine           │  ✅ V2 신규│     ❌      │   ✅    │
 * │ Location-Based Routing  │  ✅ V2 신규│     ❌      │   ❌    │
 * │ Offscreen Notification  │  ✅ V2 신규│     ❌      │   ❌    │
 * │ 5-DayPart System        │  ✅ V2 신규│     ❌      │   ❌    │ Sandbox는 V1 TimeOfDay 4단계 유지
 * │ Act/Chapter Structure   │     ❌     │     ❌      │   ✅    │
 * │ Scene Batch + Prefetch  │     ❌     │     ❌      │   ✅    │
 * │ Intermission            │     ❌     │     ❌      │   ✅    │
 * │ Stat-gated Branch       │     ❌     │     ❌      │   ✅    │
 * │ Intervention (난입)     │     ❌     │     ❌      │   ✅    │
 * │ Save/Load Slots         │     ❌     │     ❌      │   ✅    │
 * │ Director Note           │     ❌     │     ❌      │   ✅    │
 * └─────────────────────────┴────────────┴─────────────┴─────────┘
 * </pre>
 */
public final class ChatModePolicy {

    private ChatModePolicy() {} // utility class

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Feature Flags — V1 STORY 자산이 SANDBOX로 이관됨에 따라 SANDBOX도 활성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 씬 디렉션: location/time/outfit/bgmMode 동적 전환 */
    public static boolean supportsSceneDirection(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.SANDBOX || mode == ChatMode.THEATER;
    }

    /** 멀티 씬 (STORY V2: 4~5 / SANDBOX: 2~3 (이관) / Theater: 5~8 배치) */
    public static boolean supportsMultiScene(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.SANDBOX || mode == ChatMode.THEATER;
    }

    /**
     * V1 관계 승급 이벤트 시스템 (3단계 mood_score 누적 + director 모드 진입).
     * <p>V1에서는 STORY 전용이었으나 V2 패치 시 SANDBOX로 이관. STORY V2는 별도 메커니즘
     * ({@code RelationPromotionEligibility} + LLM 자율 발동) 사용.
     */
    public static boolean supportsPromotion(ChatMode mode) {
        return mode == ChatMode.SANDBOX;
    }

    /**
     * 엔딩 시스템.
     * <p>STORY V2: 백엔드 자격 활성 + LLM 자율 발동 (EndingEligibilityService).
     * <p>SANDBOX: 즉시 발동 패턴 (V1에서 이관).
     * <p>Theater: 다축 엔딩 별도 엔진.
     */
    public static boolean supportsEnding(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.SANDBOX || mode == ChatMode.THEATER;
    }

    /**
     * 이벤트 트리거 (3분기 선택지 → 디렉터 모드 진입).
     * <p>V1 STORY 전용 → V2 패치 시 SANDBOX로 이관. STORY V2는 디렉터가 직접 흡수.
     */
    public static boolean supportsEvents(ChatMode mode) {
        return mode == ChatMode.SANDBOX;
    }

    /**
     * 디렉터 모드 (지켜보기, 시간 넘기기 등 V1 별도 모드).
     * <p>V1 STORY 전용 → V2 패치 시 SANDBOX로 이관. STORY V2는 *모든 응답이 디렉터*라
     * "별도 디렉터 모드" 개념 자체가 없음.
     */
    public static boolean supportsDirectorMode(ChatMode mode) {
        return mode == ChatMode.SANDBOX;
    }

    /**
     * topic_concluded (주제 종료 판단).
     * <p>STORY V2: 디렉터 응답에 system_updates.topic_concluded 필드로 유지.
     * <p>SANDBOX: V1 패턴 이관.
     */
    public static boolean supportsTopicConcluded(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.SANDBOX;
    }

    /** 이스터에그 트리거 — V1 STORY 전용 → SANDBOX로 이관. */
    public static boolean supportsEasterEggs(ChatMode mode) {
        return mode == ChatMode.SANDBOX;
    }

    /**
     * NPC 등장 (화자가 메인 캐릭터 외 인물).
     * <p>STORY V2: 멀티 히로인 자체가 NPC 시스템을 흡수 — 모든 히로인이 *항상 등장 가능*.
     * <p>SANDBOX: V1 NPC 소환 패턴 이관 (LLM이 speaker 필드로 임시 NPC 등장).
     */
    public static boolean supportsNpc(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.SANDBOX;
    }

    /**
     * 속마음 시스템 (inner_thought).
     * <p>STORY V2: 디렉터 응답의 scene.inner_thought (씬별 화자, 마지막 씬 갱신).
     * <p>SANDBOX: V1 패턴 이관.
     */
    public static boolean supportsInnerThought(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.SANDBOX;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [STORY V2 신규] V2 패러다임 고유 기능
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 멀티 히로인 (한 방에 복수 히로인). STORY V2 + Theater.
     * <p>Sandbox는 1:1 채팅 — 단일 캐릭터.
     */
    public static boolean supportsMultiHeroine(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.THEATER;
    }

    /**
     * 위치 기반 화자 라우팅. STORY V2 전용.
     * <p>유저의 currentUserLocationKey + CharacterPresence를 기반으로 매 턴 화자 결정.
     */
    public static boolean supportsLocationBasedRouting(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 오프스크린 알림 (다른 장소의 캐릭터가 메시지 전송). STORY V2 전용. */
    public static boolean supportsOffscreenNotification(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /**
     * 5단계 DayPart 시간 시스템 (MORNING/NOON/AFTERNOON/EVENING/NIGHT). STORY V2 전용.
     * <p>Sandbox는 V1 TimeOfDay 4단계 enum 유지.
     */
    public static boolean supportsDayPart(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Theater] Theater 전용 기능
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static boolean supportsInnerNarration(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    public static boolean supportsActStructure(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    public static boolean supportsBatchPrefetch(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    public static boolean supportsIntermission(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    public static boolean supportsAvatarStats(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    public static boolean supportsStatGatedBranch(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    public static boolean supportsIntervention(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    public static boolean supportsSaveSlots(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    public static boolean supportsDirectorNote(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    public static boolean supportsLocationChoice(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    public static boolean supportsChapterReport(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Dialogue 그룹 공통 (BPM, 동적 관계, 5축 스탯)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static boolean supportsBpm(ChatMode mode) {
        return mode.isDialogueMode();
    }

    public static boolean supportsDynamicRelationTag(ChatMode mode) {
        return mode.isDialogueMode();
    }

    public static boolean supportsHeroine5AxisStats(ChatMode mode) {
        return mode.isDialogueMode();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Cycle Constants
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 메모리 요약 주기 — USER 메시지 N개마다 트리거 */
    public static long getMemorySummarizationCycle(ChatMode mode) {
        return switch (mode) {
            case STORY -> 10;
            case SANDBOX -> 10;   // V1 이관: 15 → 10 (활성 기능들이 늘어 더 자주 요약 필요)
            case THEATER -> 20;
        };
    }

    /** 캐릭터의 생각 갱신 주기 */
    public static long getCharacterThoughtCycle(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.SANDBOX ? 10 : 15;
    }

    /** 캐릭터의 생각 갱신 오프셋 (5, 15, 25턴) */
    public static long getCharacterThoughtOffset(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.SANDBOX ? 5 : 7;
    }

    /** RAG Skip 임계값 (이 수치 미만이면 RAG 파이프라인 스킵) */
    public static long getRagSkipThreshold(ChatMode mode) {
        long cycle = getMemorySummarizationCycle(mode);
        return cycle * 2;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  BGM Policy
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * BGM 동적 전환 지원 여부.
     * <p>V1: SANDBOX는 고정 BGM (DAILY).
     * <p>V2 이관 후: SANDBOX도 BGM 동적 전환 지원 (V1 STORY 패턴 이관).
     */
    public static boolean supportsBgmTransition(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.SANDBOX || mode == ChatMode.THEATER;
    }

    /**
     * 고정 BGM (지원 안 하는 모드의 기본값).
     * <p>이관 후 모든 모드가 동적 BGM 지원 → 항상 null 반환.
     * <p>본 메서드는 *기존 V1 호출처와의 하위호환*을 위해 시그니처만 유지.
     */
    @Deprecated(forRemoval = true)
    public static String getFixedBgm(ChatMode mode) {
        return null;
    }

    /**
     * 디렉터 엔진 (비동기 선행 디렉팅).
     * <p>STORY V2는 *모든 응답이 디렉터*이므로 "별도 디렉터 엔진" 개념이 없음 → false.
     * <p>V1 STORY 디렉터 엔진은 SANDBOX로 이관.
     */
    public static boolean supportsDirectorEngine(ChatMode mode) {
        return mode == ChatMode.SANDBOX;
    }

    /**
     * 디렉터 인터루드.
     * <p>STORY V2는 인터루드 없음 — 디렉터 응답이 바로 본문. SANDBOX는 V1 패턴 이관.
     */
    public static boolean supportsDirectorInterlude(ChatMode mode) {
        return mode == ChatMode.SANDBOX;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Director Constants — SANDBOX로 이관됨
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 디렉터 최소 개입 간격 (턴). SANDBOX에 이관. STORY V2는 매 턴이 디렉터라 무관. */
    public static int getDirectorMinGap(ChatMode mode) {
        return mode == ChatMode.SANDBOX ? 3 : Integer.MAX_VALUE;
    }

    /** 디렉터 판단 시 참조할 최근 대화 턴 수. SANDBOX에 이관. */
    public static int getDirectorContextTurns(ChatMode mode) {
        return mode == ChatMode.SANDBOX ? 10 : 0;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Theater] Theater 상수 — 변경 없음
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static final int THEATER_TOTAL_ACTS = 4;
    public static final int THEATER_CHAPTERS_PER_ACT_MIN = 5;
    public static final int THEATER_CHAPTERS_PER_ACT_MAX = 8;
    public static final int THEATER_SCENES_PER_CHAPTER_MIN = 25;
    public static final int THEATER_SCENES_PER_CHAPTER_MAX = 40;
    public static final int THEATER_BATCH_SIZE_MIN = 5;
    public static final int THEATER_BATCH_SIZE_MAX = 8;
    public static final double THEATER_PREFETCH_TRIGGER_RATIO = 0.7;
    public static final int INTERMISSION_STAMINA_MAX = 5;
    public static final int INTERMISSION_EXTRA_ENERGY_COST = 2;
    public static final int INTERVENTION_ENERGY_COST = 2;
    public static final int THEATER_MAX_SAVE_SLOTS = 5;
    public static final int BRANCH_MINOR_COST = 0;
    public static final int BRANCH_MAJOR_COST = 1;
    public static final int BRANCH_CLIMAX_COST = 2;
}