package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.theater.*;
import com.spring.aichat.dto.theater.LlmSceneBatchOutput;
import com.spring.aichat.dto.theater.TheaterResponses.*;
import com.spring.aichat.exception.ExternalApiException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.prompt.TheaterPromptAssembler;
import com.spring.aichat.service.prompt.TheaterPromptAssembler.AssemblyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * [Phase 5.5-Theater-Polish] Theater 배치 생성 서비스
 *
 * [v2 변경점]
 * 1. MongoDB Scene 로그 저장 — 대화 기록 조회 + 장기 기억 기반
 * 2. 호감도 델타 강력 클램프 — 배치당 최대 ±2로 제한
 * 3. 최근 씬 프롬프트 주입 — 장기 기억 연속성 (이전 10씬 요약)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterBatchGenerator {

    private final OpenRouterClient openRouterClient;
    private final TheaterPromptAssembler promptAssembler;
    private final TheaterBatchCacheService batchCache;
    private final TheaterDirectorEngine directorEngine;
    private final WorldRepository worldRepository;
    private final CharacterRepository characterRepository;
    private final TheaterHeroineAffectionRepository affectionRepository;
    private final TheaterSceneLogRepository sceneLogRepository;
    private final OpenAiProperties openAiProperties;
    private final ObjectMapper objectMapper;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  호감도 클램프 상수 (v2 추가)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 배치당 히로인별 호감도 변화 최대 절대값 */
    private static final int AFFECTION_DELTA_MAX_PER_BATCH = 2;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  배치 생성 파라미터
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record GenerateParams(
        ChatRoom room,
        TheaterState state,
        Long hintedSpeakerHeroineId,
        String branchContext,
        boolean effectiveSecretMode
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔트리포인트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SceneBatch generateNextBatch(GenerateParams params) {
        long startMs = System.currentTimeMillis();
        ChatRoom room = params.room();
        TheaterState state = params.state();

        World world = worldRepository.findById(state.getWorldId())
            .orElseThrow(() -> new IllegalStateException("World not found: " + state.getWorldId()));

        Character speaker = directorEngine.decideNextSpeakerHeroine(
            room, state, params.hintedSpeakerHeroineId());

        List<TheaterHeroineAffection> allAffections = affectionRepository
            .findByRoom_Id(room.getId());

        String chapterPlanHint = directorEngine.generateChapterPlanHint(state, speaker);
        String rollingSummary = batchCache.getRollingSummary(room.getId()).orElse(null);

        // [v2] 최근 씬 요약 주입 — 장기 기억 연속성
        String recentScenesMemory = buildRecentScenesMemory(room.getId(), state);

        int targetSize = decideBatchSize(state);

        AssemblyContext promptCtx = new AssemblyContext(
            room, state, world, speaker, allAffections,
            rollingSummary, chapterPlanHint, params.branchContext(),
            targetSize, params.effectiveSecretMode()
        );

        var payload = promptAssembler.assembleBatchPrompt(promptCtx);

        // [v2] 최근 씬 메모리를 dynamic rules에 이어붙임
        String augmentedDynamicRules = payload.dynamicRules();
        if (recentScenesMemory != null && !recentScenesMemory.isBlank()) {
            augmentedDynamicRules = augmentedDynamicRules + "\n\n" + recentScenesMemory;
        }

        String systemPrompt = payload.staticRules() + "\n\n" + augmentedDynamicRules
            + "\n\n" + payload.outputFormat();

        log.info("🎭 [BATCH-GEN] Request | roomId={} | batchId={} | speaker={} | targetSize={}",
            room.getId(), state.getCurrentBatchId(), speaker.getName(), targetSize);

        LlmSceneBatchOutput llmOutput;
        try {
            llmOutput = invokeLlm(systemPrompt, speaker);
        } catch (Exception e) {
            log.error("🎭 [BATCH-GEN] LLM call failed | roomId={} | batchId={}: {}",
                room.getId(), state.getCurrentBatchId(), e.getMessage());
            throw new ExternalApiException("Theater 배치 생성에 실패했습니다: " + e.getMessage());
        }

        validateBatch(llmOutput, speaker, targetSize);

        SceneBatch batch = convertToSceneBatch(state, speaker, llmOutput);

        // ─── [v2] Scene 로그 MongoDB 영구 저장 ───
        persistSceneLogs(room, state, speaker, batch);

        // ─── 캐시 저장 ───
        batchCache.putBatch(room.getId(), state.getCurrentBatchId(), batch);
        batchCache.putRawBatch(room.getId(), state.getCurrentBatchId(), llmOutput);
        if (llmOutput.rollingSummary() != null && !llmOutput.rollingSummary().isBlank()) {
            batchCache.putRollingSummary(room.getId(), llmOutput.rollingSummary());
        }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("🎭 [BATCH-GEN] Complete | roomId={} | batchId={} | scenes={} | {}ms",
            room.getId(), state.getCurrentBatchId(),
            batch.scenes() == null ? 0 : batch.scenes().size(), elapsed);

        return batch;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [v2] Scene 로그 영구 저장
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void persistSceneLogs(ChatRoom room, TheaterState state, Character speaker, SceneBatch batch) {
        if (batch.scenes() == null || batch.scenes().isEmpty()) return;

        long globalSeqStart = state.getTotalSceneCount();
        int chapterSeqStart = state.getScenesInCurrentChapter();

        List<TheaterSceneLog> logs = new ArrayList<>();
        int idx = 0;
        for (TheaterScene scene : batch.scenes()) {
            String speakerType;
            Long heroineId = null;
            String speakerName = scene.speakerName();

            if (speakerName == null || speakerName.isBlank()) {
                speakerType = null; // 나레이션 only
            } else if (speakerName.equals(state.getAvatarName())
                || "AVATAR".equalsIgnoreCase(speakerName)) {
                speakerType = "AVATAR";
            } else {
                speakerType = "HEROINE";
                heroineId = speaker.getId();
            }

            EmotionTag emotion = parseEmotion(scene.emotion());

            logs.add(TheaterSceneLog.builder()
                .roomId(room.getId())
                .actNumber(state.getCurrentAct().getNumber())
                .chapterNumber(state.getCurrentChapter())
                .batchId(state.getCurrentBatchId())
                .sceneIndexInBatch(idx)
                .sceneSeqInChapter(chapterSeqStart + idx)
                .globalSceneSeq(globalSeqStart + idx)
                .narration(scene.narration())
                .innerNarration(scene.innerNarration())
                .dialogue(scene.dialogue())
                .speakerType(speakerType)
                .speakerName(speakerName)
                .heroineId(heroineId)
                .emotion(emotion)
                .location(scene.location())
                .timeOfDay(scene.time())
                .outfit(scene.outfit())
                .bgmMode(scene.bgmMode())
                .illustrationUrl(scene.illustrationUrl())
                .statReflectionHint(scene.statReflectionHint())
                .build());
            idx++;
        }

        try {
            sceneLogRepository.saveAll(logs);
            log.debug("🎭 [SCENE-LOG] Persisted {} scenes | roomId={} | batchId={}",
                logs.size(), room.getId(), state.getCurrentBatchId());
        } catch (Exception e) {
            // Scene 로그 저장 실패는 배치 생성 전체를 실패시키지 않음
            log.warn("🎭 [SCENE-LOG] Persist failed | roomId={} : {}", room.getId(), e.getMessage());
        }
    }

    private EmotionTag parseEmotion(String s) {
        if (s == null || s.isBlank()) return EmotionTag.NEUTRAL;
        try {
            return EmotionTag.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return EmotionTag.NEUTRAL;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [v2] 최근 씬 기억 요약
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 최근 10씬을 요약 형태로 프롬프트에 주입.
     * 단순 대사+나레이션 연결로 LLM에게 직전 맥락을 재환기시킨다.
     */
    private String buildRecentScenesMemory(Long roomId, TheaterState state) {
        if (state.getTotalSceneCount() == 0) return null;

        List<TheaterSceneLog> recent = sceneLogRepository
            .findTop30ByRoomIdOrderByGlobalSceneSeqDesc(roomId);
        if (recent.isEmpty()) return null;

        // 역순 정렬 → 시간순
        Collections.reverse(recent);

        // 최근 10씬만 사용 (너무 많으면 토큰 낭비)
        int take = Math.min(10, recent.size());
        List<TheaterSceneLog> window = recent.subList(Math.max(0, recent.size() - take), recent.size());

        StringBuilder sb = new StringBuilder();
        sb.append("# 📚 Recent Scenes (주인공이 기억하는 최근 흐름)\n");
        sb.append("다음은 최근 씬들의 요약이다. 이 흐름에서 자연스럽게 이어지도록 생성하라.\n\n");

        for (TheaterSceneLog s : window) {
            sb.append("[Ch").append(s.getChapterNumber())
                .append(" #").append(s.getSceneSeqInChapter() + 1).append("] ");
            if (s.getNarration() != null && !s.getNarration().isBlank()) {
                sb.append(truncate(s.getNarration(), 120));
            }
            if (s.getDialogue() != null && !s.getDialogue().isBlank()) {
                sb.append(" ").append(s.getSpeakerName() != null ? s.getSpeakerName() : "?")
                    .append(": \"").append(truncate(s.getDialogue(), 80)).append("\"");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  배치 크기 결정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private int decideBatchSize(TheaterState state) {
        int min = ChatModePolicy.THEATER_BATCH_SIZE_MIN;
        int max = ChatModePolicy.THEATER_BATCH_SIZE_MAX;
        int remaining = state.getChapterTargetScenes() - state.getScenesInCurrentChapter();
        if (remaining <= 0) return min;
        return Math.max(min, Math.min(max, remaining));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  LLM 호출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private LlmSceneBatchOutput invokeLlm(String systemPrompt, Character speaker) {
        String model = speaker.getLlmModelName() != null
            ? speaker.getLlmModelName()
            : openAiProperties.model();

        String responseText = openRouterClient.completeJson(
            model, systemPrompt,
            "Generate the next batch now.",
            4000, 0.9
        );

        String cleanJson = extractJson(responseText);

        try {
            return objectMapper.readValue(cleanJson, LlmSceneBatchOutput.class);
        } catch (JsonProcessingException e) {
            log.warn("🎭 [BATCH-GEN] JSON parse failed, raw response:\n{}", responseText);
            throw new ExternalApiException("배치 JSON 파싱 실패: " + e.getMessage());
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNL = trimmed.indexOf('\n');
            if (firstNL > 0) trimmed = trimmed.substring(firstNL + 1);
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence > 0) trimmed = trimmed.substring(0, lastFence);
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed.trim();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  검증
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void validateBatch(LlmSceneBatchOutput out, Character speaker, int targetSize) {
        if (out == null || out.scenes() == null || out.scenes().isEmpty()) {
            throw new ExternalApiException("배치에 씬이 없습니다.");
        }
        int scenes = out.scenes().size();
        if (scenes < ChatModePolicy.THEATER_BATCH_SIZE_MIN
            || scenes > ChatModePolicy.THEATER_BATCH_SIZE_MAX) {
            log.warn("🎭 [BATCH-GEN] Scene count out of range | got={} | expected=[{},{}]",
                scenes, ChatModePolicy.THEATER_BATCH_SIZE_MIN, ChatModePolicy.THEATER_BATCH_SIZE_MAX);
        }
        if (out.batchMeta() != null && out.batchMeta().speakerHeroineSlug() != null) {
            String declared = out.batchMeta().speakerHeroineSlug();
            if (!declared.equalsIgnoreCase(speaker.getSlug())) {
                log.warn("🎭 [BATCH-GEN] Speaker slug mismatch | declared={} | expected={}",
                    declared, speaker.getSlug());
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DTO 변환 — [v2] 호감도 클램프 포함
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private SceneBatch convertToSceneBatch(TheaterState state, Character speaker,
                                           LlmSceneBatchOutput out) {
        List<TheaterScene> scenes = new ArrayList<>();
        int seq = 0;
        for (LlmSceneBatchOutput.LlmScene s : out.scenes()) {
            scenes.add(new TheaterScene(
                seq++,
                resolveSpeakerName(s.speaker(), speaker, state),
                s.narration(),
                s.innerNarration(),
                s.dialogue(),
                s.emotion(),
                s.location(),
                s.time(),
                s.outfit(),
                s.bgmMode(),
                null,
                s.statReflectionHint()
            ));
        }

        // ─── [v2] 호감도 델타 클램프 ───
        Map<Long, Integer> affectionDeltas = new LinkedHashMap<>();
        if (out.heroineAffectionDeltas() != null) {
            for (Map.Entry<String, Integer> e : out.heroineAffectionDeltas().entrySet()) {
                characterRepository.findBySlug(e.getKey())
                    .ifPresent(c -> {
                        int rawDelta = e.getValue() == null ? 0 : e.getValue();
                        int clamped = clampAffectionDelta(rawDelta);
                        if (clamped != rawDelta) {
                            log.debug("🎭 [AFFECTION-CLAMP] {} delta {}→{}",
                                c.getSlug(), rawDelta, clamped);
                        }
                        affectionDeltas.put(c.getId(), clamped);
                    });
            }
        }

        boolean chapterEndAfter = out.batchMeta() != null
            && Boolean.TRUE.equals(out.batchMeta().chapterEndAfter());

        BranchSignal branchSignal = null;
        if (out.branchSignal() != null && out.branchSignal().level() != null) {
            branchSignal = new BranchSignal(
                out.branchSignal().level().toUpperCase(Locale.ROOT),
                out.branchSignal().context()
            );
        }

        return new SceneBatch(
            state.getCurrentBatchId(),
            state.getCurrentAct().getNumber(),
            state.getCurrentChapter(),
            speaker.getId(),
            speaker.getName(),
            scenes,
            chapterEndAfter,
            branchSignal,
            false,
            affectionDeltas
        );
    }

    /** [v2] 배치당 호감도 델타를 ±{AFFECTION_DELTA_MAX_PER_BATCH}로 클램프 */
    private int clampAffectionDelta(int raw) {
        if (raw > AFFECTION_DELTA_MAX_PER_BATCH) return AFFECTION_DELTA_MAX_PER_BATCH;
        if (raw < -AFFECTION_DELTA_MAX_PER_BATCH) return -AFFECTION_DELTA_MAX_PER_BATCH;
        return raw;
    }

    private String resolveSpeakerName(String rawSpeaker, Character speaker, TheaterState state) {
        if (rawSpeaker == null || rawSpeaker.isBlank()) return null;
        if ("AVATAR".equalsIgnoreCase(rawSpeaker.trim())) {
            return state.getAvatarName() != null ? state.getAvatarName() : "주인공";
        }
        if (speaker.getSlug().equalsIgnoreCase(rawSpeaker.trim())) return speaker.getName();
        return rawSpeaker;
    }
}