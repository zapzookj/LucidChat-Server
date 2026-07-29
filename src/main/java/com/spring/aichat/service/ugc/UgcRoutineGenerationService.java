package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.character.CharacterRoutine;
import com.spring.aichat.domain.character.CharacterRoutineRepository;
import com.spring.aichat.domain.enums.DayPart;
import com.spring.aichat.domain.ugc.UgcWorldLocationRepository;
import com.spring.aichat.domain.world.WorldLocationRepository;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.util.LlmOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * [2026-07-30 P2 STORY 개방 1단] UGC 캐릭터 루틴 자동생성.
 *
 * <p>Character.createUgc 주석의 '루틴 데이터는 별도 생성' 예고가 실제로는 미이행이라
 * UGC 캐릭터는 루틴 0개 → STORY 개방 시 항상 유저 합석(오프스크린 일과 없음)이 되는 문제의
 * 선결 구현. 3단 에픽(루틴 자동생성 → World enum PK 마이그레이션 → STORY 개방)의 1단 —
 * 생성된 루틴은 STORY 개방 전까지 휴면 데이터다(현 UGC는 SANDBOX 전용 불변식 유지).
 *
 * <p><b>locationKey 실존 검증 내장</b>: 시더가 무검증 삽입해 유령 장소 루틴 11행이 DB에
 * 들어간 전례(application-v2.yml) — 여기서는 연결 월드의 실제 장소 목록에 있는 키만 통과.
 *
 * <p>트리거: ① 바인딩(Stage 4) 시 월드가 연결돼 있으면 ② linkWorld 변경 시 재생성 /
 * 해제 시 삭제. 실패는 로그만(파이프라인·연결 흐름 불침해).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UgcRoutineGenerationService {

    private static final String SYSTEM_PROMPT = """
        너는 캐릭터 일과(루틴) 설계 엔진이다. 캐릭터 프로필과 세계관 장소 목록을 받아,
        시간대별로 캐릭터가 머물 법한 장소와 확률을 아래 JSON 스키마로만 응답한다.
        규칙:
        - 시간대는 정확히 이 5개: MORNING, NOON, AFTERNOON, EVENING, NIGHT.
        - 각 시간대에 1~2행. location_key는 반드시 [장소 목록]에 있는 key만 사용한다(창작 금지).
        - probability: 20~100 정수 — 같은 시간대 여러 행은 상대 가중치.
        - 캐릭터의 역할·성격에 맞는 일과를 구성한다(예: 사서는 낮에 도서관, 밤에 사적 공간).
        출력 스키마:
        {"routines":[{"time_of_day":"MORNING","location_key":"...","probability":80}, ...]}
        출력은 JSON 외 어떤 텍스트도 금지.
        """;

    private final CharacterRepository characterRepository;
    private final CharacterRoutineRepository routineRepository;
    private final WorldLocationRepository worldLocationRepository;
    private final UgcWorldLocationRepository ugcWorldLocationRepository;
    private final OpenRouterClient openRouterClient;
    private final UgcPipelineProperties ugcProps;
    private final OpenAiProperties openAiProps;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    /** 바인딩/월드 연결 트리거 — 비동기·비차단. 월드 미연결이면 기존 루틴 삭제만. */
    @Async
    public void regenerateForCharacterAsync(Long characterId) {
        try {
            Character character = characterRepository.findById(characterId).orElse(null);
            if (character == null || !character.isUgc()) return;

            // 연결 월드의 장소 목록 (key → "표시명 — 설명")
            Map<String, String> locations = resolveLocations(character);
            if (locations.isEmpty()) {
                // 월드 미연결(또는 장소 없음) — 스테일 루틴 정리만 (유령 키 방지)
                txTemplate.executeWithoutResult(tx -> routineRepository.deleteByCharacterId(characterId));
                log.info("[UGC-ROUTINE] 월드 미연결 — 루틴 정리: characterId={}", characterId);
                return;
            }

            List<CharacterRoutine> rows = generateRows(character, locations);
            // [리뷰픽스] 동시 재생성 경합(바인딩+linkWorld 등)이 uk_routine_char_time_loc 위반으로
            // 롤백되면 스테일 루틴이 남는다 — 1회 재시도로 마지막 승자가 정리하게 한다.
            try {
                replaceRoutines(characterId, rows);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                log.warn("[UGC-ROUTINE] unique 경합 — 1회 재시도: characterId={}", characterId);
                replaceRoutines(characterId, rows);
            }
            log.info("[UGC-ROUTINE] ✅ 루틴 {}행 생성: characterId={} ({})",
                rows.size(), characterId, character.getName());
        } catch (Exception e) {
            log.warn("[UGC-ROUTINE] 생성 실패 (non-blocking): characterId={}, {}", characterId, e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void replaceRoutines(Long characterId, List<CharacterRoutine> rows) {
        txTemplate.executeWithoutResult(tx -> {
            routineRepository.deleteByCharacterId(characterId);
            routineRepository.saveAll(rows);
        });
    }

    private Map<String, String> resolveLocations(Character c) {
        Map<String, String> out = new LinkedHashMap<>();
        if (c.getWorldId() != null) {
            worldLocationRepository.findByWorldIdAndActiveTrueOrderByDisplayOrderAsc(c.getWorldId())
                .forEach(l -> out.put(l.getLocationKey(),
                    l.getDisplayName() + (l.getDescription() != null ? " — " + truncate(l.getDescription(), 80) : "")));
        } else if (c.getUgcWorldId() != null) {
            ugcWorldLocationRepository.findByUgcWorldIdAndActiveTrueOrderByDisplayOrderAsc(c.getUgcWorldId())
                .forEach(l -> out.put(l.getLocationKey(),
                    l.getDisplayName() + (l.getDescription() != null ? " — " + truncate(l.getDescription(), 80) : "")));
        }
        return out;
    }

    private List<CharacterRoutine> generateRows(Character c, Map<String, String> locations) {
        List<CharacterRoutine> valid = new ArrayList<>();
        try {
            StringBuilder sb = new StringBuilder("[캐릭터 프로필]\n");
            sb.append("- 이름: ").append(c.getName()).append('\n');
            if (c.getRole() != null) sb.append("- 역할: ").append(c.getRole()).append('\n');
            if (c.getPersonality() != null) sb.append("- 성격: ").append(truncate(c.getPersonality(), 300)).append('\n');
            if (c.getHobby() != null) sb.append("- 취미: ").append(c.getHobby()).append('\n');
            sb.append("\n[장소 목록 (key: 설명)]\n");
            locations.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));

            String model = ugcProps.stage0ModelOrNull() != null ? ugcProps.stage0ModelOrNull() : openAiProps.model();
            String raw = openRouterClient.completeJson(model, SYSTEM_PROMPT, sb.toString(), 4096, 0.7);
            RoutineEnvelope parsed = objectMapper.readValue(LlmOutputParser.extractJson(raw), RoutineEnvelope.class);

            // [실존 검증] 연결 월드의 실제 키만 통과 + (time, key) 중복 제거 — 유령 장소 루틴 차단
            Set<String> seen = new LinkedHashSet<>();
            if (parsed.routines() != null) {
                for (RoutineRow r : parsed.routines()) {
                    if (r == null || r.locationKey() == null || r.timeOfDay() == null) continue;
                    String key = r.locationKey().trim();
                    DayPart dp = parseDayPart(r.timeOfDay());
                    if (dp == null) continue;
                    if (!locations.containsKey(key)) {
                        log.warn("[UGC-ROUTINE] 미존재 location_key 드랍: {} (characterId={})", key, c.getId());
                        continue;
                    }
                    if (!seen.add(dp.name() + "|" + key)) continue;
                    int prob = r.probability() != null ? r.probability() : 60;
                    valid.add(CharacterRoutine.create(c.getId(), dp, key, prob, "ugc-auto"));
                }
            }
        } catch (Exception e) {
            log.warn("[UGC-ROUTINE] LLM 산출 실패 — 결정론 폴백 사용: characterId={}, {}", c.getId(), e.getMessage());
        }

        // 폴백: 유효 행이 전무하면 장소 라운드로빈으로 전 시간대 커버(항상 유저 합석 문제 방지)
        if (valid.isEmpty()) {
            List<String> keys = new ArrayList<>(locations.keySet());
            DayPart[] parts = DayPart.values();
            for (int i = 0; i < parts.length; i++) {
                valid.add(CharacterRoutine.create(c.getId(), parts[i], keys.get(i % keys.size()), 60, "ugc-auto-fallback"));
            }
        }
        return valid;
    }

    private static DayPart parseDayPart(String s) {
        try {
            return DayPart.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String flat = s.replaceAll("\\R+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoutineEnvelope(List<RoutineRow> routines) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record RoutineRow(String timeOfDay, String locationKey, Integer probability) {}
}
