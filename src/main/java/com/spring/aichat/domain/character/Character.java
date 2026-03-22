package com.spring.aichat.domain.character;

import com.spring.aichat.config.CharacterSeedProperties;
import com.spring.aichat.domain.enums.RelationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.*;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "characters")
/**
 * AI 캐릭터 메타정보 엔티티
 *
 * [Phase 4.5] 로비 표시용 필드 추가
 * [Phase 5]   멀티캐릭터 확장 — 프롬프트/에셋 필드 분리
 *
 *   ▸ slug:                URL-safe 식별자 (에셋 경로: /characters/{slug}/...)
 *   ▸ role:                역할 설명 ("저택의 메이드", "숲속의 구미호")
 *   ▸ personality:         성격 (일반 모드)
 *   ▸ personalitySecret:   성격 (시크릿 모드, nullable)
 *   ▸ tone:                말투 (일반 모드)
 *   ▸ toneSecret:          말투 (시크릿 모드, nullable)
 *   ▸ oocExample:          메타 질문 회피 예시 대사
 *   ▸ storyBehaviorGuide:  관계별 행동 가이드 (스토리 모드 전용, TEXT)
 *   ▸ promotionScenarios:  승급 이벤트 시나리오 가이드 (TEXT, nullable)
 *   ▸ easterEggDialogue:   이스터에그 커스텀 대사 가이드 (TEXT, nullable)
 *   ▸ defaultOutfit:       기본 복장 (enum string)
 *   ▸ defaultLocation:     기본 장소 (enum string)
 *   ▸ endingRoleDesc:      엔딩 프롬프트용 역할 설명 (영문)
 *   ▸ endingQuoteHappy:    해피엔딩 폴백 인용구
 *   ▸ endingQuoteBad:      배드엔딩 폴백 인용구
 */
