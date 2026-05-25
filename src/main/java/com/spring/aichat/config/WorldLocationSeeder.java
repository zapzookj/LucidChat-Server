package com.spring.aichat.config;

import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.world.WorldLocation;
import com.spring.aichat.domain.world.WorldLocationRepository;
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
 * [Story V2] World Location 시더.
 *
 * <p>실행 순서:
 * <ul>
 *   <li>{@code @Order(1)} — WorldSeeder({@code @Order(0)}) 직후. World FK 보장.</li>
 * </ul>
 *
 * <p>업서트 정책: (worldId, locationKey) 기준 unique. 동일 키 재실행 시 update.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
@Order(1)
public class WorldLocationSeeder {

    private final WorldLocationRepository worldLocationRepository;
    private final WorldRepository worldRepository;
    private final WorldLocationSeedProperties seedProperties;

    @Value("${app.seed.update-existing:true}")
    private boolean updateExisting;

    @Bean
    @Order(1)
    public ApplicationRunner seedWorldLocationsRunner() {
        return args -> seedAllLocations();
    }

    @Transactional
    public void seedAllLocations() {
        List<WorldLocationSeedProperties.LocationSeed> seeds = seedProperties.locations();
        if (seeds == null || seeds.isEmpty()) {
            log.warn("⚠️ [LOC-SEED] No location seeds configured (app.v2-seeds.locations is empty)");
            return;
        }

        int created = 0, updated = 0, skipped = 0;

        for (WorldLocationSeedProperties.LocationSeed seed : seeds) {
            // 1) WorldId enum 검증
            WorldId worldId;
            try {
                worldId = WorldId.valueOf(seed.worldId());
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("⚠️ [LOC-SEED] Invalid worldId '{}' — skip location '{}'",
                    seed.worldId(), seed.locationKey());
                skipped++;
                continue;
            }

            // 2) World 존재 확인 (FK 무결성)
            if (!worldRepository.findById(worldId).isPresent()) {
                log.warn("⚠️ [LOC-SEED] World '{}' not found — skip location '{}'",
                    worldId, seed.locationKey());
                skipped++;
                continue;
            }

            // 3) location_key validation
            if (seed.locationKey() == null || seed.locationKey().isBlank()) {
                log.warn("⚠️ [LOC-SEED] Empty location_key for world '{}' — skip", worldId);
                skipped++;
                continue;
            }

            // 4) null-safe 추출
            String locationKey = seed.locationKey().trim();
            String displayName = nullToEmpty(seed.displayName());
            String description = nullToEmpty(seed.description());
            String defaultBgm = nullToEmpty(seed.defaultBgm());
            int displayOrder = seed.displayOrder() == null ? 0 : seed.displayOrder();
            boolean selectableAsStart = seed.selectableAsStart() == null || seed.selectableAsStart();
            boolean active = seed.active() == null || seed.active();

            // 5) 업서트
            Optional<WorldLocation> existing =
                worldLocationRepository.findByWorldIdAndLocationKey(worldId, locationKey);
            if (existing.isPresent()) {
                if (updateExisting) {
                    WorldLocation loc = existing.get();
                    loc.update(displayName, description, defaultBgm, selectableAsStart, displayOrder);
                    loc.setActive(active);
                    log.info("🔄 [LOC-SEED] Location updated: {}/{}", worldId, locationKey);
                    updated++;
                } else {
                    log.debug("⏭️ [LOC-SEED] Location exists, skip: {}/{}", worldId, locationKey);
                    skipped++;
                }
            } else {
                WorldLocation fresh = WorldLocation.create(
                    worldId, locationKey, displayName, description,
                    defaultBgm, selectableAsStart, displayOrder);
                if (!active) fresh.setActive(false);
                worldLocationRepository.save(fresh);
                log.info("✅ [LOC-SEED] Location created: {}/{} ({})", worldId, locationKey, displayName);
                created++;
            }
        }

        log.info("📍 [LOC-SEED] Complete: {} created, {} updated, {} skipped (total {} configured)",
            created, updated, skipped, seeds.size());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}