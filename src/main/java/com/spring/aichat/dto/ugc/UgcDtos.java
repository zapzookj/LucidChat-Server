package com.spring.aichat.dto.ugc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * [UGC v1] 스튜디오 위저드 API 계약 DTO 세트.
 *
 * <p>프런트 계약(UGC_Frontend_Implementation_Spec §6):
 * 이미지 URL은 전부 CloudFront 공개 경로 (presigned 금지).
 */
public final class UgcDtos {

    private UgcDtos() {}

    // ── 요청 ──

    /** 컨셉 제출. name은 선택(비우면 LLM 작명). appearance는 외형 구조화 힌트(전부 선택 — 2026-07-20 개편). */
    public record StartCreationRequest(String name, String concept, AppearanceHints appearance) {}

    /** 외형 구조화 힌트 — Stage 0 프롬프트에 "반드시 반영" 지시로 주입 (생성 시작 전에만 지정 가능). */
    public record AppearanceHints(String hair, String eyes, String body,
                                  String outfit, String accessories, String extra) {}

    /** 황금샷/스탠딩 선택 액션 — {@code reroll=true} 또는 {@code selectedIndex} 중 하나. */
    public record GoldenShotAction(Integer selectedIndex, Boolean reroll) {}

    /**
     * 프로필 초안 수정 (Stage0 이후 ~ REVIEW_WAIT — 레이턴시 하이딩 편집. null 필드 유지).
     * 외형 태그는 이미지가 이미 생성되므로 편집 대상이 아니다 (appearance/clothing 한국어 서술은 허용).
     */
    public record UpdateProfileRequest(String name, String tagline, String role,
                                       String personality, String tone,
                                       String appearance, String clothing,
                                       String backstory, String coreValues, String flaws,
                                       String speechQuirks, String firstGreeting, String introNarration) {}

    /** 공개 신청/취소. */
    public record PublishAction(Boolean cancel) {}

    /** 완성 화면 인라인 텍스트 수정 (null 필드는 유지). */
    public record UpdateTextsRequest(String name, String tagline, String personality,
                                     String tone, String firstGreeting) {}

    // ── 응답 ──

    public record StartCreationResponse(Long jobId) {}

    public record GoldenShotView(int index, String url) {}

    /**
     * 감정 컷 뷰 — status: DERIVING/REFINING/READY/CUTTING/DONE/FAILED.
     * [2026-07-20 리롤 누적] versions = 누적 완성본 URL들, selectedIndex = 현재 선택본 위치.
     * 리롤/재시도 중(DERIVING/REFINING)에도 thumbUrl은 직전 선택본을 유지한다(스피너 오버레이용).
     */
    public record EmotionCutView(String status, String thumbUrl,
                                 List<String> versions, Integer selectedIndex) {}

    /** 감정 컷 버전 선택 요청 (무과금). */
    public record VersionSelectRequest(Integer versionIndex) {}

    public record RerollCosts(int goldenShot, int baseStanding, int emotion) {}

    /** 스탠딩 후보 뷰 — status: DERIVING/REFINING/READY/FAILED. */
    public record BaseCandidateView(int index, String status, String url) {}

    /** 프로필 초안 뷰 (편집 폼 초기값 — structuredConcept의 character 부분). */
    public record ProfileView(String name, String tagline, Integer age, String role,
                              String personality, String tone,
                              String appearance, String clothing,
                              String backstory, String coreValues, String flaws,
                              String speechQuirks, String firstGreeting, String introNarration) {}

    /**
     * 잡 상태 (폴링 응답). status → 위저드 스텝 매핑은 프런트 useUgcCreationJob 책임.
     * currentStepHint: CONCEPT_ANALYZING | GOLDEN_GENERATING | 그 외 status와 동일.
     */
    public record CreationJobView(
        Long jobId,
        String status,
        String currentStepHint,
        List<GoldenShotView> goldenShots,
        List<BaseCandidateView> baseCandidates,
        String baseStandingUrl,
        Map<String, EmotionCutView> emotionAssets,
        ProfileView profile,
        int energySpent,
        RerollCosts rerollCosts,
        String failReason,
        Long characterId,
        LocalDateTime expiresAt
    ) {}

    /** 내 UGC 캐릭터 카드 (상태 뱃지: visibility × secretReviewStatus). */
    public record UgcCharacterView(
        Long characterId,
        String name,
        String slug,
        String tagline,
        String thumbnailUrl,
        String defaultImageUrl,
        String visibility,
        boolean secretEligible,
        String secretReviewStatus,
        String reviewNote,
        String personality,
        String tone,
        String firstGreeting
    ) {}

    public record MineResponse(List<UgcCharacterView> characters, CreationJobView activeJob) {}

    public record ExploreItem(Long characterId, String name, String tagline,
                              String thumbnailUrl, String creatorNickname) {}

    public record ExploreResponse(List<ExploreItem> items, Long nextCursor) {}
}
