package com.spring.aichat.config;

import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.world.World;
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

/**
 * [Polish · World Seed] 세계관 시더
 *
 * 캐릭터 시더(CharacterSeeder)와 동일한 패턴으로 app.worlds 리스트의 모든
 * 세계관을 WorldId 기준으로 업서트한다.
 *
 * <p>실행 순서:
 * <ul>
 *   <li>{@code @Order(0)} — 캐릭터 시더보다 먼저 실행되어, 캐릭터의
 *       worldId FK 무결성을 보장한다. (캐릭터는 World가 존재해야 연결 가능)</li>
 * </ul>
 *
 * <p>업서트 정책:
 * <ul>
 *   <li>새 세계관: INSERT</li>
 *   <li>기존 세계관 (WorldId 매치): {@code app.seed.update-existing=true}이면 UPDATE</li>
 *   <li>잘못된 WorldId 문자열: SKIP + 경고 로그</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
@Order(0)
public class WorldSeeder {

    private final WorldRepository worldRepository;
    private final WorldSeedProperties seedProperties;

    @Value("${app.seed.update-existing:true}")
    private boolean updateExisting;

    /**
     * @Order(0): 캐릭터 시더보다 먼저 실행하여 worldId FK 무결성 확보.
     *   (CharacterSeeder는 별도 @Order 미지정이라 default — 0보다 큰 값으로 간주)
     */
    @Bean
    @Order(0)
    public ApplicationRunner seedWorldsRunner() {
        return args -> seedAllWorlds();
    }

    @Transactional
    public void seedAllWorlds() {
        List<WorldSeedProperties.WorldSeed> seeds = seedProperties.worlds();
        if (seeds == null || seeds.isEmpty()) {
            log.warn("⚠️ [WORLD-SEED] No world seeds configured (app.worlds is empty)");
            return;
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;

        for (WorldSeedProperties.WorldSeed seed : seeds) {
            // 1) WorldId enum 검증
            WorldId worldId;
            try {
                worldId = WorldId.valueOf(seed.id());
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("⚠️ [WORLD-SEED] Invalid worldId '{}' — skip", seed.id());
                skipped++;
                continue;
            }

            // 2) null-safe 값 추출
            String displayName = nullToEmpty(seed.displayName());
            String tagline = nullToEmpty(seed.tagline());
            String description = nullToEmpty(seed.description());
            String heroImageUrl = nullToEmpty(seed.heroImageUrl());
            String thumbnailUrl = nullToEmpty(seed.thumbnailUrl());
            String openingNarration = nullToEmpty(seed.openingNarration());
            String defaultBgm = nullToEmpty(seed.defaultBgm());
            String moodKeywords = nullToEmpty(seed.moodKeywords());
            boolean secretAllowed = Boolean.TRUE.equals(seed.secretAllowed());
            boolean active = seed.active() == null ? true : seed.active();
            int displayOrder = seed.displayOrder() == null ? 0 : seed.displayOrder();

            // 3) 업서트
            var existing = worldRepository.findById(worldId);
            if (existing.isPresent()) {
                if (updateExisting) {
                    World w = existing.get();
                    w.update(
                        displayName, tagline, description,
                        heroImageUrl, thumbnailUrl, openingNarration,
                        defaultBgm, moodKeywords, secretAllowed, displayOrder
                    );
                    // World.update()는 active 필드를 다루지 않으므로 별도 적용
                    w.setActive(active);
                    log.info("🔄 [WORLD-SEED] World updated: {} ({})", displayName, worldId);
                    updated++;
                } else {
                    log.debug("⏭️ [WORLD-SEED] World exists, skip: {}", worldId);
                    skipped++;
                }
            } else {
                World fresh = World.create(
                    worldId, displayName, tagline, description,
                    heroImageUrl, thumbnailUrl, openingNarration,
                    defaultBgm, moodKeywords, secretAllowed, displayOrder
                );
                if (!active) {
                    fresh.setActive(false);
                }
                worldRepository.save(fresh);
                log.info("✅ [WORLD-SEED] World created: {} ({})", displayName, worldId);
                created++;
            }
        }

        log.info("🌍 [WORLD-SEED] Complete: {} created, {} updated, {} skipped (total {} configured)",
            created, updated, skipped, seeds.size());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}