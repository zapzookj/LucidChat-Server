package com.spring.aichat.domain.character;

import com.spring.aichat.config.CharacterSeedProperties;
import com.spring.aichat.domain.enums.CharacterSource;
import com.spring.aichat.domain.enums.CharacterVisibility;
import com.spring.aichat.domain.enums.RelationStatus;
import com.spring.aichat.domain.enums.SecretReviewStatus;
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
    //  [UGC v1] 소유·공개·Secret 심사 (additive only — V1 무회귀)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 생성자 유저 (null = 공식 캐릭터). 의도적 FK 미설정(Long 참조 — V2 도메인 관례). */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private CharacterSource source = CharacterSource.OFFICIAL;

    /**
     * Secret Mode 허용 여부 — 런타임 fast-path.
     * 공식 캐릭터는 true(V9 마이그레이션에서 일괄 설정), UGC는 승인 전 false.
     */
    @Column(name = "secret_eligible", nullable = false)
    private boolean secretEligible = false;

    /** 접근 규칙: PUBLIC은 전체, 그 외는 소유자만. PENDING_PUBLIC 동작은 PRIVATE와 동일. */
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private CharacterVisibility visibility = CharacterVisibility.PUBLIC;

    /** Secret 허용 심사 상태 — 공개 심사와 독립 경로. */
    @Enumerated(EnumType.STRING)
    @Column(name = "secret_review_status", nullable = false, length = 20)
    private SecretReviewStatus secretReviewStatus = SecretReviewStatus.NONE;

    /** 마지막 심사(공개/Secret) 반려·승인 사유. */
    @Column(name = "review_note", length = 500)
    private String reviewNote;

    /**
     * [UGC 세계관 빌더] 소속 UGC 월드 (FK 미설정 Long 참조 — {@code UgcWorld}).
     * 공식 연결은 기존 {@code worldId}(enum) 그대로 — 두 컬럼은 <b>배타적</b>(앱 레벨 XOR 가드).
     * 시드({@code applySeed})가 건드리지 않아 유저 연결이 보존된다(hidden 필드와 동일 관례).
     */
    @Column(name = "ugc_world_id")
    private Long ugcWorldId;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2026-07-22 프로필 뷰] 몰입형 신상 정보 (전부 선택 — 없으면 "기록 없음" 처리)
    //  UGC는 Stage0 산출, 공식은 시드 수기 입력. 프롬프트 원문과 달리 유저에게 그대로 노출된다.
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 키 — "164cm" 형식 짧은 문자열. */
    @Column(name = "height", length = 30)
    private String height;

    /** 좋아하는 것 — 짧은 구, 콤마 구분. */
    @Column(name = "likes", length = 200)
    private String likes;

    /** 싫어하는 것 — 짧은 구, 콤마 구분. */
    @Column(name = "dislikes", length = 200)
    private String dislikes;

    /** 취미 — 짧은 구. */
    @Column(name = "hobby", length = 200)
    private String hobby;

    /** 무드 태그 칩 — 콤마 구분 (UGC: persona 태그 조인 저장 · 공식: 시드 입력). */
    @Column(name = "mood_tags", length = 200)
    private String moodTags;

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

        // [2026-07-22 프로필 뷰] 몰입형 신상 — 공식 캐릭터는 시드 수기 입력
        if (seed.height() != null) this.height = seed.height();
        if (seed.likes() != null) this.likes = seed.likes();
        if (seed.dislikes() != null) this.dislikes = seed.dislikes();
        if (seed.hobby() != null) this.hobby = seed.hobby();
        if (seed.moodTags() != null) this.moodTags = seed.moodTags();

        // [UGC v1] YAML 시드 = 공식 캐릭터 불변식 (신규 시드에도 Secret 허용 보장 — V9 일괄 UPDATE와 동일 의미)
        this.source = CharacterSource.OFFICIAL;
        this.visibility = CharacterVisibility.PUBLIC;
        this.secretEligible = true;
        this.ownerUserId = null;
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [UGC v1] 생성·접근·심사
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** UGC 캐릭터 생성 스펙 — Stage 0 산출 + 에셋 바인딩 결과를 도메인으로 전달하는 경계 레코드. */
    public record UgcCharacterSpec(
        Long ownerUserId,
        String name,
        String slug,
        String baseSystemPrompt,
        String llmModelName,
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
        /** 첫 만남 장면 묘사 — SYSTEM 나레이션 채널 (공식 intro-narration과 동일 렌더 경로). */
        String introNarration,
        String defaultImageUrl,
        String thumbnailUrl,
        String defaultOutfit,
        WorldId worldId,
        /** [세계관 빌더] UGC 월드 연결 (worldId와 배타 — 위저드 3택 중 '내 커스텀 월드'). */
        Long ugcWorldId,
        // ── [2026-07-22 프로필 뷰] 몰입형 신상 (Stage0 산출 — 전부 선택) ──
        String height,
        String likes,
        String dislikes,
        String hobby,
        /** 무드 태그 CSV — persona 태그 조인. */
        String moodTags
    ) {}

    /**
     * UGC 캐릭터 바인딩 (Stage 4).
     * 불변식: source=UGC · visibility=PRIVATE(공개는 승인제) · secretEligible=false(승인 전 차단) ·
     * storyAvailable=false · theaterAvailable=false (v1 SANDBOX 전용 — 루틴 데이터는 별도 생성하되 개방은 v1.1).
     */
    public static Character createUgc(UgcCharacterSpec spec) {
        Character c = new Character(spec.name(), spec.slug(), spec.baseSystemPrompt(), spec.llmModelName());
        c.ownerUserId = spec.ownerUserId();
        c.source = CharacterSource.UGC;
        c.visibility = CharacterVisibility.PRIVATE;
        c.secretEligible = false;
        c.secretReviewStatus = SecretReviewStatus.NONE;
        c.storyAvailable = false;
        c.theaterAvailable = false;
        c.hidden = false;

        c.tagline = spec.tagline();
        c.description = spec.description();
        c.role = spec.role();
        c.personality = spec.personality();
        c.tone = spec.tone();
        c.appearance = spec.appearance();
        c.clothing = spec.clothing();
        c.backstory = spec.backstory();
        c.coreValues = spec.coreValues();
        c.flaws = spec.flaws();
        c.speechQuirks = spec.speechQuirks();
        c.firstGreeting = spec.firstGreeting();
        c.introNarration = spec.introNarration();
        c.defaultImageUrl = spec.defaultImageUrl();
        c.thumbnailUrl = spec.thumbnailUrl();
        c.defaultOutfit = spec.defaultOutfit();
        requireWorldXor(spec.worldId(), spec.ugcWorldId());
        c.worldId = spec.worldId();
        c.ugcWorldId = spec.ugcWorldId();
        c.height = spec.height();
        c.likes = spec.likes();
        c.dislikes = spec.dislikes();
        c.hobby = spec.hobby();
        c.moodTags = spec.moodTags();
        return c;
    }

    public boolean isUgc() {
        return source == CharacterSource.UGC;
    }

    public boolean isOwnedBy(Long userId) {
        return ownerUserId != null && ownerUserId.equals(userId);
    }

    /** 접근 규칙: PUBLIC은 전체, 그 외(PRIVATE/PENDING_PUBLIC)는 소유자만. */
    public boolean isAccessibleBy(Long userId) {
        return visibility.isPubliclyVisible() || isOwnedBy(userId);
    }

    // ── [세계관 빌더] 월드 연결/변경 (에셋 무관, 무료 — updateUgcTexts와 동일 정책) ──

    /** 공식 세계관 연결 — UGC 월드 연결은 해제된다(XOR 유지). */
    public void linkOfficialWorld(WorldId worldId) {
        requireUgc();
        this.worldId = worldId;
        this.ugcWorldId = null;
    }

    /** UGC 월드 연결 — 공식 연결은 해제된다(XOR 유지). 승인 게이트는 서비스 계층 책임. */
    public void linkUgcWorld(Long ugcWorldId) {
        requireUgc();
        this.ugcWorldId = ugcWorldId;
        this.worldId = null;
    }

    /** 세계관 연결 해제 ('나중에 연결' 회귀). */
    public void unlinkWorld() {
        requireUgc();
        this.worldId = null;
        this.ugcWorldId = null;
    }

    private static void requireWorldXor(WorldId worldId, Long ugcWorldId) {
        if (worldId != null && ugcWorldId != null) {
            throw new IllegalArgumentException("공식 세계관과 UGC 월드는 동시 연결 불가");
        }
    }

    /** UGC 텍스트 설정 수정 (완성 화면 인라인 수정 — 에셋 무관, 무료). */
    public void updateUgcTexts(String name, String tagline, String personality,
                               String tone, String firstGreeting) {
        if (name != null && !name.isBlank()) this.name = name;
        if (tagline != null) this.tagline = tagline;
        if (personality != null) this.personality = personality;
        if (tone != null) this.tone = tone;
        if (firstGreeting != null) this.firstGreeting = firstGreeting;
    }

    // ── 공개 심사 경로 ──

    public void requestPublish() {
        requireUgc();
        if (visibility == CharacterVisibility.PUBLIC) {
            throw new IllegalStateException("이미 공개된 캐릭터: " + id);
        }
        this.visibility = CharacterVisibility.PENDING_PUBLIC;
    }

    public void cancelPublishRequest() {
        requireUgc();
        if (visibility == CharacterVisibility.PENDING_PUBLIC) {
            this.visibility = CharacterVisibility.PRIVATE;
        }
    }

    /** 승인 큐: 공개 승인 (+선택적 Secret 동시 판정은 approveSecret 별도 호출). */
    public void approvePublish(String note) {
        requireUgc();
        this.visibility = CharacterVisibility.PUBLIC;
        this.reviewNote = note;
    }

    /** 승인 큐: 공개 반려 → PRIVATE 회귀 + 사유. */
    public void rejectPublish(String note) {
        requireUgc();
        this.visibility = CharacterVisibility.PRIVATE;
        this.reviewNote = note;
    }

    // ── Secret 심사 경로 (독립 — PRIVATE 캐릭터도 단독 신청 가능) ──

    public void requestSecretReview() {
        requireUgc();
        if (secretEligible) {
            throw new IllegalStateException("이미 Secret 허용된 캐릭터: " + id);
        }
        this.secretReviewStatus = SecretReviewStatus.PENDING;
    }

    public void approveSecret(String note) {
        requireUgc();
        this.secretEligible = true;
        this.secretReviewStatus = SecretReviewStatus.APPROVED;
        if (note != null) this.reviewNote = note;
    }

    public void rejectSecret(String note) {
        requireUgc();
        this.secretEligible = false;
        this.secretReviewStatus = SecretReviewStatus.REJECTED;
        if (note != null) this.reviewNote = note;
    }

    private void requireUgc() {
        if (!isUgc()) {
            throw new IllegalStateException("공식 캐릭터에 UGC 전용 연산 시도: " + id);
        }
    }
}