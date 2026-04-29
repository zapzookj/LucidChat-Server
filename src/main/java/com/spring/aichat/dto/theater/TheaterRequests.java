package com.spring.aichat.dto.theater;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * [Phase 5.5-Theater] Theater 모드 요청 DTO 모음
 */
public final class TheaterRequests {

    private TheaterRequests() {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  세션 생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Theater 세션 생성 요청
     *
     * 유저가 로비에서 세계관 선택 → 아바타 페르소나 입력 → 히로인 선택 후 호출
     */
    public record CreateTheaterSessionRequest(
        @NotBlank
        String worldId,

        /** 이 세션에 등장시킬 히로인 ID 목록 (1~3명) */
        @NotNull
        @Size(min = 1, max = 3, message = "히로인은 1~3명 선택해야 합니다.")
        List<Long> heroineIds,

        /** 아바타 프로필 (nullable — 비어있으면 기본 프리셋) */
        AvatarProfile avatarProfile,

        /** 아바타 이름 (nullable — 비어있으면 유저 닉네임 사용) */
        String avatarName,

        /** 자유 텍스트 페르소나 */
        @Size(max = 500)
        String personaText,

        /**
         * 초기 스탯 분배 (Lucid Pass 가입자 전용)
         * null이면 전부 0으로 시작.
         * 합계 제한은 서버에서 구독 티어에 따라 검증.
         */
        InitialStatDistribution initialStats,

        /**
         * [Phase 5.5 UX Polish · R4] 활성 극이 있을 때 덮어쓰기 동의.
         *  - true:  진행 중인 극을 ARCHIVED로 보관하고 새 극 시작
         *  - false/null: 활성극이 있으면 409 Conflict 응답 (UI에서 confirm 받고 재호출)
         *
         *  활성극이 없으면 이 값은 무관 — 정상적으로 새 극 시작.
         */
        Boolean overwriteActive
    ) {}

    public record InitialStatDistribution(
        int charm,
        int wit,
        int boldness,
        int intellect,
        int empathy
    ) {
        public int total() {
            return charm + wit + boldness + intellect + empathy;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Scene 진행
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 다음 Scene 배치 요청
     *
     * 프론트가 현재 배치의 70% 지점 도달 시 prefetch용으로 호출하거나,
     * 배치 소비 완료 시 동기적으로 호출.
     */
    public record NextBatchRequest(
        /** 이 요청이 prefetch 목적인지 (체감 지연 관리용 힌트) */
        boolean prefetch
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  분기 선택
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record ChooseBranchRequest(
        @NotBlank
        String branchLevel,     // MINOR / MAJOR / CLIMAX / LOCATION

        @NotNull
        Integer chosenIndex,

        /**
         * 클라이언트가 보유한 분기 토큰 (서버 Redis에 있는 브랜치 컨텍스트 참조용).
         * 브랜치 컨텍스트는 별도 LLM 호출 없이 다음 배치 생성 프롬프트에 주입됨.
         */
        String branchToken
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  인터미션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record IntermissionChoiceRequest(
        @NotBlank
        String activityId,

        /** 피로도 소진 후 에너지로 추가 선택하는 경우 true */
        boolean useExtraEnergy
    ) {}

    public record EndIntermissionRequest() {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  난입 (Intervention)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record StartInterventionRequest(
        /** 난입 트리거 종류: USER_INITIATED / SYSTEM_SUGGESTED / BRANCH_SKIP */
        String trigger
    ) {}

    public record ResumeFromInterventionRequest(
        @NotBlank
        String checkpointToken
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  재생 설정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record UpdatePlaySettingsRequest(
        Boolean autoPlayEnabled,
        String playSpeed   // SLOW / NORMAL / FAST
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  세이브/로드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record SaveSlotRequest(
        @NotNull
        Integer slotNumber,    // 1~5 (수동), 0은 Quick Save 전용이라 유저는 직접 지정 불가

        @Size(max = 100)
        String label
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  감독 노트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record CreateDirectorNoteRequest(
        @NotBlank
        @Size(max = 1000)
        String content
    ) {}

    public record UpdateDirectorNoteRequest(
        @NotBlank
        @Size(max = 1000)
        String content
    ) {}

    /**
     * [Phase 5.5 UX Polish · R3] 감독 명령어 발동 요청.
     * 다음 1배치에 환경 변화를 주입한다 (검증 통과 시).
     * 캐릭터 직접 조작은 분류기에서 차단됨.
     */
    public record TriggerDirectorCommandRequest(
        @NotBlank
        @Size(max = 300)
        String content,
        /**
         * 기존 활성 명령어 덮어쓰기를 명시적으로 허용 — UI에서 confirm 받은 후 true.
         * false인데 활성 명령어가 이미 있으면 서비스가 409 응답으로 confirm 요청.
         */
        Boolean overwriteActive
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  아바타 업데이트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record UpdateAvatarRequest(
        String avatarName,
        AvatarProfile profile,
        @Size(max = 500)
        String personaText
    ) {}

    /** 리롤권 사용 요청 (스탯 재분배) */
    public record RerollStatsRequest(
        @NotNull
        InitialStatDistribution newDistribution
    ) {}
}