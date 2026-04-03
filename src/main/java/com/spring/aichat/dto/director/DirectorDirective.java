package com.spring.aichat.dto.director;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * [Phase 5.5-Director] 디렉터 엔진의 판단 결과 DTO
 *
 * 디렉터가 비동기 후처리에서 생성하여 Redis에 캐시.
 * 다음 유저 턴 시점에 프론트가 조회 → 인터루드 시퀀스 발동.
 *
 * ┌─────────────────────────────────────────────────┐
 * │  decision 유형별 동작                              │
 * ├──────────┬──────────────────────────────────────┤
 * │ PASS     │ 개입 없음. 캐시에 저장되지 않음.         │
 * │ INTERLUDE│ 나레이션 → 유저 인지 → 행동 (대화 도중)  │
 * │ BRANCH   │ 선택지 3개 제시 (기존 이벤트의 진화형)    │
 * │ TRANSITION│ 시간/장소 전환 나레이션 (씬 마무리 후)   │
 * └──────────┴──────────────────────────────────────┘
 */
public record DirectorDirective(

    /** 판단 결과: PASS / INTERLUDE / BRANCH / TRANSITION */
    String decision,

    /** 디렉터의 판단 근거 (디버그/로깅용, 프론트 미노출) */
    String reasoning,

    /** narrative_beat 유형: tension_escalation, comic_relief, romantic_catalyst 등 */
    @JsonProperty("narrative_beat")
    String narrativeBeat,

    /** INTERLUDE 유형 — 깜짝 나레이션 개입 */
    InterludePayload interlude,

    /** BRANCH 유형 — 3-Branch 선택지 (기존 이벤트 진화형) */
    BranchPayload branch,

    /** TRANSITION 유형 — 시간/장소 전환 */
    TransitionPayload transition

) {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  판단 유형 상수
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static final String DECISION_PASS = "PASS";
    public static final String DECISION_INTERLUDE = "INTERLUDE";
    public static final String DECISION_BRANCH = "BRANCH";
    public static final String DECISION_TRANSITION = "TRANSITION";

    @JsonIgnore public boolean isPass() { return DECISION_PASS.equalsIgnoreCase(decision); }
    @JsonIgnore public boolean isInterlude() { return DECISION_INTERLUDE.equalsIgnoreCase(decision); }
    @JsonIgnore public boolean isBranch() { return DECISION_BRANCH.equalsIgnoreCase(decision); }
    @JsonIgnore public boolean isTransition() { return DECISION_TRANSITION.equalsIgnoreCase(decision); }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Payload 레코드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * INTERLUDE: 대화 도중 깜짝 개입
     *
     * narration → 유저에게 직접 표시
     * actorConstraint → 액터(캐릭터 엔진)에게 주입되는 연기 지시
     * environment → 배경/BGM 변경 (null이면 유지)
     * userAgency → 인터루드 후 유저 행동 모드
     *   "FREE"    : 자유 입력 (기본)
     *   "OBSERVER" : 관찰자 시점 (계속 지켜보기 / 개입하기 버튼)
     */
    public record InterludePayload(
        String narration,
        @JsonProperty("actor_constraint")
        String actorConstraint,
        EnvironmentChange environment,
        @JsonProperty("user_agency")
        String userAgency,
        @JsonProperty("npc_hint")
        String npcHint
    ) {
        @JsonIgnore public boolean isObserverMode() {
            return "OBSERVER".equalsIgnoreCase(userAgency);
        }
    }

    /**
     * BRANCH: 선택지 제시 (기존 3-Branch 이벤트의 디렉터 통합형)
     *
     * situation → 유저에게 표시할 상황 설명
     * options → 선택지 목록 (2~3개)
     */
    public record BranchPayload(
        String situation,
        List<BranchOption> options
    ) {}

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
     * TRANSITION: 시간/장소 전환
     *
     * narration → 전환 나레이션 텍스트 (유저에게 표시)
     * newTime → 전환 후 시간대 (DAY/NIGHT/SUNSET 등)
     * newLocationName → 새 장소명 (동적 배경 생성 트리거)
     * locationDescription → 장소 설명 (배경 생성 프롬프트용)
     * actorConstraint → 전환 후 캐릭터 행동 지시
     */
    public record TransitionPayload(
        String narration,
        @JsonProperty("new_time")
        String newTime,
        @JsonProperty("new_location_name")
        String newLocationName,
        @JsonProperty("location_description")
        String locationDescription,
        @JsonProperty("actor_constraint")
        String actorConstraint,
        @JsonProperty("new_bgm")
        String newBgm
    ) {}

    /**
     * 환경 변경 정보 (공통 서브 레코드)
     */
    public record EnvironmentChange(
        String location,
        String time,
        String bgm,
        @JsonProperty("location_description")
        String locationDescription
    ) {}
}