package com.spring.aichat.domain.enums;

/**
 * [Phase 5.5-Separation] 모드별 기능 분리 정책 중앙화
 * [Phase 5.5-Theater]   THEATER 모드 추가
 *
 * 모든 모드 의존적 판단(기능 on/off, 주기 상수 등)은 이 클래스를 경유한다.
 * 새로운 모드나 기능이 추가되어도 이 파일만 수정하면 된다.
 *
 * ┌─────────────────────────┬────────┬─────────┬─────────┐
 * │ Feature                 │ STORY  │ SANDBOX │ THEATER │
 * ├─────────────────────────┼────────┼─────────┼─────────┤
 * │ Scene Direction         │   ✅   │   ❌    │   ✅    │
 * │ Multi-Scene (2~4/turn)  │   ✅   │   ❌    │   ✅✅  │ (배치 5~8)
 * │ Promotion Event         │   ✅   │   ❌    │   ❌    │
 * │ Ending Credits          │   ✅   │   ❌    │   ✅    │ (다축)
 * │ Event Trigger (3-branch)│   ✅   │   ❌    │   ❌    │
 * │ Director Mode           │   ✅   │   ❌    │   ❌    │ (Theater는 자체 엔진)
 * │ Topic Concluded         │   ✅   │   ❌    │   ❌    │ (Chapter로 대체)
 * │ Easter Eggs             │   ✅   │   ❌    │   ❌    │
 * │ NPC Summoning           │   ✅   │   ❌    │   ❌    │
 * │ Inner Thought           │   ✅   │   ❌    │   ❌    │ (inner_narration)
 * │ Inner Narration         │   ❌   │   ❌    │   ✅    │ (Theater 고유)
 * │ Cinematic Intro         │   ✅   │   ✅    │   ✅    │
 * │ 5-Axis Heroine Stats    │   ✅   │   ✅    │   ❌    │ (Theater: 호감도 단일축)
 * │ Heroine Affection Only  │   ❌   │   ❌    │   ✅    │
 * │ 5-Axis Avatar Stats     │   ❌   │   ❌    │   ✅    │ (Theater 고유)
 * │ BPM Heartbeat           │   ✅   │   ✅    │   ❌    │
 * │ Dynamic Relation Tag    │   ✅   │   ✅    │   ❌    │
 * │ RAG Memory              │   ✅   │   ✅    │   ✅    │
 * │ Secret Mode             │   ✅   │   ✅    │   ✅    │
 * │ Boost Mode              │   ✅   │   ✅    │   ❌    │ (Theater는 배치 단위)
 * │ Character Thought       │   ✅   │   ✅*   │   ❌    │
 * │ Memory Summarization    │   ✅   │   ✅*   │   ✅    │
 * │ BGM                     │   ✅   │   ✅*   │   ✅    │
 * │ Multi-Heroine           │   ❌   │   ❌    │   ✅    │ (Theater 고유)
 * │ Act/Chapter Structure   │   ❌   │   ❌    │   ✅    │ (Theater 고유)
 * │ Scene Batch + Prefetch  │   ❌   │   ❌    │   ✅    │ (Theater 고유)
 * │ Intermission            │   ❌   │   ❌    │   ✅    │ (Theater 고유)
 * │ Stat-gated Branch       │   ❌   │   ❌    │   ✅    │ (Theater 고유)
 * │ Intervention (난입)     │   ❌   │   ❌    │   ✅    │ (Theater→Story)
 * │ Save/Load Slots         │   ❌   │   ❌    │   ✅    │ (Theater 고유)
 * │ Director Note           │   ❌   │   ❌    │   ✅    │ (Theater 고유)
 * └─────────────────────────┴────────┴─────────┴─────────┘
 *  * = 경량 버전 (주기 확장 또는 고정값)
 */
public final class ChatModePolicy {

    private ChatModePolicy() {} // utility class

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Feature Flags — 기능 ON/OFF
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 씬 디렉션: location/time/outfit/bgmMode 동적 전환 */
    public static boolean supportsSceneDirection(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.THEATER;
    }

    /** 멀티 씬: 1턴에 2~4개 씬 출력 (Theater는 배치 5~8개) */
    public static boolean supportsMultiScene(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.THEATER;
    }

