package com.spring.aichat.dto.admin;

import java.util.List;
import java.util.Map;

/**
 * [UGC v1] 백오피스 UGC 승인 큐 DTO — 공개 신청 + Secret 단독 신청 통합 검수.
 */
public final class UgcReviewDtos {

    private UgcReviewDtos() {}

    /** 큐 항목. requestType: PUBLISH | SECRET | BOTH. */
    public record QueueItem(
        Long characterId,
        String name,
        String slug,
        String thumbnailUrl,
        Long ownerUserId,
        String ownerNickname,
        String visibility,
        String secretReviewStatus,
        String requestType
    ) {}

    /** 상세 검토 — 에셋·설정을 한 화면에서 (검수 항목: 미성년 시그널·기존 IP 모방·최소 품질선). */
    public record DetailResponse(
        QueueItem summary,
        String tagline,
        String description,
        String role,
        String personality,
        String tone,
        String appearance,
        String clothing,
        String backstory,
        String coreValues,
        String flaws,
        String speechQuirks,
        String firstGreeting,
        String reviewNote,
        boolean secretEligible,
        /** EmotionTag → CloudFront URL (확정 에셋 15종). */
        Map<String, String> emotionAssets
    ) {}

    /**
     * 판정 — 체크박스 2개 동시 제출.
     * publishApprove: true=공개 승인 / false=반려(PRIVATE 회귀) / null=미판정.
     * secretApprove: true=Secret 허용 / false=반려·회수 / null=미판정.
     */
    public record ReviewRequest(Boolean publishApprove, Boolean secretApprove, String note) {}

    public record QueueResponse(List<QueueItem> items) {}

    /**
     * [프롬프트 인스펙션 2026-07-20] 캐릭터 일러 생성에 실제 들어간 프롬프트 재구성.
     * 최종 프롬프트는 잡의 구조화 태그 + 서버 상수의 결정적 함수라 저장 없이 정확 재현된다.
     * (외형 태그는 Stage0 이후 불변이므로 제출 시점 값과 동일)
     */
    public record PromptInspection(
        Long jobId,
        List<String> appearanceTags,
        List<String> personaTags,
        List<String> sceneTags,
        String bgColor,
        /** WF-1 positive (황금샷) */
        String goldenShotPositive,
        /** WF-2 positive — NEUTRAL(베이스) 예시 */
        String refinePositiveNeutral,
        /** WF-2 positive — JOY 예시 (감정 태그 차이 확인용) */
        String refinePositiveJoy,
        /** FaceDetailer 와일드카드 (얼굴 일관성 주입분) */
        String faceDetailWildcard,
        /** 네거티브 (템플릿 동결 상수) */
        String negative,
        /** Qwen 패스1 — 자세 표준화 */
        String qwenPosePrompt,
        /** Qwen 패스2 — 배경 클린업 (BG_COLOR 주입본) */
        String qwenBackgroundPrompt,
        /** Qwen 감정 파생 — JOY 예시 (persona 힌트 포함본) */
        String qwenEmotionJoy,
        /** Qwen 공통 네거티브 */
        String qwenNegative
    ) {}
}
