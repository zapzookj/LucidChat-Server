package com.spring.aichat.config;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
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