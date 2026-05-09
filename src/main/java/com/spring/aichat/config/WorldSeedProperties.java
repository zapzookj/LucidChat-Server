package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * [Polish · World Seed] 세계관 기본 시드 설정
 *
 * 캐릭터 시드(CharacterSeedProperties)와 동일한 패턴으로 application.yml의
 * app.worlds 리스트에서 모든 세계관 시드 데이터를 로드한다.
 *
 * 기존엔 SQL DDL로만 초기 데이터를 채웠지만, CharacterSeeder처럼 코드 기반
 * 업서트 흐름이 필요하다 (신규 환경 부트스트랩, 필드 추가 시 기존 DB 자동 갱신).
 *
 * yml 예시 (resources/application.yml):
 * ```
 * app:
 *   worlds:
 *     - id: MEDIEVAL_FANTASY
 *       display-name: 중세 판타지
 *       tagline: 고딕 저택에서 피어나는 로맨스
 *       description: ...
 *       hero-image-url: /worlds/medieval_fantasy/hero.jpg
 *       thumbnail-url: /worlds/medieval_fantasy/thumb.jpg
 *       opening-narration: ...
 *       default-bgm: TOUCHING
 *       mood-keywords: 고딕,저택,귀족,클래식
 *       secret-allowed: true
 *       active: true
 *       display-order: 1
 *     - id: ORIENTAL_FANTASY
 *       ...
 * ```
 *
 * Camel/Kebab 케이스 변환은 Spring Boot가 자동 처리.
 */
@ConfigurationProperties(prefix = "app")
public record WorldSeedProperties(
    List<WorldSeed> worlds
) {

    /**
     * 단일 세계관 시드.
     *
     * 모든 필드는 nullable. WorldSeeder가 null 값에 대해 적절한 기본값(빈 문자열,
     * false, 0)으로 fallback하여 yml 설정 누락에 강건하게 동작.
     *
     * <p>id는 {@link com.spring.aichat.domain.enums.WorldId} enum의 문자열 값이어야
     * 하며, 매칭되지 않으면 해당 시드는 skip되고 경고 로그가 남는다.
     */
    public record WorldSeed(
        /** WorldId enum 문자열 (MEDIEVAL_FANTASY / ORIENTAL_FANTASY / MODERN_KOREA) */
        String id,
        String displayName,
        String tagline,
        String description,
        String heroImageUrl,
        String thumbnailUrl,
        String openingNarration,
        /** BgmMode enum 문자열 (TOUCHING / DAILY / TENSE / ROMANCE 등) */
        String defaultBgm,
        /** 콤마 구분 키워드 */
        String moodKeywords,
        Boolean secretAllowed,
        Boolean active,
        Integer displayOrder
    ) {}
}