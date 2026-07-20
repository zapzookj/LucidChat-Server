package com.spring.aichat.config;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
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
 * [Phase 5] 멀티캐릭터 시더
 *
 * 기존 DefaultCharacterSeeder(단일 캐릭터)를 대체.
 * app.characters 리스트의 모든 캐릭터를 slug 기준으로 업서트.
 *
 * - 새 캐릭터: INSERT
 * - 기존 캐릭터 (slug 매치): app.seed.update-existing=true이면 UPDATE
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class CharacterSeeder {

    private final CharacterRepository characterRepository;
    private final CharacterSeedProperties seedProperties;

    @Value("${app.seed.update-existing:true}")
    private boolean updateExisting;

    /**
     * [Seed-Order Fix 2026-07-20] ApplicationRunner 순서는 @Configuration 클래스가 아니라
     * <b>@Bean 메서드의 @Order</b>가 결정한다. 기존엔 클래스에만 @Order(3)이 있어 이 러너가
     * 최후순위로 밀렸고, 빈 DB 첫 부팅 시 RoutineSeeder(@Order 3)가 먼저 돌아 루틴 140건이
     * 전부 skip되는 버그가 있었다. 체인: World(0) → Location(1) → Persona(2) → <b>Character(3)</b> → Routine(4).
     */
    @Bean
    @Order(3)
    public ApplicationRunner seedCharactersRunner() {
        return args -> seedAllCharacters();
    }

    @Transactional
    public void seedAllCharacters() {
        List<CharacterSeedProperties.CharacterSeed> seeds = seedProperties.characters();
        if (seeds == null || seeds.isEmpty()) {
            log.warn("⚠️ [SEED] No character seeds configured (app.characters is empty)");
            return;
        }

        for (CharacterSeedProperties.CharacterSeed seed : seeds) {
            characterRepository.findBySlug(seed.slug())
                .ifPresentOrElse(
                    existing -> {
                        if (updateExisting) {
                            existing.applySeed(seed);
                            log.info("🔄 [SEED] Character updated: {} (slug={})", seed.name(), seed.slug());
                        } else {
                            log.debug("⏭️ [SEED] Character exists, skip: {} (slug={})", seed.name(), seed.slug());
                        }
                    },
                    () -> {
                        Character created = new Character(
                            seed.name(), seed.slug(),
                            seed.baseSystemPrompt(), seed.llmModelName()
                        );
                        created.applySeed(seed);
                        characterRepository.save(created);
                        log.info("✅ [SEED] Character created: {} (slug={})", seed.name(), seed.slug());
                    }
                );
        }

        log.info("🎭 [SEED] Character seeding complete: {} characters processed", seeds.size());
    }
}