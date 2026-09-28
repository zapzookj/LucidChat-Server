package com.spring.aichat.config;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.service.payment.BoostModeResolver;
import com.spring.aichat.service.theater.TheaterModelResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** Checks packaged defaults and seed-to-runtime routing without a Spring context, DB, or API call. */
class GeminiFlashDefaultsTest {
    private static final String FLASH = "google/gemini-3-flash-preview";
    private static final String PRO = "google/gemini-3.1-pro-preview";

    @Test
    void packagedConfigurationUsesFlashForDefaultAndAuxiliaryCalls() throws IOException {
        Binder binder = resourceBinder("application.yml");
        OpenAiProperties models = binder.bind("openai", OpenAiProperties.class).get();
        UgcPipelineProperties ugc = binder.bind("ugc", UgcPipelineProperties.class).get();
        SceneIllustrationProperties scene = binder.bind("illustration.scene", SceneIllustrationProperties.class).get();

        assertAll(
            () -> assertEquals(FLASH, models.model()),
            () -> assertEquals(FLASH, models.sentimentModel()),
            () -> assertEquals(PRO, models.proModel()),
            () -> assertEquals(FLASH, ugc.vlmPrefilter().modelOrDefault()),
            () -> assertEquals(FLASH, scene.director().modelOrDefault(models.model())),
            () -> assertEquals("openai/gpt-5.6-sol", ugc.stage0ModelOrNull())
        );
    }

    @ParameterizedTest
    @CsvSource({"application-characters.yml, 10", "application-charactersm.yml, 6"})
    void everyPackagedSeedUpdatesStoredModelAndTheaterSelection(String resource, int expectedCount)
        throws IOException {
        var seeds = resourceBinder(resource).bind("app", CharacterSeedProperties.class).get().characters();
        var models = resourceBinder("application.yml").bind("openai", OpenAiProperties.class).get();
        var theater = new TheaterModelResolver(models);

        assertEquals(expectedCount, seeds.size(), resource);
        assertEquals(expectedCount, seeds.stream().map(CharacterSeedProperties.CharacterSeed::slug).distinct().count(),
            "Duplicate slugs could silently skip a character during seeding: " + resource);
        for (var seed : seeds) {
            assertEquals(FLASH, seed.llmModelName(), resource + ": " + seed.slug());
            // Exercise the existing-record update path, not only construction with the expected ID.
            var character = new Character(seed.name(), seed.slug(), seed.baseSystemPrompt(), "previous/model");
            character.applySeed(seed);
            assertEquals(FLASH, character.getLlmModelName(), resource + ": " + seed.slug());
            assertEquals(FLASH, theater.resolveBatchModel(null, character, null, false, false),
                resource + ": " + seed.slug());
        }
    }

    @Test
    void javaVlmFallbackMatchesYamlAndPreservesExplicitOverrides() {
        assertEquals(FLASH, new UgcPipelineProperties.VlmPrefilter(null, null, null).modelOrDefault());
        assertEquals(FLASH, new UgcPipelineProperties.VlmPrefilter(null, "  ", null).modelOrDefault());
        assertEquals("custom/vision", new UgcPipelineProperties.VlmPrefilter(null, "custom/vision", null).modelOrDefault());
    }

    @Test
    void normalChatUsesFlashWhileBoostAndExplicitTheaterModelsRemainUnchanged() throws IOException {
        var models = resourceBinder("application.yml").bind("openai", OpenAiProperties.class).get();
        var boost = new BoostModeResolver(models);
        var user = User.local("model-contract-test", "unused", "test", "test@example.invalid");
        assertEquals(FLASH, boost.resolveModel(user));
        user.updateBoostMode(true);
        assertEquals(PRO, boost.resolveModel(user));

        var theater = new TheaterModelResolver(models);
        var explicit = new Character("test", "model-contract-test", "unused", "custom/character-model");
        assertEquals("custom/character-model", theater.resolveBatchModel(null, explicit, null, false, false));
        assertEquals(FLASH, theater.resolveBatchModel(null, null, null, false, false));
        assertEquals(PRO, theater.resolveEndingModel(null));
    }

    private static Binder resourceBinder(String resource) throws IOException {
        // Resolve YAML defaults in isolation so developer-machine env overrides cannot mask a stale default.
        var sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource)).forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }
}