public class Character {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "character_id")
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    /** URL-safe 식별자 — 에셋 경로 key (예: "airi", "yeonhwa") */
    @Column(nullable = false, unique = true, length = 50)
    private String slug;

    @Column(name = "base_system_prompt", nullable = false, columnDefinition = "TEXT")
    private String baseSystemPrompt;

    @Column(name = "llm_model_name", nullable = false, length = 100)
    private String llmModelName;

    @Column(name = "tts_voice_id", length = 100)
    private String ttsVoiceId;

    @Column(name = "default_image_url", length = 500)
    private String defaultImageUrl;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4.5] 로비 표시용 필드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "tagline", length = 100)
    private String tagline;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "story_available", nullable = false)
    private boolean storyAvailable = true;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5] 캐릭터별 프롬프트 메타데이터
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 역할 설명 (예: "저택의 메이드", "숲속의 구미호") */
    @Column(name = "role", length = 100)
    private String role;

    /** 나이 (프롬프트 주입용, nullable) */
    @Column(name = "age", length = 100)
    private Integer age;

    /** 성격 — 일반 모드 */
    @Column(name = "personality", columnDefinition = "TEXT")
    private String personality;

    /** 성격 — 시크릿 모드 (nullable, 없으면 personality 폴백) */
    @Column(name = "personality_secret", columnDefinition = "TEXT")
    private String personalitySecret;

    /** 말투 — 일반 모드 */
    @Column(name = "tone", length = 300)
    private String tone;

    /** 말투 — 시크릿 모드 (nullable) */
    @Column(name = "tone_secret", length = 300)
    private String toneSecret;

    /** 메타 질문 회피 예시 대사 */
    @Column(name = "ooc_example", columnDefinition = "TEXT")
    private String oocExample;

    /**
     * 관계별 행동 가이드 (스토리 모드 전용)
     * STRANGER, ACQUAINTANCE, FRIEND, LOVER별 행동 · 감정범위 · 경계선
     */
    @Column(name = "story_behavior_guide", columnDefinition = "TEXT")
    private String storyBehaviorGuide;

    /**
     * 승급 이벤트 시나리오 가이드 (스토리 모드 전용, nullable)
     * ACQUAINTANCE/FRIEND/LOVER 승급 시 시나리오 플레이버
     */
    @Column(name = "promotion_scenarios", columnDefinition = "TEXT")
    private String promotionScenarios;

    /**
     * 이스터에그 커스텀 대사 블록 (nullable → 기본 블록 사용)
     */
    @Column(name = "easter_egg_dialogue", columnDefinition = "TEXT")
    private String easterEggDialogue;

    /** 기본 복장 (enum string: MAID, HANBOK 등) */
    @Column(name = "default_outfit", length = 30)
    private String defaultOutfit;

    /** 기본 장소 (enum string: ENTRANCE, FOREST 등) */
    @Column(name = "default_location", length = 30)
    private String defaultLocation;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4 Fix] 캐릭터별 복장/장소 독립 세계관
    //  각 캐릭터의 고유 복장·장소 풀 + 관계별 해금 규칙
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** STRANGER 레벨부터 사용 가능한 복장 (쉼표 구분, 예: "MAID") */
    @Column(name = "base_outfits", length = 500)
    private String baseOutfits;

    /** STRANGER 레벨부터 사용 가능한 장소 (쉼표 구분) */
    @Column(name = "base_locations", length = 500)
    private String baseLocations;

    /** ACQUAINTANCE 승급 시 해금되는 복장 (쉼표 구분, 예: "DATE,PAJAMA") */
    @Column(name = "acquaintance_unlock_outfits", length = 200)
    private String acquaintanceUnlockOutfits;

    /** ACQUAINTANCE 승급 시 해금되는 장소 */
    @Column(name = "acquaintance_unlock_locations", length = 200)
    private String acquaintanceUnlockLocations;

    /** FRIEND 승급 시 해금되는 복장 */
    @Column(name = "friend_unlock_outfits", length = 200)
    private String friendUnlockOutfits;

    /** FRIEND 승급 시 해금되는 장소 */
    @Column(name = "friend_unlock_locations", length = 200)
    private String friendUnlockLocations;

    /** LOVER 승급 시 해금되는 복장 */
    @Column(name = "lover_unlock_outfits", length = 200)
    private String loverUnlockOutfits;

    /** LOVER 승급 시 해금되는 장소 */
    @Column(name = "lover_unlock_locations", length = 200)
    private String loverUnlockLocations;

    /** 복장별 설명 (프롬프트 주입용, 줄 구분: "MAID:Default maid attire\nDATE:Going-out clothes") */
    @Column(name = "outfit_descriptions", columnDefinition = "TEXT")
    private String outfitDescriptions;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5] 엔딩 프롬프트용 필드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 엔딩 프롬프트용 영문 역할 설명 (예: "a maid in a mansion") */
    @Column(name = "ending_role_desc", length = 200)
    private String endingRoleDesc;

    @Column(name = "ending_quote_happy", columnDefinition = "TEXT")
    private String endingQuoteHappy;

    @Column(name = "ending_quote_bad", columnDefinition = "TEXT")
    private String endingQuoteBad;

    @Column(name = "intro_narration", columnDefinition = "TEXT")
    private String introNarration;

    @Column(name = "first_greeting", columnDefinition = "TEXT")
    private String firstGreeting;


    public Character(String name, String slug, String baseSystemPrompt, String llmModelName) {
        this.name = name;
        this.slug = slug;
        this.baseSystemPrompt = baseSystemPrompt;
        this.llmModelName = llmModelName;
    }

    /**
     * 시드 데이터로 전체 필드 동기화
     */
    public void applySeed(CharacterSeedProperties.CharacterSeed seed) {
        this.name = seed.name();
        this.slug = seed.slug();
        this.baseSystemPrompt = seed.baseSystemPrompt();
        this.llmModelName = seed.llmModelName();
        this.ttsVoiceId = seed.ttsVoiceId();
        this.defaultImageUrl = seed.defaultImageUrl();

        // 로비 필드
        if (seed.tagline() != null) this.tagline = seed.tagline();
        if (seed.thumbnailUrl() != null) this.thumbnailUrl = seed.thumbnailUrl();
        if (seed.description() != null) this.description = seed.description();
        if (seed.storyAvailable() != null) this.storyAvailable = seed.storyAvailable();

        // 프롬프트 메타데이터
        if (seed.role() != null) this.role = seed.role();
        if (seed.personality() != null) this.personality = seed.personality();
        if (seed.personalitySecret() != null) this.personalitySecret = seed.personalitySecret();
        if (seed.tone() != null) this.tone = seed.tone();
        if (seed.toneSecret() != null) this.toneSecret = seed.toneSecret();
        if (seed.oocExample() != null) this.oocExample = seed.oocExample();
        if (seed.storyBehaviorGuide() != null) this.storyBehaviorGuide = seed.storyBehaviorGuide();
        if (seed.promotionScenarios() != null) this.promotionScenarios = seed.promotionScenarios();
        if (seed.easterEggDialogue() != null) this.easterEggDialogue = seed.easterEggDialogue();
        if (seed.defaultOutfit() != null) this.defaultOutfit = seed.defaultOutfit();
        if (seed.defaultLocation() != null) this.defaultLocation = seed.defaultLocation();
        // [Phase 4 Fix] 캐릭터별 독립 세계관 필드
        if (seed.baseOutfits() != null) this.baseOutfits = seed.baseOutfits();
        if (seed.baseLocations() != null) this.baseLocations = seed.baseLocations();
        if (seed.acquaintanceUnlockOutfits() != null) this.acquaintanceUnlockOutfits = seed.acquaintanceUnlockOutfits();
        if (seed.acquaintanceUnlockLocations() != null) this.acquaintanceUnlockLocations = seed.acquaintanceUnlockLocations();
        if (seed.friendUnlockOutfits() != null) this.friendUnlockOutfits = seed.friendUnlockOutfits();
        if (seed.friendUnlockLocations() != null) this.friendUnlockLocations = seed.friendUnlockLocations();
        if (seed.loverUnlockOutfits() != null) this.loverUnlockOutfits = seed.loverUnlockOutfits();
        if (seed.loverUnlockLocations() != null) this.loverUnlockLocations = seed.loverUnlockLocations();
        if (seed.outfitDescriptions() != null) this.outfitDescriptions = seed.outfitDescriptions();
        if (seed.endingRoleDesc() != null) this.endingRoleDesc = seed.endingRoleDesc();
        if (seed.endingQuoteHappy() != null) this.endingQuoteHappy = seed.endingQuoteHappy();
        if (seed.endingQuoteBad() != null) this.endingQuoteBad = seed.endingQuoteBad();
        if (seed.introNarration() != null) this.introNarration = seed.introNarration();
        if (seed.firstGreeting() != null) this.firstGreeting = seed.firstGreeting();
    }

    // ── 편의 메서드: 시크릿 모드 성격/말투 (폴백) ──

    public String getEffectivePersonality(boolean isSecretMode) {
        if (isSecretMode && personalitySecret != null && !personalitySecret.isBlank()) {
            return personalitySecret;
        }
        return personality != null ? personality : "다정하고 따뜻한 성격";
    }

    public String getEffectiveTone(boolean isSecretMode) {
        if (isSecretMode && toneSecret != null && !toneSecret.isBlank()) {
            return toneSecret;
        }
        return tone != null ? tone : "따뜻한 말투";
    }

    public String getEffectiveOocExample() {
        return oocExample != null ? oocExample
            : name + "은(는) 그런 어려운 말은 잘 몰라요...";
    }

    public String getEffectiveDefaultOutfit() {
        return defaultOutfit != null ? defaultOutfit : "MAID";
    }

    public String getEffectiveDefaultLocation() {
        return defaultLocation != null ? defaultLocation : "ENTRANCE";
    }

    public String getEffectiveRole() {
        return role != null ? role : name;
    }

    public String getEffectiveEndingRoleDesc() {
        return endingRoleDesc != null ? endingRoleDesc : "a character in a visual novel";
    }

    public String getEffectiveEndingQuoteHappy() {
        return endingQuoteHappy != null ? endingQuoteHappy
            : "당신과의 모든 순간이, " + name + "에겐 기적이었어요.";
    }

    public String getEffectiveEndingQuoteBad() {
        return endingQuoteBad != null ? endingQuoteBad
            : "그 분이 처음 문을 열었을 때의 온기가... 아직도 손끝에 남아 있습니다.";
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4 Fix] 캐릭터별 복장/장소 해금 로직
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private Set<String> parseCommaSet(String csv) {
        if (csv == null || csv.isBlank()) return new LinkedHashSet<>();
        Set<String> set = new LinkedHashSet<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim().toUpperCase();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }

    /**
     * 현재 관계에서 허용되는 복장 목록
     * @param status  현재 관계 레벨
     * @param isSecret  시크릿 모드 여부 (true면 전체 해금)
     */
    public Set<String> getAllowedOutfits(RelationStatus status, boolean isSecret) {
        Set<String> all = getAllOutfits();
        if (isSecret) return all;

        Set<String> allowed = new LinkedHashSet<>(getBaseOutfitSet());
        if (status.ordinal() >= RelationStatus.ACQUAINTANCE.ordinal()) {
            allowed.addAll(parseCommaSet(acquaintanceUnlockOutfits));
        }
        if (status.ordinal() >= RelationStatus.FRIEND.ordinal()) {
            allowed.addAll(parseCommaSet(friendUnlockOutfits));
        }
        if (status.ordinal() >= RelationStatus.LOVER.ordinal()) {
            allowed.addAll(parseCommaSet(loverUnlockOutfits));
        }
        return allowed;
    }

    /**
     * 현재 관계에서 허용되는 장소 목록
     */
    public Set<String> getAllowedLocations(RelationStatus status, boolean isSecret) {
        Set<String> all = getAllLocations();
        if (isSecret) return all;

        Set<String> allowed = new LinkedHashSet<>(getBaseLocationSet());
        if (status.ordinal() >= RelationStatus.ACQUAINTANCE.ordinal()) {
            allowed.addAll(parseCommaSet(acquaintanceUnlockLocations));
        }
        if (status.ordinal() >= RelationStatus.FRIEND.ordinal()) {
            allowed.addAll(parseCommaSet(friendUnlockLocations));
        }
        if (status.ordinal() >= RelationStatus.LOVER.ordinal()) {
            allowed.addAll(parseCommaSet(loverUnlockLocations));
        }
        return allowed;
    }

    /** 이 캐릭터의 전체 복장 풀 (모든 해금 포함) */
    public Set<String> getAllOutfits() {
        Set<String> all = new LinkedHashSet<>(getBaseOutfitSet());
        all.addAll(parseCommaSet(acquaintanceUnlockOutfits));
        all.addAll(parseCommaSet(friendUnlockOutfits));
        all.addAll(parseCommaSet(loverUnlockOutfits));
        return all;
    }

    /** 이 캐릭터의 전체 장소 풀 (모든 해금 포함) */
    public Set<String> getAllLocations() {
        Set<String> all = new LinkedHashSet<>(getBaseLocationSet());
        all.addAll(parseCommaSet(acquaintanceUnlockLocations));
        all.addAll(parseCommaSet(friendUnlockLocations));
        all.addAll(parseCommaSet(loverUnlockLocations));
        return all;
    }

    public Set<String> getBaseOutfitSet() {
        Set<String> set = parseCommaSet(baseOutfits);
        if (set.isEmpty()) set.add(getEffectiveDefaultOutfit());
        return set;
    }

    public Set<String> getBaseLocationSet() {
        Set<String> set = parseCommaSet(baseLocations);
        if (set.isEmpty()) set.add(getEffectiveDefaultLocation());
        return set;
    }

    /**
     * 특정 관계로 승급할 때 새로 해금되는 콘텐츠 목록
     */
    public record UnlockInfo(String type, String name, String displayName) {}

    public List<UnlockInfo> getUnlocksForRelation(RelationStatus relation) {
        List<UnlockInfo> unlocks = new ArrayList<>();
        String unlockOutfits = switch (relation) {
            case ACQUAINTANCE -> acquaintanceUnlockOutfits;
            case FRIEND -> friendUnlockOutfits;
            case LOVER -> loverUnlockOutfits;
            default -> null;
        };
        String unlockLocations = switch (relation) {
            case ACQUAINTANCE -> acquaintanceUnlockLocations;
            case FRIEND -> friendUnlockLocations;
            case LOVER -> loverUnlockLocations;
            default -> null;
        };

        if (unlockLocations != null) {
            for (String loc : parseCommaSet(unlockLocations)) {
                unlocks.add(new UnlockInfo("LOCATION", loc, loc));
            }
        }
        if (unlockOutfits != null) {
            for (String outfit : parseCommaSet(unlockOutfits)) {
                unlocks.add(new UnlockInfo("OUTFIT", outfit, outfit));
            }
        }
        return unlocks;
    }

    /**
     * 프롬프트용 복장 설명 블록 빌더
     * outfitDescriptions 필드에서 현재 해금된 복장만 필터링하여 반환
     */
    public String buildOutfitDescriptionsForPrompt(RelationStatus status, boolean isSecret) {
        if (outfitDescriptions == null || outfitDescriptions.isBlank()) return "";

        Set<String> allowed = getAllowedOutfits(status, isSecret);
        StringBuilder sb = new StringBuilder();
        for (String line : outfitDescriptions.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx <= 0) continue;
            String outfitKey = trimmed.substring(0, colonIdx).trim().toUpperCase();
            if (allowed.contains(outfitKey)) {
                sb.append("- ").append(trimmed).append("\n");
            }
        }
        return sb.toString();
    }
}