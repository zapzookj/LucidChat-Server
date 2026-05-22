package com.spring.aichat.dto.director;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * [Phase 5.5-Director v3] 디렉터 엔진의 판단 결과 DTO
 *
 * 판단 유형 (5종):
 *   PASS       — 개입 없음 (기본)
 *   INTERLUDE  — 원샷 깜짝 이벤트 (유저와 함께 있는 상황)
 *   BRANCH     — 3장 카드 선택지 (SCENARIO / CHOICE 2변형)
 *   TRANSITION — 시간/장소 전환
 *   AWAY       — 멀티턴 관찰 이벤트 (유저 부재 시 캐릭터 단독 상황)
 *
 * ⚠️ check*() 패턴 사용 — Jackson의 is*() boolean property 충돌 방지
 */
public record DirectorDirective(

    /** 판단 결과: PASS / INTERLUDE / BRANCH / TRANSITION / AWAY */
    String decision,

    /** 디렉터의 판단 근거 (디버그/로깅용, 프론트 미노출) */
    String reasoning,

    /** narrative_beat 유형: tension_escalation, comic_relief, romantic_catalyst 등 */
    @JsonProperty("narrative_beat")
    String narrativeBeat,

    ContinuePayload cont,

    /** INTERLUDE 유형 — 원샷 깜짝 나레이션 개입 */
    InterludePayload interlude,

    /** BRANCH 유형 — 3장 카드 선택지 (SCENARIO / CHOICE) */
    BranchPayload branch,

    /** TRANSITION 유형 — 시간/장소 전환 */
    TransitionPayload transition,

    /** AWAY 유형 — 유저 부재 시 캐릭터 단독 멀티턴 이벤트 */
    AwayPayload away

) {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  판단 유형 상수 + 체크 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static final String DECISION_PASS = "PASS";
    public static final String DECISION_INTERLUDE = "INTERLUDE";
    public static final String DECISION_BRANCH = "BRANCH";
    public static final String DECISION_TRANSITION = "TRANSITION";
    public static final String DECISION_AWAY = "AWAY";

    public boolean checkPass() { return DECISION_PASS.equalsIgnoreCase(decision); }
    public boolean checkInterlude() { return DECISION_INTERLUDE.equalsIgnoreCase(decision); }
    public boolean checkBranch() { return DECISION_BRANCH.equalsIgnoreCase(decision); }
    public boolean checkTransition() { return DECISION_TRANSITION.equalsIgnoreCase(decision); }
    public boolean checkAway() { return DECISION_AWAY.equalsIgnoreCase(decision); }

    /** 하위 호환 생성자: away 필드 없는 6-param 버전 */
    public DirectorDirective(String decision, String reasoning, String narrativeBeat, ContinuePayload cont,
                             InterludePayload interlude, BranchPayload branch,
                             TransitionPayload transition) {
        this(decision, reasoning, narrativeBeat, cont, interlude, branch, transition, null);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Payload 레코드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * CONTINUE: 같은 신 유지 (기존 코드 그대로)
     */
    public record ContinuePayload(
        String tone,
        @JsonProperty("actor_constraint")
        String actorConstraint,
        @JsonProperty("scene_hint")
        String sceneHint
    ) {}

    /**
     * INTERLUDE: 원샷 깜짝 이벤트 (투명 처리)
     *
     * narration → 대화창에 인라인 삽입되는 나레이션
     * actorConstraint → 캐릭터 LLM에 주입되는 연기 지시
     * environment → 배경/BGM 변경 (null이면 유지)
     */
    public record InterludePayload(
        String narration,
        @JsonProperty("actor_constraint")
        String actorConstraint,
        EnvironmentChange environment,
        @JsonProperty("npc_hint")
        String npcHint
    ) {}

    /**
     * BRANCH: 3장 카드 선택지
     *
     * branchMode:
     *   "SCENARIO" — 유저가 시나리오 자체를 선택 (수동 호출 시)
     *   "CHOICE"   — 디렉터가 상황 제시 + 유저가 반응 선택 (비동기 개입 시)
     *
     * situation → CHOICE 모드에서 먼저 표시할 상황 나레이션 (SCENARIO에서는 null)
     * options → 선택지 목록 (3개)
     */
    public record BranchPayload(
        @JsonProperty("branch_mode")
        String branchMode,
        String situation,
        List<BranchOption> options
    ) {
        public boolean checkScenarioMode() {
            return "SCENARIO".equalsIgnoreCase(branchMode);
        }
        public boolean checkChoiceMode() {
            return "CHOICE".equalsIgnoreCase(branchMode);
        }
    }

    public record BranchOption(
        String label,
        String detail,
        String tone,
        @JsonProperty("energy_cost")
        int energyCost,
        @JsonProperty("is_secret")
        boolean isSecret
    ) {}

    /**
     * TRANSITION: 시간/장소 전환 (투명 처리)
     *
     * <p>[Phase 6-Illust] locationCanonicalKey 신규 필드.
     */
    public record TransitionPayload(
        String narration,
        @JsonProperty("new_time")
        String newTime,
        @JsonProperty("new_location_name")
        String newLocationName,
        @JsonProperty("location_description")
        String locationDescription,
        // ── [Phase 6-Illust] 신규 ──
        @JsonProperty("location_canonical_key")
        String locationCanonicalKey,
        @JsonProperty("actor_constraint")
        String actorConstraint,
        @JsonProperty("new_bgm")
        String newBgm
    ) {}

    /**
     * AWAY: 유저 부재 시 캐릭터 단독 멀티턴 이벤트
     *
     * narration → "한편..." 오프닝 나레이션
     * actorConstraint → 캐릭터 연기 지시
     * environment → 배경/BGM 변경
     * npcHint → 등장할 수 있는 NPC 힌트
     */
    public record AwayPayload(
        String narration,
        @JsonProperty("actor_constraint")
        String actorConstraint,
        EnvironmentChange environment,
        @JsonProperty("npc_hint")
        String npcHint
    ) {}

    /**
     * 환경 변경 정보 (공통 서브 레코드)
     */
    public record EnvironmentChange(
        String location,
        String time,
        String bgm,
        @JsonProperty("location_description")
        String locationDescription,
        // ── [Phase 6-Illust] 신규 ──
        @JsonProperty("location_canonical_key")
        String locationCanonicalKey
    ) {}
}