package com.spring.aichat.bakeoff;

import com.spring.aichat.config.CharacterSeedProperties;
import com.spring.aichat.config.WorldSeedProperties;
import com.spring.aichat.config.WorldLocationSeedProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.world.World;
import com.spring.aichat.domain.world.WorldRef;
import com.spring.aichat.service.story.WorldView;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import java.util.*;

/** Uses the checked-in normal-mode seeds and production mapping, without running seeders or a DB. */
final class RosettaFixture {
    static final String ID = "official-rosetta-stranger-v1";
    static final String STORY_LOCATION = "GARDEN_OF_ACADEMY";
    static final String USER_PROFILE = "21세 성인. 아카데미에 새로 편입한 학생. 상대의 지위에 쉽게 주눅 들지 않고 솔직하게 말한다.";
    static final String INITIAL_USER = "(점심시간, 아카데미 중앙 정원 테라스를 지나가다 로제타와 눈이 마주쳤다.)";
    static final String DEFAULT_INPUT = "나 말이야? 무슨 일인데? 선배라고 다들 눈부터 피해야 하는 건 아니잖아.";
    final Character character;
    final World world;
    final WorldView view;

    RosettaFixture() {
        var characters = bind("application-characters.yml", "app", CharacterSeedProperties.class).characters();
        var matching = characters.stream().filter(c -> "rosetta".equals(c.slug())).toList();
        if (matching.size() != 1) throw new IllegalStateException("Expected exactly one official rosetta seed");
        var seed = matching.get(0);
        character = new Character(seed.name(), seed.slug(), seed.baseSystemPrompt(), seed.llmModelName());
        character.applySeed(seed);
        var worlds = bind("application-worlds.yml", "app", WorldSeedProperties.class).worlds();
        var worldSeeds = worlds.stream().filter(w -> seed.worldId().equals(w.id())).toList();
        if (worldSeeds.size() != 1) throw new IllegalStateException("Expected exactly one matching world seed");
        var w = worldSeeds.get(0);
        world = World.create(WorldId.valueOf(w.id()), w.displayName(), w.tagline(), w.description(),
            w.heroImageUrl(), w.thumbnailUrl(), w.openingNarration(), w.defaultBgm(), w.moodKeywords(),
            Boolean.TRUE.equals(w.secretAllowed()), w.displayOrder() == null ? 0 : w.displayOrder());
        var locations = bind("application-v2.yml", "app.v2", WorldLocationSeedProperties.class).locations().stream()
            .filter(l -> seed.worldId().equals(l.worldId()) && !Boolean.FALSE.equals(l.active()))
            .sorted(Comparator.comparingInt(l -> l.displayOrder() == null ? 0 : l.displayOrder()))
            .map(l -> new WorldView.LocationView(l.locationKey(), l.displayName(), l.description(),
                !Boolean.FALSE.equals(l.selectableAsStart()), null)).toList();
        if (locations.stream().map(WorldView.LocationView::key).distinct().count() != locations.size())
            throw new IllegalStateException("Duplicate location keys");
        view = new WorldView(WorldRef.ofOfficial(world.getId()), world, null, locations);
        if (view.location(STORY_LOCATION).isEmpty()) throw new IllegalStateException("Missing academy garden");
    }

    private static <T> T bind(String file, String prefix, Class<T> type) {
        try {
            var sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
            return new Binder(ConfigurationPropertySources.from(sources)).bind(prefix, type).get();
        } catch (Exception e) { throw new IllegalStateException("Cannot load fixture from " + file, e); }
    }

    Map<String, Object> profile() {
        var p = new LinkedHashMap<String, Object>();
        p.put("id", ID);
        p.put("source", "src/main/resources/application-characters.yml · slug: rosetta（저장소 공식 시드, 운영 DB 미조회）");
        p.put("worldSource", "src/main/resources/application-worlds.yml + application-v2.yml");
        p.put("name", character.getName());
        p.put("age", character.getAge());
        p.put("tagline", character.getTagline());
        p.put("role", character.getRole());
        p.put("personality", character.getPersonality());
        p.put("tone", character.getTone());
        p.put("speechQuirks", character.getSpeechQuirks());
        p.put("backstory", character.getBackstory());
        p.put("coreValues", character.getCoreValues());
        p.put("flaws", character.getFlaws());
        p.put("storyBehaviorGuide", character.getStoryBehaviorGuide());
        p.put("difficulty", character.getDifficultyOrDefault());
        p.put("world", world.getDisplayName());
        p.put("worldId", world.getId());
        p.put("locations", view.locations());
        p.put("state", "처음 만남(STRANGER) · 스탯 0 · 점심시간 · 자유: TERRACE / 스토리: GARDEN_OF_ACADEMY · 관계·장소·시간 고정");
        p.put("userProfile", "지우 · " + USER_PROFILE);
        p.put("initialUser", INITIAL_USER);
        p.put("introNarration", character.getIntroNarration());
        p.put("firstGreeting", character.getFirstGreeting());
        p.put("defaultInput", DEFAULT_INPUT);
        return p;
    }
}
