package com.spring.aichat.config;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.Location;
import com.spring.aichat.domain.enums.Outfit;
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

        int invalidEnumKeys = 0;
        for (CharacterSeedProperties.CharacterSeed seed : seeds) {
            invalidEnumKeys += validateEnumKeys(seed);
            characterRepository.findBySlug(seed.slug())
                .ifPresentOrElse(
                    existing -> {
                        if (updateExisting) {
                            existing.applySeed(seed);
                            // [Fix 2026-07-24] UPDATE 경로 영속화 버그: seedAllCharacters()가 @Bean 람다에서
                            //   자기호출되어 @Transactional이 우회됨 → 트랜잭션/영속 컨텍스트 없이 detached 엔티티라
                            //   dirty-checking이 안 돌아 기존 캐릭터의 필드 변경(예: 신설 프로필 필드)이 유실됐다.
                            //   명시적 save()로 merge하여 트랜잭션 상태와 무관하게 갱신을 영속화한다.
                            characterRepository.save(existing);
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
        if (invalidEnumKeys > 0) {
            log.error("❌ [CHAR-SEED] {}건의 무효 enum 시드 키가 남아 있다 — 위 로그의 캐릭터를 "
                + "application-characters.yml에서 교정할 것 (E-3.①.14)", invalidEnumKeys);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [E-3.①.14 · D-28] 시드 enum 키 검증
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  이 시더는 default-location/baseLocations/default-outfit/baseOutfits를 enum과 대조하지 않고
    //  문자열 그대로 DB에 적재했다(Character.applySeed). app.seed.update-existing=true라 매 부팅마다
    //  유령 키가 재적재됐고, 소비 시점(ChatRoom.parseLocationOrDefault)은 침묵 폴백이라 5명의
    //  캐릭터가 2026-07 이래 엉뚱한 장소에서 시작하는 것을 아무도 몰랐다(E-3.①.1~①.12).
    //
    //  처분은 '보고 후 계속'이다 — throw로 부팅을 막으면 프로드가 시드 오타 하나로 죽는다.
    //  기존 시더 관례(CharacterRoutineSeeder: log.warn + skip/continue)와 맞춘다.
    //  적재 자체는 막지 않는다: 동적 배경 트랙으로 넘어갈 값이 섞일 수 있고, 런타임 폴백이 이미 있다.
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** @return 무효 키 개수 */
    private int validateEnumKeys(CharacterSeedProperties.CharacterSeed seed) {
        int invalid = 0;
        invalid += checkEnum(seed.slug(), "default-location", seed.defaultLocation(), Location.class);
        invalid += checkEnumCsv(seed.slug(), "baseLocations", seed.baseLocations(), Location.class);
        invalid += checkEnum(seed.slug(), "default-outfit", seed.defaultOutfit(), Outfit.class);
        invalid += checkEnumCsv(seed.slug(), "baseOutfits", seed.baseOutfits(), Outfit.class);
        return invalid;
    }

    /** 콤마 구분 목록(baseLocations/baseOutfits)의 각 토큰을 검사한다. */
    private <E extends Enum<E>> int checkEnumCsv(String slug, String field, String csv, Class<E> type) {
        if (csv == null || csv.isBlank()) return 0;
        int invalid = 0;
        for (String token : csv.split(",")) {
            invalid += checkEnum(slug, field, token.trim(), type);
        }
        return invalid;
    }

    private <E extends Enum<E>> int checkEnum(String slug, String field, String value, Class<E> type) {
        if (value == null || value.isBlank()) return 0;
        try {
            Enum.valueOf(type, value.trim());
            return 0;
        } catch (IllegalArgumentException e) {
            log.error("❌ [CHAR-SEED] {} — {}의 '{}'는 {} enum에 없는 키다 (런타임에 조용히 폴백된다)",
                slug, field, value, type.getSimpleName());
            return 1;
        }
    }
}