    /** 관계 승급 이벤트 (Theater는 호감도 단일축이므로 승급 없음) */
    public static boolean supportsPromotion(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 엔딩 크레딧 시스템 (Theater는 다축 엔딩으로 별도 엔진) */
    public static boolean supportsEnding(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.THEATER;
    }

    /** 이벤트 트리거 (3분기 선택지 + 디렉터 모드) */
    public static boolean supportsEvents(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 디렉터 모드 (지켜보기, 시간 넘기기) — Story 전용 */
    public static boolean supportsDirectorMode(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** topic_concluded (주제 종료 판단) — Theater는 Chapter 구조로 대체 */
    public static boolean supportsTopicConcluded(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 이스터에그 트리거 */
    public static boolean supportsEasterEggs(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** NPC 소환 (speaker 필드 활용) */
    public static boolean supportsNpc(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 속마음 시스템 (inner_thought) — Story 전용 */
    public static boolean supportsInnerThought(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Theater] Theater 전용 기능
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 내면 나레이션 (3인칭 심리 묘사) — Theater 전용 */
    public static boolean supportsInnerNarration(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** Act/Chapter 서사 구조 */
    public static boolean supportsActStructure(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** 씬 배치 + Prefetch 파이프라인 */
    public static boolean supportsBatchPrefetch(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** 인터미션 시스템 (Act 사이 스탯 성장) */
    public static boolean supportsIntermission(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** 아바타 5축 스탯 */
    public static boolean supportsAvatarStats(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** 스탯 잠금 분기 (Stat-gated Branch) */
    public static boolean supportsStatGatedBranch(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** 난입 시스템 (Theater ↔ Story 왕복 전환) */
    public static boolean supportsIntervention(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** 세이브/로드 슬롯 */
    public static boolean supportsSaveSlots(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** 감독 노트 */
    public static boolean supportsDirectorNote(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** 멀티 히로인 (복수 캐릭터 등장) */
    public static boolean supportsMultiHeroine(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** 장소 선택 분기 (Act 시작 시) */
    public static boolean supportsLocationChoice(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** Chapter 종료 시 호감도 리포트 */
    public static boolean supportsChapterReport(ChatMode mode) {
        return mode == ChatMode.THEATER;
    }

    /** BPM / 동적 관계 태그 / 히로인 5축 스탯 — Dialogue 그룹 한정 */
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
    //  Cycle Constants — 주기 상수 (경량 버전 차등)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 메모리 요약 주기 (USER 메시지 N개마다 트리거) */
    public static long getMemorySummarizationCycle(ChatMode mode) {
        return switch (mode) {
            case STORY -> 10;
            case SANDBOX -> 15;
            case THEATER -> 20; // Theater는 자동 진행이 많아 주기 길게
        };
    }

    /** 캐릭터의 생각 갱신 주기 */
    public static long getCharacterThoughtCycle(ChatMode mode) {
        return mode == ChatMode.STORY ? 10 : 15;
    }

    /** 캐릭터의 생각 갱신 오프셋 (5, 15, 25턴 / 7, 22, 37턴) */
    public static long getCharacterThoughtOffset(ChatMode mode) {
        return mode == ChatMode.STORY ? 5 : 7;
    }

    /** RAG Skip 임계값 (이 수치 미만이면 RAG 파이프라인 스킵) */
    public static long getRagSkipThreshold(ChatMode mode) {
        long cycle = getMemorySummarizationCycle(mode);
        return cycle * 2;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  BGM Policy
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** BGM 동적 전환 지원 여부 */
    public static boolean supportsBgmTransition(ChatMode mode) {
        return mode == ChatMode.STORY || mode == ChatMode.THEATER;
    }

    /** Sandbox 모드의 고정 BGM */
    public static String getFixedBgm(ChatMode mode) {
        return mode == ChatMode.SANDBOX ? "DAILY" : null;
    }

    /** 디렉터 엔진 (비동기 선행 디렉팅) — Story만 해당, Theater는 자체 엔진 */
    public static boolean supportsDirectorEngine(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 디렉터 인터루드 */
    public static boolean supportsDirectorInterlude(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Director Constants
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 디렉터 최소 개입 간격 (턴) */
    public static int getDirectorMinGap(ChatMode mode) {
        return mode == ChatMode.STORY ? 3 : Integer.MAX_VALUE;
    }

    /** 디렉터 판단 시 참조할 최근 대화 턴 수 */
    public static int getDirectorContextTurns(ChatMode mode) {
        return mode == ChatMode.STORY ? 10 : 0;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Theater] Theater 상수
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** Act 총 개수 */
    public static final int THEATER_TOTAL_ACTS = 4;

    /** Act당 Chapter 개수 범위 */
    public static final int THEATER_CHAPTERS_PER_ACT_MIN = 5;
    public static final int THEATER_CHAPTERS_PER_ACT_MAX = 8;

    /** Chapter당 Scene 개수 범위 (Act 전환 직전 Chapter는 최대값) */
    public static final int THEATER_SCENES_PER_CHAPTER_MIN = 25;
    public static final int THEATER_SCENES_PER_CHAPTER_MAX = 40;

    /** 배치당 Scene 개수 (LLM 1회 호출에 반환받는 Scene 수) */
    public static final int THEATER_BATCH_SIZE_MIN = 5;
    public static final int THEATER_BATCH_SIZE_MAX = 8;

    /** Prefetch 트리거: 현재 배치의 몇 %를 소비했을 때 다음 배치 prefetch 시작 */
    public static final double THEATER_PREFETCH_TRIGGER_RATIO = 0.7;

    /** 인터미션 피로도 */
    public static final int INTERMISSION_STAMINA_MAX = 5;

    /** 인터미션 추가 선택 시 에너지 비용 */
    public static final int INTERMISSION_EXTRA_ENERGY_COST = 2;

    /** 난입 에너지 비용 (Story STORY 모드 기본 비용과 동일) */
    public static final int INTERVENTION_ENERGY_COST = 2;

    /** 세이브 슬롯 최대 개수 */
    public static final int THEATER_MAX_SAVE_SLOTS = 5;

    /** 분기 레벨별 에너지 비용 */
    public static final int BRANCH_MINOR_COST = 0;
    public static final int BRANCH_MAJOR_COST = 1;
    public static final int BRANCH_CLIMAX_COST = 2;
}