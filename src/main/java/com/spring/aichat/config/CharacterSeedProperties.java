package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * [Phase 5] 멀티캐릭터 시드 설정
 * [Phase 5.5-Theater] Theater 모드 관련 필드 추가
 *
 * 기존 DefaultCharacterProperties(단일 캐릭터)를 대체.
 * application.yml의 app.characters 리스트에서 모든 캐릭터 시드 데이터를 로드.
 */
@ConfigurationProperties(prefix = "app")
public record CharacterSeedProperties(
    List<CharacterSeed> characters
) {

    public record CharacterSeed(
        // ── 필수 필드 ──
        String name,
        String slug,
        String llmModelName,
        String baseSystemPrompt,

        // ── 선택 필드: 기본 정보 ──
        String ttsVoiceId,
        String defaultImageUrl,

        // ── 로비 표시용 ──
        String tagline,
        String thumbnailUrl,
        String description,
        Boolean storyAvailable,

        // ── 프롬프트 메타데이터 ──
        String role,
        Integer age,
        String background,
        String personality,
        String personalitySecret,
        String tone,
        String toneSecret,
        String oocExample,
        String storyBehaviorGuide,

        // ── [Phase 6 도그푸딩 #3] 캐릭터 영혼 필드 ──
        /** 캐릭터 과거사 — 어떤 사건이 지금의 가치관을 형성했는가 */
        String backstory,
        /** 가치관/철학 — 무엇을 옳다/그르다 여기는가 */
        String coreValues,
        /** 약점·두려움·모순 */
        String flaws,
        /** 절대 하지 않는 것 — 영혼의 기둥 */
        String behavioralAnchors,
        /** 어휘 습관·말버릇 */
        String speechQuirks,

        String promotionScenarios,
        String easterEggDialogue,
        String defaultOutfit,
        String defaultLocation,

        // ── [Phase 4 Fix] 캐릭터별 독립 세계관 ──
        String baseOutfits,
        String baseLocations,
        String acquaintanceUnlockOutfits,
        String acquaintanceUnlockLocations,
        String friendUnlockOutfits,
        String friendUnlockLocations,
        String loverUnlockOutfits,
        String loverUnlockLocations,
        String outfitDescriptions,

        // ── 엔딩 ──
        String endingRoleDesc,
        String endingQuoteHappy,
        String endingQuoteBad,
        String introNarration,
        String firstGreeting,

        // ── [Phase 5.5-Theater] Theater 모드 ──
        /** 소속 세계관 (WorldId enum 문자열: MEDIEVAL_FANTASY / ORIENTAL_FANTASY / MODERN_KOREA) */
        String worldId,
        /** Theater 모드 지원 여부 */
        Boolean theaterAvailable,
        /** 영역 장소 (줄 구분, Theater 장소 선택 분기용) */
        String homeLocations,
        /** Theater Act 1 첫 만남 시드 나레이션 */
        String theaterIntroBeat
    ) {}
}