package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * [Phase 5] 멀티캐릭터 시드 설정
 *
 * 기존 DefaultCharacterProperties(단일 캐릭터)를 대체.
 * application.yml의 app.characters 리스트에서 모든 캐릭터 시드 데이터를 로드.
 *
 * 사용 예:
 *   app:
 *     characters:
 *       - name: 아이리
 *         slug: airi
 *         role: 저택의 메이드
 *         ...
 *       - name: 연화
 *         slug: yeonhwa
 *         role: 숲속의 구미호
 *         ...
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
        String personality,
        String personalitySecret,
        String tone,
        String toneSecret,
        String oocExample,
        String storyBehaviorGuide,
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
        String firstGreeting
    ) {}
}