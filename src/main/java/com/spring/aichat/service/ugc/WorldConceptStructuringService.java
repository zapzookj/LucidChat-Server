package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.dto.ugc.StructuredWorld;
import com.spring.aichat.dto.ugc.WorldDraft;
import com.spring.aichat.exception.ExternalApiException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.util.LlmOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * [UGC 세계관 빌더] W0 — 유저 자유 서술을 세계관 구조화 데이터로 변환.
 * {@link ConceptStructuringService}와 동일한 3단 파이프(call → parse → sanitize)·동일 불변 원칙:
 * 유저 텍스트는 여기서 끝나고, 배경/썸네일 프롬프트는 구조화 산출만으로 조립된다.
 *
 * <p>부가 책임: 유저가 W1에서 직접 추가한 장소(backgroundPrompt 없음)의 <b>일괄 프롬프트화</b>
 * ({@link #promptizeLocations}) — 일러 시작 시 워커가 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldConceptStructuringService {

    /** 장소 제안 기본 개수 — 초과 산출은 절삭. */
    static final int SUGGESTED_LOCATIONS = 6;
    /** locationKey 최대 길이 — canonical key(UGCW_{id}__{KEY}) 100자 제한 정합. */
    static final int LOCATION_KEY_MAX = 40;

    // [리뷰 픽스] 텍스트 상한 단일 소스 — W0 sanitize와 유저 편집(updateDraft) 검증이 대칭이어야
    // LLM 산출 경로로 상한이 우회되지 않는다. lore 상한은 캐릭터 정체성 희석 방지선(도그푸딩 #3).
    static final int INTRO_MAX_LENGTH = 500;
    static final int LORE_MAX_LENGTH = 2000;
    static final int LOCATION_DESC_MAX_LENGTH = 300;
    static final int MOOD_TAG_MAX_LENGTH = 20;
    static final int MOOD_TAGS_MAX_COUNT = 10;

    private static final String SYSTEM_PROMPT = """
        너는 세계관 구조화 엔진이다. 유저의 자유 서술을 아래 JSON 스키마로만 응답한다.
        규칙:
        - world.name: 한국어 세계관 이름 — 유저가 이름을 지정했다면 반드시 그대로 사용.
        - world.intro: 카드 노출용 소개 1~2문장 (한국어).
        - world.lore: 채팅 AI의 시스템 프롬프트에 주입될 세계관 설정 본문 (한국어 4~8문장).
          시대·사회 구조·규칙(마법/기술/금기)·핵심 긴장을 담는다. 지시문·메타 발화·2인칭 호명 금지.
        - world.mood_tags: 무드 키워드 3~5개 (짧은 단어 — 예: "몽환적", "네온", "잔혹동화").
        - locations: 정확히 6개 — 이 세계관을 대표하는 서로 다른 장소.
          · location_key: 영문 대문자 SCREAMING_SNAKE_CASE 고유 키 (최대 40자 — 예: "ROOFTOP_GARDEN")
          · display_name: 한국어 표시명
          · description: 한국어 1~2문장 분위기 설명 (채팅 장소 안내용)
          · background_prompt: 영문 1~3문장 — 2D 애니메이션 비주얼노벨 배경 일러스트 묘사.
            시간대·조명·재질·원근을 포함하고, 사람·글자·로고는 절대 넣지 않는다.
        - thumbnail_prompt: 영문 1~3문장 — 세계관 전체를 상징하는 대표 풍경 묘사 (사람 없음).
        - moderation: 명백한 미성년 관련 설정 시그널이 있을 때만 minor_signal=true (모호하면 false).
        출력 스키마:
        {"world":{"name":"...","intro":"...","lore":"...","mood_tags":["..."]},
         "locations":[{"location_key":"...","display_name":"...","description":"...","background_prompt":"..."}],
         "thumbnail_prompt":"...",
         "moderation":{"minor_signal":false,"reason":""}}
        출력은 JSON 외 어떤 텍스트도 금지.
        """;

    private static final String PROMPTIZE_SYSTEM_PROMPT = """
        너는 배경 일러스트 프롬프트 변환기다. 세계관 설명과 장소 목록(한국어)을 받아,
        각 장소의 background_prompt(영문 1~3문장)를 생성해 아래 JSON 스키마로만 응답한다.
        규칙: 2D 애니메이션 비주얼노벨 배경 묘사 — 시간대·조명·재질·원근 포함,
        사람·글자·로고 금지. location_key는 입력을 그대로 되돌려준다.
        출력 스키마: {"locations":[{"location_key":"...","background_prompt":"..."}]}
        출력은 JSON 외 어떤 텍스트도 금지.
        """;

    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties openAiProps;
    private final com.spring.aichat.config.UgcPipelineProperties ugcProps;
    private final ObjectMapper objectMapper;

    /** [2026-07-21] W0도 Stage0 전용 모델 공유 — ugc.stage0-model 지정 시 우선. */
    private String effectiveModel() {
        String override = ugcProps.stage0ModelOrNull();
        return override != null ? override : openAiProps.model();
    }

    /**
     * 세계관 구조화 실행 (블로킹 — @Async 오케스트레이터 스레드에서 호출).
     *
     * @param rawInput      유저 자유 서술 (하드 키워드 게이트 통과본)
     * @param requestedName 유저 지정 이름 (null이면 LLM 작명)
     * @param moodHint      장르/무드 힌트 (선택)
     */
    public StructuredWorld structure(String rawInput, String requestedName, String moodHint) {
        String userMessage = buildUserMessage(rawInput, requestedName, moodHint);

        String raw;
        try {
            raw = openRouterClient.completeJson(
                effectiveModel(), SYSTEM_PROMPT, userMessage, 8192, 0.7);
        } catch (Exception e) {
            log.error("[UGC-WORLD-W0] LLM 호출 실패: {}", e.getMessage());
            throw new ExternalApiException("세계관 구조화 실패 — 잠시 후 다시 시도해 주세요.");
        }

        StructuredWorld world = parse(raw);
        return sanitize(world, requestedName);
    }

    /**
     * 유저 직접 추가 장소의 일괄 프롬프트화 — backgroundPrompt가 비어 있는 장소만 채워 반환한다.
     * (유저 텍스트 직결 금지 원칙 — 직접 입력 장소도 반드시 LLM 변환을 거친다.)
     *
     * @param worldLore 세계관 정합성 문맥 (드래프트 lore)
     * @param locations 드래프트 장소 전체 — 순서 유지
     */
    public List<WorldDraft.DraftLocation> promptizeLocations(String worldLore,
                                                             List<WorldDraft.DraftLocation> locations) {
        List<WorldDraft.DraftLocation> missing = locations.stream()
            .filter(l -> l.backgroundPrompt() == null || l.backgroundPrompt().isBlank())
            .toList();
        if (missing.isEmpty()) return locations;

        StringBuilder sb = new StringBuilder();
        sb.append("[세계관 설정]:\n").append(worldLore == null ? "(없음)" : worldLore).append("\n\n[장소 목록]:\n");
        for (WorldDraft.DraftLocation l : missing) {
            sb.append("- location_key: ").append(l.locationKey())
                .append(" / 이름: ").append(l.displayName())
                .append(" / 설명: ").append(l.description() == null ? "" : l.description()).append("\n");
        }

        String raw;
        try {
            raw = openRouterClient.completeJson(
                effectiveModel(), PROMPTIZE_SYSTEM_PROMPT, sb.toString(), 4096, 0.5);
        } catch (Exception e) {
            log.error("[UGC-WORLD-W2] 장소 프롬프트화 LLM 호출 실패: {}", e.getMessage());
            throw new ExternalApiException("장소 프롬프트 생성 실패 — 잠시 후 다시 시도해 주세요.");
        }

        Map<String, String> byKey = parsePromptized(raw);
        List<WorldDraft.DraftLocation> result = new ArrayList<>(locations.size());
        for (WorldDraft.DraftLocation l : locations) {
            if (l.backgroundPrompt() != null && !l.backgroundPrompt().isBlank()) {
                result.add(l);
                continue;
            }
            String prompt = byKey.get(l.locationKey());
            if (prompt == null || prompt.isBlank()) {
                log.error("[UGC-WORLD-W2] 프롬프트화 누락: key={}", l.locationKey());
                throw new ExternalApiException("장소 프롬프트 생성 실패 — 잠시 후 다시 시도해 주세요.");
            }
            result.add(new WorldDraft.DraftLocation(l.locationKey(), l.displayName(), l.description(), prompt.trim()));
        }
        return result;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildUserMessage(String rawInput, String requestedName, String moodHint) {
        StringBuilder sb = new StringBuilder();
        if (requestedName != null && !requestedName.isBlank()) {
            sb.append("[세계관 이름 (반드시 이 이름 사용)]: ").append(requestedName.trim()).append("\n\n");
        }
        if (moodHint != null && !moodHint.isBlank()) {
            sb.append("[장르/무드 힌트]: ").append(moodHint.trim()).append("\n\n");
        }
        sb.append("[세계관 서술]:\n").append(rawInput);
        return sb.toString();
    }

    private StructuredWorld parse(String raw) {
        try {
            String json = LlmOutputParser.extractJson(raw);
            return objectMapper.readValue(json, StructuredWorld.class);
        } catch (Exception e) {
            log.error("[UGC-WORLD-W0] 산출 파싱 실패: {} — raw 앞부분: {}", e.getMessage(),
                raw == null ? "null" : raw.substring(0, Math.min(200, raw.length())));
            throw new ExternalApiException("세계관 구조화 실패 — 잠시 후 다시 시도해 주세요.");
        }
    }

    private Map<String, String> parsePromptized(String raw) {
        try {
            String json = LlmOutputParser.extractJson(raw);
            PromptizedLocations parsed = objectMapper.readValue(json, PromptizedLocations.class);
            Map<String, String> byKey = new HashMap<>();
            if (parsed.locations() != null) {
                for (PromptizedLocation l : parsed.locations()) {
                    if (l.locationKey() != null) byKey.put(l.locationKey().trim(), l.backgroundPrompt());
                }
            }
            return byKey;
        } catch (Exception e) {
            log.error("[UGC-WORLD-W2] 프롬프트화 파싱 실패: {}", e.getMessage());
            throw new ExternalApiException("장소 프롬프트 생성 실패 — 잠시 후 다시 시도해 주세요.");
        }
    }

    /** 필수 필드 검증 + 장소 6개 절삭 + locationKey 정규화·중복 해소 + 유저 지정 이름 우선. */
    private StructuredWorld sanitize(StructuredWorld w, String requestedName) {
        if (w.world() == null
            || w.world().name() == null || w.world().name().isBlank()
            || w.world().lore() == null || w.world().lore().isBlank()
            || w.locations() == null || w.locations().isEmpty()
            || w.thumbnailPrompt() == null || w.thumbnailPrompt().isBlank()) {
            log.error("[UGC-WORLD-W0] 필수 필드 누락: world={}, locations={}, thumbPrompt={}",
                w.world(), w.locations() == null ? "null" : w.locations().size(),
                w.thumbnailPrompt() == null ? "null" : "ok");
            throw new ExternalApiException("세계관 구조화 실패 — 잠시 후 다시 시도해 주세요.");
        }

        String effectiveName = (requestedName != null && !requestedName.isBlank())
            ? requestedName.trim() : w.world().name().trim();
        effectiveName = truncate(effectiveName, 50);

        // [리뷰 픽스] LLM 산출에도 유저 편집과 동일 상한 강제 — 상한 우회 차단 (초과분 절삭)
        List<String> moodTags = (w.world().moodTags() == null ? List.<String>of() : w.world().moodTags()).stream()
            .filter(t -> t != null && !t.isBlank())
            .map(t -> truncate(t.trim(), MOOD_TAG_MAX_LENGTH))
            .limit(MOOD_TAGS_MAX_COUNT)
            .toList();

        List<StructuredWorld.LocationSuggestion> locations = new ArrayList<>();
        Set<String> usedKeys = new HashSet<>();
        int order = 0;
        for (StructuredWorld.LocationSuggestion loc : w.locations()) {
            if (locations.size() >= SUGGESTED_LOCATIONS) break;
            if (loc.displayName() == null || loc.displayName().isBlank()
                || loc.backgroundPrompt() == null || loc.backgroundPrompt().isBlank()) {
                log.warn("[UGC-WORLD-W0] 불완전 장소 제안 스킵: {}", loc);
                continue;
            }
            String key = normalizeLocationKey(loc.locationKey(), order, usedKeys);
            usedKeys.add(key);
            locations.add(new StructuredWorld.LocationSuggestion(
                key, truncate(loc.displayName().trim(), 100),
                truncate(loc.description(), LOCATION_DESC_MAX_LENGTH), loc.backgroundPrompt()));
            order++;
        }
        if (locations.isEmpty()) {
            throw new ExternalApiException("세계관 구조화 실패 — 잠시 후 다시 시도해 주세요.");
        }

        StructuredWorld.WorldProfile profile = new StructuredWorld.WorldProfile(
            effectiveName, truncate(w.world().intro(), INTRO_MAX_LENGTH),
            truncate(w.world().lore(), LORE_MAX_LENGTH), moodTags);
        return new StructuredWorld(profile, locations, w.thumbnailPrompt(), w.moderation());
    }

    /**
     * locationKey 정규화 — 영문 대문자·숫자·언더스코어만 유지, 40자 절삭, 빈/중복 키는
     * {@code LOC_{n}} 폴백. canonical key({@code UGCW_{id}__{KEY}})와 v1.1 루틴 이행의 안정 식별자.
     */
    static String normalizeLocationKey(String rawKey, int index, Set<String> usedKeys) {
        String key = rawKey == null ? "" : rawKey.trim().toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (key.length() > LOCATION_KEY_MAX) key = key.substring(0, LOCATION_KEY_MAX);
        if (key.isBlank() || usedKeys.contains(key)) {
            key = "LOC_" + (index + 1);
            int suffix = 1;
            while (usedKeys.contains(key)) key = "LOC_" + (index + 1) + "_" + (suffix++);
        }
        return key;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ── promptize 응답 파싱용 내부 레코드 (snake_case) ──

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    @com.fasterxml.jackson.databind.annotation.JsonNaming(
        com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PromptizedLocations(List<PromptizedLocation> locations) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    @com.fasterxml.jackson.databind.annotation.JsonNaming(
        com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PromptizedLocation(String locationKey, String backgroundPrompt) {}
}
