package com.spring.aichat.config;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.character.CharacterRoutine;
import com.spring.aichat.domain.character.CharacterRoutineRepository;
import com.spring.aichat.domain.enums.DayPart;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.world.WorldLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * [Story V2] Character Routine 시더.
 *
 * <p>실행 순서: 러너 빈 {@code @Order(4)} — CharacterSeeder(러너 빈 @Order(3)) 이후 (Character ID slug 매핑 안정).
 * [Seed-Order Fix 2026-07-20] 기존 @Order(3)은 CharacterSeeder 러너(당시 무순위=최후순위)보다
 * 먼저 실행되어 빈 DB 첫 부팅에서 루틴이 전부 skip되던 버그의 원인이었다.
 *
 * <p>[업데이트 정책]
 * - 시드에 등장한 *모든 캐릭터 slug*에 대해 *기존 루틴 일괄 삭제* → *시드 행 일괄 재삽입*
 * - 이유: (character, timeOfDay)에 N개 후보 가능 → 부분 update 불가
 * - 시드에 등장하지 않은 캐릭터의 기존 루틴은 *보존*
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
@Order(4)
public class CharacterRoutineSeeder {

    private final CharacterRoutineRepository routineRepository;
    private final CharacterRepository characterRepository;
    private final CharacterRoutineSeedProperties seedProperties;
    /** [E-3.②.12 · D-28] location-key ↔ WorldLocation 선언 대조용. */
    private final WorldLocationRepository worldLocationRepository;

    private final org.springframework.transaction.PlatformTransactionManager txManager;

    @Bean @Order(4)
    public ApplicationRunner seedCharacterRoutinesRunner() {
        return args -> new org.springframework.transaction.support.TransactionTemplate(txManager)
            .executeWithoutResult(status -> seedAllRoutines());
    }

    @Transactional
    public void seedAllRoutines() {
        List<CharacterRoutineSeedProperties.RoutineSeed> seeds = seedProperties.characterRoutines();
        if (seeds == null || seeds.isEmpty()) {
            log.warn("⚠️ [ROUTINE-SEED] No character routine seeds configured");
            return;
        }

        // 1) slug → Character ID 매핑 (전체 캐릭터 조회 1회로 최적화)
        Set<String> requiredSlugs = seeds.stream()
            .map(s -> s.characterSlug())
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.toSet());

        Map<String, Long> slugToId = new HashMap<>();
        // [E-3.②.12 · D-28] 유령 location-key 차단용 — slug → 소속 WorldId 매핑도 함께 확보한다.
        Map<String, WorldId> slugToWorldId = new HashMap<>();
        for (String slug : requiredSlugs) {
            characterRepository.findBySlug(slug).ifPresent(c -> {
                slugToId.put(slug, c.getId());
                if (c.getWorldId() != null) slugToWorldId.put(slug, c.getWorldId());
            });
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [E-3.②.12 · D-28] 선언된 WorldLocation 키 집합 사전 로드 (월드당 1회)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  이 시더는 location-key를 WorldLocation 선언과 대조하지 않아, 미선언 키(연화
        //  MOONLIT_FOREST 3행 · 로제타/시에라 GARDEN_OF_MIRRORS 8행)를 그대로 적재해 왔다.
        //  :74-76에서 매 부팅 전삭제 후 재삽입하므로 유령 키가 영구 재생산된다.
        //  실행 순서가 World(0) → Location(1) → … → Routine(4)이라 대조에 필요한 데이터는
        //  이미 DB에 있다 — 기술적으로 가능한데 안 하고 있던 검증이다.
        //  처분은 skip: 유령 위치에 배치하느니 후보를 빼는 편이 안전하다
        //  (WorldRoutingService.java:246 totalWeight가 남은 후보로 재정규화된다).
        Map<WorldId, Set<String>> declaredKeysByWorld = new HashMap<>();
        for (WorldId wid : new java.util.HashSet<WorldId>(slugToWorldId.values())) {
            declaredKeysByWorld.put(wid,
                worldLocationRepository.findByWorldIdAndActiveTrueOrderByDisplayOrderAsc(wid).stream()
                    .map(l -> l.getLocationKey())
                    .collect(Collectors.toSet()));
        }

        // 2) 기존 루틴 일괄 삭제 (시드에 등장한 캐릭터에 한정)
        Set<Long> affectedCharIds = new java.util.HashSet<>(slugToId.values());
        for (Long charId : affectedCharIds) {
            routineRepository.deleteByCharacterId(charId);
        }
        log.info("🧹 [ROUTINE-SEED] Cleared existing routines for {} characters", affectedCharIds.size());

        // 3) 시드 행 일괄 삽입
        int inserted = 0, skipped = 0;
        for (CharacterRoutineSeedProperties.RoutineSeed seed : seeds) {
            Long characterId = slugToId.get(seed.characterSlug());
            if (characterId == null) {
                log.warn("⚠️ [ROUTINE-SEED] Character slug '{}' not found — skip routine",
                    seed.characterSlug());
                skipped++;
                continue;
            }

            DayPart timeOfDay;
            try {
                timeOfDay = DayPart.valueOf(seed.timeOfDay());
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("⚠️ [ROUTINE-SEED] Invalid timeOfDay '{}' for char '{}' — skip",
                    seed.timeOfDay(), seed.characterSlug());
                skipped++;
                continue;
            }

            if (seed.locationKey() == null || seed.locationKey().isBlank()) {
                log.warn("⚠️ [ROUTINE-SEED] Empty location_key for {} {} — skip",
                    seed.characterSlug(), timeOfDay);
                skipped++;
                continue;
            }

            // [E-3.②.12 · D-28] 선언된 WorldLocation과 대조 — 유령 키면 그 후보를 통째로 뺀다.
            // (worldId가 없는 캐릭터 = UGC/월드 미소속은 대조 기준이 없어 통과시킨다.)
            String locationKey = seed.locationKey().trim();
            WorldId worldId = slugToWorldId.get(seed.characterSlug());
            if (worldId != null) {
                Set<String> declared = declaredKeysByWorld.getOrDefault(worldId, Set.of());
                if (!declared.contains(locationKey)) {
                    log.warn("⚠️ [ROUTINE-SEED] Undeclared location_key '{}' for {} ({}) — skip (E-3.②)",
                        locationKey, seed.characterSlug(), worldId);
                    skipped++;
                    continue;
                }
            }

            int probability = seed.probability() == null ? 50 : seed.probability();
            String notes = seed.notes() == null ? "" : seed.notes();

            CharacterRoutine routine = CharacterRoutine.create(
                characterId, timeOfDay, locationKey, probability, notes);
            routineRepository.save(routine);
            inserted++;
        }

        log.info("⏰ [ROUTINE-SEED] Complete: {} inserted, {} skipped (total {} configured, {} characters affected)",
            inserted, skipped, seeds.size(), affectedCharIds.size());
    }
}