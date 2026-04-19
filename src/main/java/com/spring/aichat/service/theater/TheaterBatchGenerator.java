package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.theater.TheaterHeroineAffection;
import com.spring.aichat.domain.theater.TheaterHeroineAffectionRepository;
import com.spring.aichat.domain.theater.TheaterState;
import com.spring.aichat.domain.theater.World;
import com.spring.aichat.domain.theater.WorldRepository;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.theater.LlmSceneBatchOutput;
import com.spring.aichat.dto.theater.TheaterResponses.*;
import com.spring.aichat.exception.ExternalApiException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.prompt.TheaterPromptAssembler;
import com.spring.aichat.service.prompt.TheaterPromptAssembler.AssemblyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * [Phase 5.5-Theater] Theater 배치 생성 서비스
 *
 * LLM에 Theater 프롬프트를 전달하고, 응답을 SceneBatch DTO로 파싱한다.
 *
 * [흐름]
 * 1. TheaterDirectorEngine이 speaker, chapterPlanHint 결정
 * 2. TheaterPromptAssembler가 프롬프트 조립
 * 3. OpenRouterClient(non-stream) 호출 → JSON 완성 응답
 * 4. LlmSceneBatchOutput으로 역직렬화
 * 5. SceneBatch DTO로 변환하여 반환
 * 6. TheaterBatchCacheService에 캐싱 (prefetch용)
 *
 * [비스트리밍 선택 이유]
 * - Theater는 5~8 Scene 배치의 구조적 일관성이 중요 (한 씬 한 화자, rolling_summary 등)
 * - 스트리밍 도중 JSON 파싱 실패 위험 → 안정성 우선
 * - 유저 체감은 prefetch로 커버 (배치 70% 시점에 다음 배치 선행 생성)
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
    private final OpenAiProperties openAiProperties;
    private final ObjectMapper objectMapper;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  배치 생성 파라미터
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record GenerateParams(
        ChatRoom room,
        TheaterState state,
        Long hintedSpeakerHeroineId,    // 장소 선택 후 결정된 히로인 (nullable)
        String branchContext,            // 직전 분기 컨텍스트 (nullable)
        boolean effectiveSecretMode
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔트리포인트: 배치 생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 다음 배치를 LLM에 생성 요청하고 SceneBatch로 반환.
     * 내부에서 캐시에 put까지 수행.
     */
    public SceneBatch generateNextBatch(GenerateParams params) {
        long startMs = System.currentTimeMillis();
        ChatRoom room = params.room();
        TheaterState state = params.state();

        // ─── 1. 세계관 + 히로인 해석 ───
        World world = worldRepository.findById(state.getWorldId())
            .orElseThrow(() -> new IllegalStateException("World not found: " + state.getWorldId()));

        Character speaker = directorEngine.decideNextSpeakerHeroine(
            room, state, params.hintedSpeakerHeroineId());

        List<TheaterHeroineAffection> allAffections = affectionRepository
            .findByRoom_Id(room.getId());

        // ─── 2. Chapter 방향 힌트 + 롤링 요약 ───
        String chapterPlanHint = directorEngine.generateChapterPlanHint(state, speaker);
        String rollingSummary = batchCache.getRollingSummary(room.getId()).orElse(null);

        // ─── 3. 배치 크기 결정 ───
        int targetSize = decideBatchSize(state);

        // ─── 4. 프롬프트 조립 ───
        AssemblyContext promptCtx = new AssemblyContext(
            room, state, world, speaker, allAffections,
            rollingSummary, chapterPlanHint, params.branchContext(),
            targetSize, params.effectiveSecretMode()
        );

        var payload = promptAssembler.assembleBatchPrompt(promptCtx);
        String systemPrompt = payload.staticRules() + "\n\n" + payload.dynamicRules()
            + "\n\n" + payload.outputFormat();

        log.info("🎭 [BATCH-GEN] Request | roomId={} | batchId={} | speaker={} | targetSize={}",
            room.getId(), state.getCurrentBatchId(), speaker.getName(), targetSize);

        // ─── 5. LLM 호출 (non-stream) ───
        LlmSceneBatchOutput llmOutput;
        try {
            llmOutput = invokeLlm(systemPrompt, speaker);
        } catch (Exception e) {
            log.error("🎭 [BATCH-GEN] LLM call failed | roomId={} | batchId={}: {}",
                room.getId(), state.getCurrentBatchId(), e.getMessage());
            throw new ExternalApiException("Theater 배치 생성에 실패했습니다: " + e.getMessage());
        }

        // ─── 6. 검증 ───
        validateBatch(llmOutput, speaker, targetSize);

        // ─── 7. SceneBatch로 변환 ───
        SceneBatch batch = convertToSceneBatch(state, speaker, llmOutput);

        // ─── 8. 캐시 저장 ───
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
    //  배치 크기 결정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private int decideBatchSize(TheaterState state) {
        int min = ChatModePolicy.THEATER_BATCH_SIZE_MIN;
        int max = ChatModePolicy.THEATER_BATCH_SIZE_MAX;
        int remaining = state.getChapterTargetScenes() - state.getScenesInCurrentChapter();
        if (remaining <= 0) return min;
        return Math.max(min, Math.min(max, remaining)); // Chapter 잔여량 내에서
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  LLM 호출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private LlmSceneBatchOutput invokeLlm(String systemPrompt, Character speaker) {
        // Theater는 배치 크기가 크므로 응답 토큰도 크게 확보
        String model = speaker.getLlmModelName() != null
            ? speaker.getLlmModelName()
            : openAiProperties.model();

        // 비스트리밍 완전 응답 호출 (OpenRouterClient.createCompletion non-stream)
        String responseText = openRouterClient.completeJson(
            model,
            systemPrompt,
            "Generate the next batch now.",
            /*maxTokens*/ 4000,
            /*temperature*/ 0.9
        );

        // JSON 정리 (코드 펜스 등 제거)
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
        // ```json ... ``` 또는 ``` ... ``` 제거
        if (trimmed.startsWith("```")) {
            int firstNL = trimmed.indexOf('\n');
            if (firstNL > 0) trimmed = trimmed.substring(firstNL + 1);
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence > 0) trimmed = trimmed.substring(0, lastFence);
        }
        // 첫 { 부터 마지막 } 까지만
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
            // 경고만 — 완전히 비거부
        }
        // speaker 검증: batch_meta.speaker_heroine_slug 일치 여부
        if (out.batchMeta() != null && out.batchMeta().speakerHeroineSlug() != null) {
            String declared = out.batchMeta().speakerHeroineSlug();
            if (!declared.equalsIgnoreCase(speaker.getSlug())) {
                log.warn("🎭 [BATCH-GEN] Speaker slug mismatch | declared={} | expected={}",
                    declared, speaker.getSlug());
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DTO 변환
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
                null,  // illustrationUrl — 비동기 prefetch 파이프라인에서 채워짐
                s.statReflectionHint()
            ));
        }

        Map<Long, Integer> affectionDeltas = new LinkedHashMap<>();
        if (out.heroineAffectionDeltas() != null) {
            for (Map.Entry<String, Integer> e : out.heroineAffectionDeltas().entrySet()) {
                characterRepository.findBySlug(e.getKey())
                    .ifPresent(c -> affectionDeltas.put(c.getId(), e.getValue()));
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
            /*nextBatchPrefetched*/ false, // 추후 prefetch 시 업데이트
            affectionDeltas
        );
    }

    private String resolveSpeakerName(String rawSpeaker, Character speaker, TheaterState state) {
        if (rawSpeaker == null || rawSpeaker.isBlank()) return null; // 나레이션 only
        if ("AVATAR".equalsIgnoreCase(rawSpeaker.trim())) {
            return state.getAvatarName() != null ? state.getAvatarName() : "주인공";
        }
        // 기본: speaker slug와 일치하면 히로인 이름
        if (speaker.getSlug().equalsIgnoreCase(rawSpeaker.trim())) return speaker.getName();
        return rawSpeaker;
    }
}