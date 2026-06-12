package com.spring.aichat.dto.story;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * [V2 Story] DTO 컬렉션 — Theater Requests 패턴 차용.
 */
public final class StoryV2Requests {

    private StoryV2Requests() {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  CreateFlow — World 진입
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * V2 Story 세션 생성 요청.
     * 유저가 로비에서 World 선택 → CreateFlow 4단계 진행 후 호출.
     */
    public record CreateStoryV2Request(
        /** 세계관 ID (예: "MEDIEVAL_FANTASY") */
        @NotBlank
        String worldId,

        /** 등장 히로인 ID 목록 (1~3명) */
        @NotNull
        @Size(min = 1, max = 3, message = "히로인은 1~3명 선택해야 합니다.")
        List<Long> heroineIds,

        /** 시작 장소 키 (WorldLocation.locationKey 참조). null이면 World 기본값 */
        String startLocationKey,

        /**
         * [Phase 7-V2 Pivot] 이 스토리에서 사용할 유저 닉네임.
         * CreateFlow에서 입력. null/blank면 User.nickname 폴백.
         */
        @Size(max = 20)
        String nickname,

        /**
         * 페르소나 본문 — 자유 텍스트 또는 preset 선택 후 description 그대로 전달.
         * null 허용 (유저 기본 페르소나 폴백)
         */
        @Size(max = 500)
        String personaText,

        /**
         * 사전 정의 페르소나를 선택한 경우 그 preset_key (UI 추적용, 선택 기록).
         * 자유 페르소나 입력이면 null.
         */
        String selectedPersonaPresetKey,

        /**
         * 활성 방 덮어쓰기 동의.
         *  - true:  기존 같은 World 방을 *완전 reset* 후 새 흐름 시작 (페르소나 포함)
         *  - false/null: 기존 방 있으면 409 Conflict 응답 (UI에서 confirm 받고 재호출)
         */
        Boolean overwriteExisting
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스토리 초기화
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 스토리 초기화 요청. PUT /story/v2/rooms/:id/reset
     * 페르소나 포함 여부는 유저 선택 (2단계 모달의 결과).
     */
    public record ResetStoryRequest(
        /** true면 페르소나도 초기화 (완전 새 세션). false면 유지. */
        boolean includePersona,

        /** reset 후 시작 장소 (null이면 World 기본값) */
        String startLocationKey
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  메시지 전송 — V1 SendChatRequest와 별개로 V2 actionType 지원
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * V2 메시지 전송 요청.
     *
     * <p>4종 액션 UI는 actionType + actionPayload로 표현:
     * <pre>
     *   - 일반 채팅:        actionType=null,         message="..."
     *   - 장소 이동:        actionType="MOVE",       actionPayload.toLocationKey="GARDEN", message=null 또는 동반
     *   - 시간 넘기기:      actionType="TIME_ADVANCE", message=null
     *   - 다음 씬:          actionType="NEXT_SCENE", message=null
     * </pre>
     */
    public record SendStoryV2MessageRequest(
        /** 유저 자연어 메시지. 액션 단독 트리거 시 null/empty 허용. */
        String message,

        /** "MOVE" | "TIME_ADVANCE" | "NEXT_SCENE" | null (=일반 채팅) */
        String actionType,

        /** 액션별 추가 페이로드. */
        ActionPayload actionPayload
    ) {}

    public record ActionPayload(
        /** MOVE 액션에서 사용 — 이동할 location_key */
        String toLocationKey
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  알림 마킹 (UI)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record MarkNotificationReadRequest(
        @NotNull Long notificationId
    ) {}
}