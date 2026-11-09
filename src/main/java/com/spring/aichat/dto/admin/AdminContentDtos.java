package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.world.World;

import java.time.LocalDateTime;

/**
 * [2026-11-09] 백오피스 콘텐츠 RUD — 캐릭터·공식 세계관 상세/편집 DTO.
 *
 * <p>편집 요청은 부분 갱신이다: null 필드는 변경하지 않는다. 화면은 바뀐 필드만 보낸다.
 * {@code seedManaged}=true면 YAML 시드가 관리하는 행이라, 편집한 필드가 {@code lockedFields}(필드 단위 시드 잠금)에
 * 더해진다. {@code seedLocked} = 잠긴 필드가 1개 이상.
 */
public final class AdminContentDtos {

    private AdminContentDtos() {}

    // ━━━ Character ━━━

    public record CharacterDetail(
        Long id, String slug, String source, String visibility, Long ownerUserId,
        String worldId, Long ugcWorldId,
        boolean hidden, boolean storyAvailable, boolean theaterAvailable,
        boolean seedManaged, boolean seedLocked, java.util.List<String> lockedFields,
        // 편집 가능 필드 (Character.AdminContentEdit와 동일 순서)
        String name, String tagline, String description, String thumbnailUrl, String defaultImageUrl,
        String role, Integer age, String llmModelName, String ttsVoiceId,
        String height, String likes, String dislikes, String hobby, String moodTags, String profileQuote,
        String difficulty,
        String baseSystemPrompt, String personality, String personalitySecret, String tone, String toneSecret,
        String oocExample, String storyBehaviorGuide,
        String appearance, String clothing, String backstory, String coreValues, String flaws,
        String speechQuirks, String promotionScenarios, String easterEggDialogue,
        String introNarration, String firstGreeting,
        String endingRoleDesc, String endingQuoteHappy, String endingQuoteBad,
        String homeLocations, String theaterIntroBeat
    ) {
        public static CharacterDetail from(Character c, boolean seedManaged) {
            return new CharacterDetail(
                c.getId(), c.getSlug(),
                c.getSource() != null ? c.getSource().name() : null,
                c.getVisibility() != null ? c.getVisibility().name() : null,
                c.getOwnerUserId(),
                c.getWorldId() != null ? c.getWorldId().name() : null, c.getUgcWorldId(),
                c.isHidden(), c.isStoryAvailable(), c.isTheaterAvailable(),
                seedManaged, c.isSeedLocked(), java.util.List.copyOf(c.getSeedLockedFieldSet()),
                c.getName(), c.getTagline(), c.getDescription(), c.getThumbnailUrl(), c.getDefaultImageUrl(),
                c.getRole(), c.getAge(), c.getLlmModelName(), c.getTtsVoiceId(),
                c.getHeight(), c.getLikes(), c.getDislikes(), c.getHobby(), c.getMoodTags(), c.getProfileQuote(),
                c.getDifficulty() != null ? c.getDifficulty().name() : null,
                c.getBaseSystemPrompt(), c.getPersonality(), c.getPersonalitySecret(), c.getTone(), c.getToneSecret(),
                c.getOocExample(), c.getStoryBehaviorGuide(),
                c.getAppearance(), c.getClothing(), c.getBackstory(), c.getCoreValues(), c.getFlaws(),
                c.getSpeechQuirks(), c.getPromotionScenarios(), c.getEasterEggDialogue(),
                c.getIntroNarration(), c.getFirstGreeting(),
                c.getEndingRoleDesc(), c.getEndingQuoteHappy(), c.getEndingQuoteBad(),
                c.getHomeLocations(), c.getTheaterIntroBeat());
        }
    }

    public record CharacterEditRequest(
        String name, String tagline, String description, String thumbnailUrl, String defaultImageUrl,
        String role, Integer age, String llmModelName, String ttsVoiceId,
        String height, String likes, String dislikes, String hobby, String moodTags, String profileQuote,
        String difficulty,
        String baseSystemPrompt, String personality, String personalitySecret, String tone, String toneSecret,
        String oocExample, String storyBehaviorGuide,
        String appearance, String clothing, String backstory, String coreValues, String flaws,
        String speechQuirks, String promotionScenarios, String easterEggDialogue,
        String introNarration, String firstGreeting,
        String endingRoleDesc, String endingQuoteHappy, String endingQuoteBad,
        String homeLocations, String theaterIntroBeat,
        String reason
    ) {
        public Character.AdminContentEdit toEdit() {
            return new Character.AdminContentEdit(
                name, tagline, description, thumbnailUrl, defaultImageUrl,
                role, age, llmModelName, ttsVoiceId,
                height, likes, dislikes, hobby, moodTags, profileQuote,
                difficulty,
                baseSystemPrompt, personality, personalitySecret, tone, toneSecret,
                oocExample, storyBehaviorGuide,
                appearance, clothing, backstory, coreValues, flaws,
                speechQuirks, promotionScenarios, easterEggDialogue,
                introNarration, firstGreeting,
                endingRoleDesc, endingQuoteHappy, endingQuoteBad,
                homeLocations, theaterIntroBeat);
        }
    }

    // ━━━ World (공식) ━━━

    public record WorldSummary(
        String id, String displayName, String tagline, String thumbnailUrl,
        boolean active, boolean secretAllowed, int displayOrder,
        boolean seedManaged, boolean seedLocked, long characterCount
    ) {
        public static WorldSummary from(World w, boolean seedManaged, long characterCount) {
            return new WorldSummary(w.getId().name(), w.getDisplayName(), w.getTagline(), w.getThumbnailUrl(),
                w.isActive(), w.isSecretAllowed(), w.getDisplayOrder(),
                seedManaged, w.isSeedLocked(), characterCount);
        }
    }

    public record WorldDetail(
        String id, boolean active, boolean seedManaged, boolean seedLocked, java.util.List<String> lockedFields,
        LocalDateTime createdAt,
        String displayName, String tagline, String description,
        String heroImageUrl, String thumbnailUrl, String openingNarration,
        String defaultBgm, String moodKeywords, boolean secretAllowed, int displayOrder,
        java.util.List<String> bgmOptions
    ) {
        public static WorldDetail from(World w, boolean seedManaged, java.util.List<String> bgmOptions) {
            return new WorldDetail(w.getId().name(), w.isActive(), seedManaged, w.isSeedLocked(),
                java.util.List.copyOf(w.getSeedLockedFieldSet()), w.getCreatedAt(),
                w.getDisplayName(), w.getTagline(), w.getDescription(),
                w.getHeroImageUrl(), w.getThumbnailUrl(), w.getOpeningNarration(),
                w.getDefaultBgm(), w.getMoodKeywords(), w.isSecretAllowed(), w.getDisplayOrder(),
                bgmOptions);
        }
    }

    public record WorldEditRequest(
        String displayName, String tagline, String description,
        String heroImageUrl, String thumbnailUrl, String openingNarration,
        String defaultBgm, String moodKeywords, Boolean secretAllowed, Integer displayOrder,
        String reason
    ) {
        public World.AdminContentEdit toEdit() {
            return new World.AdminContentEdit(displayName, tagline, description,
                heroImageUrl, thumbnailUrl, openingNarration,
                defaultBgm, moodKeywords, secretAllowed, displayOrder);
        }
    }

    public record WorldActiveRequest(Boolean active, String reason) {}

    /** 시드로 되돌리기 결과 — seedFound=false면 시드에 해당 행이 없어 잠금만 풀었다. */
    public record RevertSeedResult(boolean seedFound) {}
}
