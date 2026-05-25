package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * [Story V2] User Persona Preset 시드 설정.
 *
 * <p>application.yml 예시:
 * <pre>
 * app:
 *   v2-seeds:
 *     persona-presets:
 *       - world-id: MEDIEVAL_FANTASY
 *         preset-key: YOUNG_LORD
 *         name: 젊은 영주
 *         description: 새로 영지를 물려받은 21세의 청년...
 *         default-nickname: 영주님
 *         suggested-start-location-key: CATHEDRAL
 *         display-order: 1
 *         active: true
 * </pre>
 */
@ConfigurationProperties(prefix = "app.v2.persona-presets")
public record UserPersonaPresetSeedProperties(
    List<PersonaPresetSeed> personaPresets
) {

    public record PersonaPresetSeed(
        String worldId,
        String presetKey,
        String name,
        String description,
        String defaultNickname,
        String suggestedStartLocationKey,
        Integer displayOrder,
        Boolean active
    ) {}
}