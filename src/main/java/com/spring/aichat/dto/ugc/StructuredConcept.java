package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * [UGC v1] Stage 0 산출 — LLM 컨셉 구조화 결과.
 *
 * <p>불변 원칙: 유저 자유 텍스트는 절대 이미지 프롬프트에 직결되지 않는다.
 * 이미지 프롬프트는 이 구조화 산출의 태그만으로 서버가 조립한다.
 *
 * <p>JSON 키는 snake_case (appearance_tags, core_values, ...).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StructuredConcept(
    /** Danbooru 관례 외형 태그 40~60개 (영문 소문자, 씬·조명·구도 금지). */
    List<String> appearanceTags,
    /** [2026-07-20] 성격·무드 태그 5~8개 (영문 — kuudere, cold beauty 등). 감정 파생 연출 개성화 + 이미지 positive 무드. */
    List<String> personaTags,
    /** 황금샷 연출 태그 10~20개 (배경, 소품, 조명, 구도 — WF-1 전용). */
    List<String> sceneTags,
    /** 누끼 대비 배경색 — §4 팔레트 중 1개. */
    String bgColor,
    CharacterProfile character,
    Moderation moderation,
    /**
     * [2026-07-21 컨셉 반영 자세] 캐릭터 기본 스탠딩 자세 묘사 (영문 1~2문장 — Qwen 패스1 슬롯).
     * null이면 서버 기본 스탠스 폴백. 카메라 거리·앵글 변경 금지는 서버 템플릿 가드가 강제.
     */
    @JsonDeserialize(using = FlexibleStringDeserializer.class)
    String basePose,
    /**
     * [2026-07-21 컨셉 반영 감정] EmotionTag명 → 캐릭터별 동적 표정·자세 (감정 스테이지 진입 시
     * 별도 LLM 콜 산출 — Stage0 산출엔 없음). null/누락 감정은 서버 상수 폴백. 리롤 재현성 위해 잡에 저장.
     */
    Map<String, EmotionPromptOverride> emotionPrompts
) {

    /**
     * 장문 필드에는 {@link FlexibleStringDeserializer} 적용 — LLM이 bullet 요구 필드를
     * JSON 배열로 반환하는 케이스 실측(2026-07-20)에 대한 관용 수용.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CharacterProfile(
        String name,
        String tagline,
        Integer age,
        String role,
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String personality,
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String tone,
        /** 외형 한국어 서술 (Character.appearance 슬롯). */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String appearance,
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String clothing,
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String backstory,
        /** 가치관 bullet 5~7개 — V1 프롬프트 어셈블러 영혼 필드 (2026-07-17 스키마 확장 결정). */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String coreValues,
        /** 약점·모순 bullet 3~5개 — 영혼 필드. */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String flaws,
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String speechQuirks,
        /** 첫인사 '대사'만 — 순수 발화 평문 (마크다운/괄호 지문/따옴표 금지, 후처리로 이중 방어). */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String firstGreeting,
        /** 첫 만남 장면 묘사 2~3문장 (관찰자 시점) — SYSTEM 나레이션 채널(공식 캐릭터 intro-narration과 동일). */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String introNarration,
        // ── [2026-07-22 프로필 뷰] 몰입형 신상 4종 (구버전 JSON은 null — "기록 없음" 처리) ──
        /** 키 — "164cm" 형식 짧은 문자열. */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String height,
        /** 좋아하는 것 — 짧은 구 1~2개, 콤마 구분. */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String likes,
        /** 싫어하는 것 — 짧은 구 1~2개, 콤마 구분. */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String dislikes,
        /** 취미 — 짧은 구 1~2개. */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String hobby,
        /** 프로필 카드 전용 한 줄 문장 — 자기소개 또는 명대사 1문장. */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String profileQuote
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Moderation(boolean minorSignal, String reason) {}

    /** [2026-07-21] 캐릭터별 동적 감정 프롬프트 — 표정(필수 성격)·상반신 제스처. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmotionPromptOverride(
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String expression,
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String pose
    ) {}

    /** 해당 감정의 동적 프롬프트 (없으면 null — 호출측 상수 폴백). */
    public EmotionPromptOverride emotionPromptFor(String emotionName) {
        return emotionPrompts == null ? null : emotionPrompts.get(emotionName);
    }

    /** 감정 스테이지 산출 병합 — 잡 스크래치 재기록용 카피. */
    public StructuredConcept withEmotionPrompts(Map<String, EmotionPromptOverride> prompts) {
        return new StructuredConcept(appearanceTags, personaTags, sceneTags, bgColor,
            character, moderation, basePose, prompts);
    }

    /**
     * [2026-07-21 리롤 외형 수정] 외형 산출 병합 — 외형 관련 필드(태그·씬·배경색·외형/복장 서술·
     * moderation)만 {@code source}에서 가져오고, 페르소나·서사·첫인사 등 나머지는 <b>this(최신본)</b>를
     * 유지한다. 락 안에서 최신 concept에 대해 호출해야 동시 프로필 편집의 lost update가 없다.
     */
    public StructuredConcept withAppearanceFrom(StructuredConcept source) {
        CharacterProfile p = this.character;
        CharacterProfile sp = source.character();
        CharacterProfile merged = new CharacterProfile(
            p.name(), p.tagline(), p.age(), p.role(), p.personality(), p.tone(),
            sp != null && sp.appearance() != null && !sp.appearance().isBlank() ? sp.appearance() : p.appearance(),
            sp != null && sp.clothing() != null && !sp.clothing().isBlank() ? sp.clothing() : p.clothing(),
            p.backstory(), p.coreValues(), p.flaws(), p.speechQuirks(),
            p.firstGreeting(), p.introNarration(),
            p.height(), p.likes(), p.dislikes(), p.hobby(), p.profileQuote());
        return new StructuredConcept(source.appearanceTags(), personaTags, source.sceneTags(),
            source.bgColor(), merged, source.moderation(), basePose, emotionPrompts);
    }
}
