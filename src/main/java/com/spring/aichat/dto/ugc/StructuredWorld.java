package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * [UGC 세계관 빌더] W0 산출 — LLM 세계관 구조화 결과.
 *
 * <p>{@link StructuredConcept}와 동일 원칙: 유저 자유 텍스트는 여기서 끝난다 —
 * 배경/썸네일 이미지 프롬프트는 이 구조화 산출({@code backgroundPrompt}/{@code thumbnailPrompt})만으로
 * 서버 상수와 조립된다. JSON 키는 snake_case.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StructuredWorld(
    WorldProfile world,
    /** 대표 장소 제안 — 기본 6개 (유저 편집 상한 10은 W1에서). */
    List<LocationSuggestion> locations,
    /** 월드 대표 썸네일 생성 프롬프트 (영문, 사람 없음). */
    @JsonDeserialize(using = FlexibleStringDeserializer.class)
    String thumbnailPrompt,
    Moderation moderation
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorldProfile(
        String name,
        /** 카드/셀렉터용 짧은 소개 (한국어). */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String intro,
        /** 채팅 시스템 프롬프트 주입용 설정 본문 (한국어 4~8문장). */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String lore,
        /** 무드 키워드 3~5개 — 저장 시 콤마 결합(World.moodKeywords 관례). */
        List<String> moodTags
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LocationSuggestion(
        /** 영문 SCREAMING_SNAKE_CASE 고유 키 (서버가 재정규화·중복 해소). */
        String locationKey,
        String displayName,
        /** 한국어 1~2문장 분위기 설명 — 채팅 프롬프트 장소 풀 주입용. */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String description,
        /** 영문 배경 일러 묘사 1~3문장 (사람 없음 — flux-2 positive 전용). */
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String backgroundPrompt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Moderation(boolean minorSignal, String reason) {}
}
