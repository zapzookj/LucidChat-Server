package com.spring.aichat.domain.character;

import com.spring.aichat.config.CharacterSeedProperties;
import com.spring.aichat.domain.enums.RelationStatus;
import com.spring.aichat.domain.enums.WorldId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * AI 캐릭터 메타정보 엔티티
 *
 * [Phase 4.5] 로비 표시용 필드 추가
 * [Phase 5]   멀티캐릭터 확장 — 프롬프트/에셋 필드 분리
 * [Phase 5.5-Theater] Theater 모드 소속 세계관, 영역 장소, Theater 가용 플래그 추가
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
 *   ▸ [Theater] worldId:            소속 세계관 (nullable, Theater 미지원 캐릭터는 null)
 *   ▸ [Theater] homeLocations:      영역 장소 (줄 구분, Theater 장소 선택 분기용)
 *   ▸ [Theater] theaterAvailable:   Theater 모드 지원 여부
 *   ▸ [Theater] theaterIntroBeat:   Theater Act 1 첫 만남 씬 시드 (TEXT)
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "characters")
public class Character {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "character_id")
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

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

    @Column(name = "role", length = 100)
    private String role;

    @Column(name = "age", length = 100)
    private Integer age;

    @Column(name = "personality", columnDefinition = "TEXT")
    private String personality;

    @Column(name = "personality_secret", columnDefinition = "TEXT")
    private String personalitySecret;

    @Column(name = "tone", length = 300)
    private String tone;

    @Column(name = "tone_secret", length = 300)
    private String toneSecret;

    @Column(name = "ooc_example", columnDefinition = "TEXT")
    private String oocExample;

    @Column(name = "story_behavior_guide", columnDefinition = "TEXT")
    private String storyBehaviorGuide;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 6 도그푸딩 #3] 캐릭터 영혼 필드 — Tier 1 스키마 확장
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  기존 personality/tone/oocExample/storyBehaviorGuide는 캐릭터를 *부분적으로*만 정의.
    //  도그푸딩 결과: 시스템 프롬프트의 90~95%가 게임 시스템, 캐릭터 정체성은 5~10%에 불과 →
    //  LLM의 RLHF 기본 성향(유저 만족 우선)이 빈 공간을 채워 "유저바라기" 현상.
    //  아래 5개 필드는 *살아있는 영혼*을 위한 콘텐츠 슬롯이며 nullable — 콘텐츠 작성은 별도 진행.

    /** 캐릭터 외모 설명. */
    @Column(name = "appearance", columnDefinition = "TEXT")
    private String appearance;

    /** 캐릭터 복장 설명. */
    @Column(name = "clothing", columnDefinition = "TEXT")
    private String clothing;

    /** 캐릭터 과거사 (3~5문단). 어떤 사건이 지금의 가치관을 형성했는가. */
    @Column(name = "backstory", columnDefinition = "TEXT")
    private String backstory;

    /** 가치관/철학 (구체적 5~7개 bullet). 무엇을 옳다/그르다 여기는가. */
    @Column(name = "core_values", columnDefinition = "TEXT")
    private String coreValues;

    /** 약점·두려움·모순 (3~5개 bullet). 살아있는 사람의 결. */
    @Column(name = "flaws", columnDefinition = "TEXT")
    private String flaws;

    /** 어휘 습관·말버릇 (구체 예시 포함). tone보다 한 단계 더 구체적. */
    @Column(name = "speech_quirks", columnDefinition = "TEXT")
    private String speechQuirks;

    @Column(name = "promotion_scenarios", columnDefinition = "TEXT")
    private String promotionScenarios;

    @Column(name = "easter_egg_dialogue", columnDefinition = "TEXT")
    private String easterEggDialogue;

    @Column(name = "default_outfit", length = 30)
    private String defaultOutfit;

    @Column(name = "default_location", length = 30)
    private String defaultLocation;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4 Fix] 캐릭터별 복장/장소 독립 세계관
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "base_outfits", length = 500)
    private String baseOutfits;

    @Column(name = "base_locations", length = 500)
    private String baseLocations;

    @Column(name = "acquaintance_unlock_outfits", length = 200)
    private String acquaintanceUnlockOutfits;

    @Column(name = "acquaintance_unlock_locations", length = 200)
    private String acquaintanceUnlockLocations;

    @Column(name = "friend_unlock_outfits", length = 200)
    private String friendUnlockOutfits;

    @Column(name = "friend_unlock_locations", length = 200)
    private String friendUnlockLocations;

    @Column(name = "lover_unlock_outfits", length = 200)
    private String loverUnlockOutfits;

    @Column(name = "lover_unlock_locations", length = 200)
    private String loverUnlockLocations;

    @Column(name = "outfit_descriptions", columnDefinition = "TEXT")
    private String outfitDescriptions;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5] 엔딩 프롬프트용 필드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Theater] Theater 모드 관련 필드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 소속 세계관 (Theater 미지원 캐릭터는 null) */
    @Enumerated(EnumType.STRING)
    @Column(name = "world_id", length = 50)
    private WorldId worldId;

    /** Theater 모드 지원 여부 */
    @Column(name = "theater_available", nullable = false)
    private boolean theaterAvailable = false;

    /** [Phase 6] 관리자 전역 숨김(로비/목록에서 즉시 비노출). 시드(YAML)가 건드리지 않아 admin 편집이 보존됨. */
    @Column(name = "hidden", nullable = false)
    private boolean hidden = false;

    /**
     * 영역 장소 — Theater의 Act 초입 장소 선택 분기에서 사용
     * 줄 단위 구분: 한 줄 = 하나의 장소
     * 예: "대학교 미술 동아리실\n근처 카페\n자취방"
     */
    @Column(name = "home_locations", columnDefinition = "TEXT")
    private String homeLocations;

    /**
     * Theater Act 1 첫 만남 시드 나레이션
     * LLM이 이 씬을 기반으로 Act 1 첫 Chapter를 전개함
     */
    @Column(name = "theater_intro_beat", columnDefinition = "TEXT")
    private String theaterIntroBeat;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  생성자 & 시드 적용
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public Character(String name, String slug, String baseSystemPrompt, String llmModelName) {
        this.name = name;
        this.slug = slug;
        this.baseSystemPrompt = baseSystemPrompt;
        this.llmModelName = llmModelName;
    }

    public void applySeed(CharacterSeedProperties.CharacterSeed seed) {
        this.name = seed.name();
        this.slug = seed.slug();
        this.baseSystemPrompt = seed.baseSystemPrompt();
        this.llmModelName = seed.llmModelName();
        this.ttsVoiceId = seed.ttsVoiceId();
        this.defaultImageUrl = seed.defaultImageUrl();

        if (seed.tagline() != null) this.tagline = seed.tagline();
        if (seed.thumbnailUrl() != null) this.thumbnailUrl = seed.thumbnailUrl();
        if (seed.description() != null) this.description = seed.description();
        if (seed.storyAvailable() != null) this.storyAvailable = seed.storyAvailable();

        if (seed.role() != null) this.role = seed.role();
        if (seed.personality() != null) this.personality = seed.personality();
        if (seed.personalitySecret() != null) this.personalitySecret = seed.personalitySecret();
        if (seed.tone() != null) this.tone = seed.tone();
        if (seed.toneSecret() != null) this.toneSecret = seed.toneSecret();
        if (seed.oocExample() != null) this.oocExample = seed.oocExample();
        if (seed.storyBehaviorGuide() != null) this.storyBehaviorGuide = seed.storyBehaviorGuide();
        // [Phase 6 도그푸딩 #3] 영혼 필드
        if (seed.backstory() != null) this.backstory = seed.backstory();
        if (seed.coreValues() != null) this.coreValues = seed.coreValues();
        if (seed.flaws() != null) this.flaws = seed.flaws();
        if (seed.speechQuirks() != null) this.speechQuirks = seed.speechQuirks();
        if (seed.promotionScenarios() != null) this.promotionScenarios = seed.promotionScenarios();
        if (seed.easterEggDialogue() != null) this.easterEggDialogue = seed.easterEggDialogue();
        if (seed.defaultOutfit() != null) this.defaultOutfit = seed.defaultOutfit();
        if (seed.defaultLocation() != null) this.defaultLocation = seed.defaultLocation();
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

        // [Phase 5.5-Theater]
        if (seed.worldId() != null) {
            this.worldId = WorldId.fromStringOrNull(seed.worldId());
        }
        if (seed.theaterAvailable() != null) this.theaterAvailable = seed.theaterAvailable();
        if (seed.homeLocations() != null) this.homeLocations = seed.homeLocations();
        if (seed.theaterIntroBeat() != null) this.theaterIntroBeat = seed.theaterIntroBeat();
    }

    // ── 기존 편의 메서드 ──

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

    public Set<String> getAllOutfits() {
        Set<String> all = new LinkedHashSet<>(getBaseOutfitSet());
        all.addAll(parseCommaSet(acquaintanceUnlockOutfits));
        all.addAll(parseCommaSet(friendUnlockOutfits));
        all.addAll(parseCommaSet(loverUnlockOutfits));
        return all;
    }

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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Theater] Theater 전용 편의 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Theater 장소 선택 분기용 영역 장소 리스트
     * 줄 구분 파싱. 비어있으면 defaultLocation만 반환.
     */
    public List<String> getHomeLocationList() {
        if (homeLocations == null || homeLocations.isBlank()) {
            String defaultLoc = getEffectiveDefaultLocation();
            List<String> fallback = new ArrayList<>();
            fallback.add(defaultLoc);
            return fallback;
        }
        List<String> list = new ArrayList<>();
        for (String line : homeLocations.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) list.add(trimmed);
        }
        return list.isEmpty() ? List.of(getEffectiveDefaultLocation()) : list;
    }

    public boolean isTheaterAvailable() {
        return theaterAvailable && worldId != null;
    }

    public String getEffectiveTheaterIntroBeat() {
        return theaterIntroBeat != null ? theaterIntroBeat
            : name + "과의 첫 만남. 운명이 갈리는 순간이 다가온다.";
    }

    // [Phase 6] 관리자 노출 토글 — 좁은 도메인 뮤테이터.
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public void setStoryAvailable(boolean storyAvailable) { this.storyAvailable = storyAvailable; }
    public void setTheaterAvailable(boolean theaterAvailable) { this.theaterAvailable = theaterAvailable; }

    /** 관리 용도: Theater 메타데이터 수동 업데이트 */
    public void updateTheaterMetadata(WorldId worldId, boolean theaterAvailable,
                                      String homeLocations, String theaterIntroBeat) {
        this.worldId = worldId;
        this.theaterAvailable = theaterAvailable;
        this.homeLocations = homeLocations;
        this.theaterIntroBeat = theaterIntroBeat;
    }
}