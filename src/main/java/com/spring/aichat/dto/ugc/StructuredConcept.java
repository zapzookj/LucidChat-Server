package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

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
    Moderation moderation
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
        String introNarration
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Moderation(boolean minorSignal, String reason) {}
}
