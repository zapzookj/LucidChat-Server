package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * [Story V2] World Location 시드 설정.
 *
 * <p>application.yml 예시:
 * <pre>
 * app:
 *   v2-seeds:
 *     locations:
 *       - world-id: MEDIEVAL_FANTASY
 *         location-key: CATHEDRAL
 *         display-name: 대성당
 *         description: 영지 중앙에 자리한 거대한 고딕 양식의 대성당
 *         display-order: 1
 *         selectable-as-start: true
 *         active: true
 *       - world-id: MEDIEVAL_FANTASY
 *         location-key: GARDEN
 *         display-name: 정원
 *         description: 성당 뒤편의 비밀스러운 작은 정원
 *         display-order: 2
 *         selectable-as-start: true
 *         active: true
 * </pre>
 *
 * <p>각 시드는 (worldId, locationKey) 조합으로 unique. 동일 키 재실행 시 update.
 */
@ConfigurationProperties(prefix = "app.v2")
public record WorldLocationSeedProperties(
    List<LocationSeed> locations
) {

    public record LocationSeed(
        /** WorldId enum 문자열 (예: "MEDIEVAL_FANTASY"). */
        String worldId,

        /** 정규 키 (예: "CATHEDRAL", "GARDEN"). 매 World 안에서 unique. */
        String locationKey,

        /** UI 표시명. */
        String displayName,

        /** 1~2문장 묘사. */
        String description,

        /** 장소 기본 BGM 모드 (예: "DAILY", "TOUCHING", "ROMANTIC"). null 허용. */
        String defaultBgm,

        /** UI 정렬 순서. null이면 0. */
        Integer displayOrder,

        /** StoryCreateFlow의 시작 장소 풀에 노출할지. null이면 true. */
        Boolean selectableAsStart,

        /** 비활성 시드는 시더에서 skip. null이면 true. */
        Boolean active
    ) {}
}