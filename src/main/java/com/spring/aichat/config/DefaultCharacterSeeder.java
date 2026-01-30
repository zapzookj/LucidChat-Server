package com.spring.aichat.config;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버 부팅 시 기본 캐릭터가 DB에 존재하도록 보장(시드/업서트)
 */
@Configuration
@RequiredArgsConstructor
public class DefaultCharacterSeeder {

    private final CharacterRepository characterRepository;
    private final DefaultCharacterProperties defaultCharacterProperties;

    @Value("${app.seed.update-existing:true}")
    private boolean updateExisting;

    @Bean
    public ApplicationRunner seedDefaultCharacterRunner() {
        return args -> seedDefaultCharacter();
    }

    @Transactional
    public void seedDefaultCharacter() {
        characterRepository.findByName(defaultCharacterProperties.name())
            .ifPresentOrElse(existing -> {
                if (updateExisting) {
                    existing.applySeed(defaultCharacterProperties);
                }
            }, () -> {
                Character created = new Character(
                    defaultCharacterProperties.name(),
                    defaultCharacterProperties.baseSystemPrompt(),
                    defaultCharacterProperties.llmModelName()
                );
                created.applySeed(defaultCharacterProperties);
                characterRepository.save(created);
            });
    }
}
