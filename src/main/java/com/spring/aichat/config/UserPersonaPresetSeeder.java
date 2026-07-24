package com.spring.aichat.config;

import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.world.UserPersonaPreset;
import com.spring.aichat.domain.world.UserPersonaPresetRepository;
import com.spring.aichat.domain.world.WorldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * [Story V2] User Persona Preset 시더.
 *
 * <p>실행 순서: {@code @Order(2)} — WorldSeeder(0) + WorldLocationSeeder(1) 직후.
 * <p>업서트 정책: (worldId, presetKey) 기준 unique. 동일 키 재실행 시 update.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
@Order(2)
public class UserPersonaPresetSeeder {

    private final UserPersonaPresetRepository presetRepository;
    private final WorldRepository worldRepository;
    private final UserPersonaPresetSeedProperties seedProperties;

    @Value("${app.seed.update-existing:true}")
    private boolean updateExisting;

    @Bean
    @Order(2)
    public ApplicationRunner seedPersonaPresetsRunner() {
        return args -> seedAllPresets();
    }

    @Transactional
    public void seedAllPresets() {
        List<UserPersonaPresetSeedProperties.PersonaPresetSeed> seeds = seedProperties.personaPresets();
        if (seeds == null || seeds.isEmpty()) {
            log.warn("⚠️ [PERSONA-SEED] No persona preset seeds configured");
            return;
        }

        int created = 0, updated = 0, skipped = 0;

        for (UserPersonaPresetSeedProperties.PersonaPresetSeed seed : seeds) {
            WorldId worldId;
            try {
                worldId = WorldId.valueOf(seed.worldId());
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("⚠️ [PERSONA-SEED] Invalid worldId '{}' — skip preset '{}'",
                    seed.worldId(), seed.presetKey());
                skipped++;
                continue;
            }

            if (!worldRepository.findById(worldId).isPresent()) {
                log.warn("⚠️ [PERSONA-SEED] World '{}' not found — skip preset '{}'",
                    worldId, seed.presetKey());
                skipped++;
                continue;
            }

            if (seed.presetKey() == null || seed.presetKey().isBlank()) {
                log.warn("⚠️ [PERSONA-SEED] Empty preset_key for world '{}' — skip", worldId);
                skipped++;
                continue;
            }

            String presetKey = seed.presetKey().trim();
            String name = nullToEmpty(seed.name());
            String description = nullToEmpty(seed.description());
            String defaultNickname = nullToEmpty(seed.defaultNickname());
            String suggestedStartLocationKey = nullToEmpty(seed.suggestedStartLocationKey());
            int displayOrder = seed.displayOrder() == null ? 0 : seed.displayOrder();
            boolean active = seed.active() == null || seed.active();

            Optional<UserPersonaPreset> existing =
                presetRepository.findByWorldIdAndPresetKey(worldId, presetKey);
            if (existing.isPresent()) {
                if (updateExisting) {
                    UserPersonaPreset p = existing.get();
                    p.update(name, description, defaultNickname, suggestedStartLocationKey, displayOrder);
                    p.setActive(active);
                    presetRepository.save(p); // [Fix 2026-07-24] 갱신 영속화(자기호출 @Transactional 우회 대비)
                    log.info("🔄 [PERSONA-SEED] Preset updated: {}/{}", worldId, presetKey);
                    updated++;
                } else {
                    log.debug("⏭️ [PERSONA-SEED] Preset exists, skip: {}/{}", worldId, presetKey);
                    skipped++;
                }
            } else {
                UserPersonaPreset fresh = UserPersonaPreset.create(
                    worldId, presetKey, name, description, defaultNickname,
                    suggestedStartLocationKey, displayOrder);
                if (!active) fresh.setActive(false);
                presetRepository.save(fresh);
                log.info("✅ [PERSONA-SEED] Preset created: {}/{} ({})", worldId, presetKey, name);
                created++;
            }
        }

        log.info("👤 [PERSONA-SEED] Complete: {} created, {} updated, {} skipped (total {} configured)",
            created, updated, skipped, seeds.size());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}