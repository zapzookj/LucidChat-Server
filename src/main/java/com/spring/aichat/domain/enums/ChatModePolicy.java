package com.spring.aichat.domain.enums;

/**
 * [Phase 5.5-Separation] 모드별 기능 분리 정책 중앙화
 *
 * 모든 모드 의존적 판단(기능 on/off, 주기 상수 등)은 이 클래스를 경유한다.
 * 새로운 모드(예: ARCADE)나 기능이 추가되어도 이 파일만 수정하면 된다.
 *
 * ┌─────────────────────────┬────────┬─────────┐
 * │ Feature                 │ STORY  │ SANDBOX │
 * ├─────────────────────────┼────────┼─────────┤
 * │ Scene Direction         │   ✅   │   ❌    │
 * │ Multi-Scene (2~4/turn)  │   ✅   │   ❌    │
 * │ Promotion Event         │   ✅   │   ❌    │
 * │ Ending Credits          │   ✅   │   ❌    │
 * │ Event Trigger (3-branch)│   ✅   │   ❌    │
 * │ Director Mode           │   ✅   │   ❌    │
 * │ Topic Concluded         │   ✅   │   ❌    │
 * │ Easter Eggs             │   ✅   │   ❌    │
 * │ NPC Summoning           │   ✅   │   ❌    │
 * │ Inner Thought           │   ✅   │   ❌    │
 * │ Cinematic Intro         │   ✅   │   ✅    │
 * │ 5-Axis Stats            │   ✅   │   ✅    │
 * │ BPM Heartbeat           │   ✅   │   ✅    │
 * │ Dynamic Relation Tag    │   ✅   │   ✅    │
 * │ RAG Memory              │   ✅   │   ✅    │
 * │ Secret Mode             │   ✅   │   ✅    │
 * │ Boost Mode              │   ✅   │   ✅    │
 * │ Character Thought       │   ✅   │   ✅*   │
 * │ Memory Summarization    │   ✅   │   ✅*   │
 * │ BGM                     │   ✅   │   ✅*   │
 * └─────────────────────────┴────────┴─────────┘
 *  * = 경량 버전 (주기 확장 또는 고정값)
 */
public final class ChatModePolicy {

    private ChatModePolicy() {} // utility class

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Feature Flags — 기능 ON/OFF
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 씬 디렉션: location/time/outfit/bgmMode 동적 전환 */
    public static boolean supportsSceneDirection(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 멀티 씬: 1턴에 2~4개 씬 출력 */
    public static boolean supportsMultiScene(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 관계 승급 이벤트 */
    public static boolean supportsPromotion(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 엔딩 크레딧 시스템 */
    public static boolean supportsEnding(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 이벤트 트리거 (3분기 선택지 + 디렉터 모드) */
    public static boolean supportsEvents(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** 디렉터 모드 (지켜보기, 시간 넘기기) */
    public static boolean supportsDirectorMode(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** topic_concluded (주제 종료 판단) */
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

    /** 속마음 시스템 (inner_thought) */
    public static boolean supportsInnerThought(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Cycle Constants — 주기 상수 (경량 버전 차등)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 메모리 요약 주기 (USER 메시지 N개마다 트리거) */
    public static long getMemorySummarizationCycle(ChatMode mode) {
        return mode == ChatMode.STORY ? 10 : 15;
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
        // 양쪽 동일 (RAG는 Redis 캐시 기반이므로 비용 차이 없음)
        long cycle = getMemorySummarizationCycle(mode);
        return cycle * 2;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  BGM Policy
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** BGM 동적 전환 지원 여부 (Sandbox는 DAILY 고정) */
    public static boolean supportsBgmTransition(ChatMode mode) {
        return mode == ChatMode.STORY;
    }

    /** Sandbox 모드의 고정 BGM */
    public static String getFixedBgm(ChatMode mode) {
        return mode == ChatMode.SANDBOX ? "DAILY" : null;
    }
}