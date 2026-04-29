package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.BranchLevel;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.RelationStatus;
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
    /** [Phase 5.5 UX Polish · R3] 명령어 사용 마킹용 — wasUsed/usedAt/usedInBatchId */
    private final TheaterDirectorNoteRepository directorNoteRepository;
    /** [Phase 5.5 UX Polish · R6] AUTO_MOMENT / CHAPTER_END 노트 + 일러스트 통합 */
    private final TheaterAutoNoteService autoNoteService;
    /** [Phase 5.5 UX Polish · R6] 배치 도착 시 location prefetch */
    private final com.spring.aichat.service.BackgroundGenerationService backgroundGenerationService;
    private final OpenAiProperties openAiProperties;
    private final ObjectMapper objectMapper;
    // [Phase III · 작업 3] 2단 모델 라우팅
    private final TheaterModelResolver modelResolver;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  호감도 클램프 상수 (v2 추가)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 배치당 히로인별 호감도 변화 최대 절대값 */
    private static final int AFFECTION_DELTA_MAX_PER_BATCH = 2;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  배치 생성 파라미터
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * GenerateParams
     *
     * @param room                   ChatRoom
     * @param state                  Theater 세션 상태
     * @param hintedSpeakerHeroineId 화자 힌트 (LOCATION 분기 후 등)
     * @param branchContext          분기 컨텍스트 (Redis에서 consume된 값)
     * @param effectiveSecretMode    시크릿 모드 활성 여부
     * @param justBranched           [Phase III · 작업 3]
     *                               직전 턴에 분기 적용이 있었는지.
     *                               true면 ModelResolver가 proModel을 선택.
     * @param injectedBranchLevel    [Phase 5.5 UX Polish · R2]
     *                               이번 배치 끝에 강제 발생시킬 분기 레벨
     *                               ("MINOR"/"MAJOR"/"CLIMAX") or null.
     *                               TheaterService가 decideBranchAfterBatch()로 결정 후 주입.
     * @param activeDirectorCommand  [Phase 5.5 UX Polish · R3]
     *                               유저가 발동한 검증 통과 명령어 텍스트 or null.
     *                               1배치 일회성으로 프롬프트에 흡수.
     */
    public record GenerateParams(
        ChatRoom room,
        TheaterState state,
        Long hintedSpeakerHeroineId,
        String branchContext,
        boolean effectiveSecretMode,
        boolean justBranched,
        String injectedBranchLevel,
        String activeDirectorCommand
    ) {
        /** 하위 호환 — 5-인자 호출부 (모든 신규 필드 null/false) */
        public GenerateParams(ChatRoom room, TheaterState state,
                              Long hintedSpeakerHeroineId, String branchContext,
                              boolean effectiveSecretMode) {
            this(room, state, hintedSpeakerHeroineId, branchContext,
                effectiveSecretMode, false, null, null);
        }

        /** 하위 호환 — 6-인자 호출부 (작업 3까지의 시그니처) */
        public GenerateParams(ChatRoom room, TheaterState state,
                              Long hintedSpeakerHeroineId, String branchContext,
                              boolean effectiveSecretMode, boolean justBranched) {
            this(room, state, hintedSpeakerHeroineId, branchContext,
                effectiveSecretMode, justBranched, null, null);
        }
    }

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

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R2] 결정론적 분기 결정
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  params에 명시된 값이 있으면 그것 우선(테스트/특수 진입), 없으면 DirectorEngine.
        //  null이면 분기 없음 (LOCATION 분기는 별도 경로이므로 여기서 처리하지 않음).
        String resolvedBranchLevel = params.injectedBranchLevel();
        if (resolvedBranchLevel == null) {
            BranchLevel computed = directorEngine.decideBranchAfterBatch(
                state, targetSize, state.getChapterTargetScenes());
            resolvedBranchLevel = computed != null ? computed.name() : null;
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R3] 활성 감독 명령어 consume
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  params에 명시된 값이 있으면 그것 우선(테스트/특수 진입).
        //  없으면 Redis 활성 큐에서 consume — 1회 사용 후 자동 폐기.
        //  consume된 명령어의 noteId는 응답 처리 후 wasUsed 마킹용으로 보관.
        String resolvedDirectorCommand = params.activeDirectorCommand();
        Long consumedCommandNoteId = null;
        if (resolvedDirectorCommand == null) {
            var consumed = batchCache.consumeActiveDirectorCommand(room.getId());
            if (consumed.isPresent()) {
                resolvedDirectorCommand = consumed.get().text();
                consumedCommandNoteId = consumed.get().noteId();
                log.info("🎬 [BATCH-GEN] Director command consumed | roomId={} | noteId={}",
                    room.getId(), consumedCommandNoteId);
            }
        }

        AssemblyContext promptCtx = new AssemblyContext(
            room, state, world, speaker, allAffections,
            rollingSummary, chapterPlanHint, params.branchContext(),
            targetSize, params.effectiveSecretMode(),
            // [R2/R3] 결정론적 분기 + 감독 명령어 주입
            resolvedBranchLevel,
            resolvedDirectorCommand
        );

        var payload = promptAssembler.assembleBatchPrompt(promptCtx);

        // [v2] 최근 씬 메모리를 dynamic rules에 이어붙임
        String augmentedDynamicRules = payload.dynamicRules();
        if (recentScenesMemory != null && !recentScenesMemory.isBlank()) {
            augmentedDynamicRules = augmentedDynamicRules + "\n\n" + recentScenesMemory;
        }

        String systemPrompt = payload.staticRules() + "\n\n" + augmentedDynamicRules
            + "\n\n" + payload.outputFormat();

        log.info("🎭 [BATCH-GEN] Request | roomId={} | batchId={} | speaker={} | targetSize={} | branch={} | cmd={}",
            room.getId(), state.getCurrentBatchId(), speaker.getName(), targetSize,
            params.injectedBranchLevel(), params.activeDirectorCommand() != null ? "active" : "none");

        LlmSceneBatchOutput llmOutput;
        try {
            // [Phase III · 작업 3] 2단 모델 라우팅 — 분기 직후 또는 마지막 Chapter면 proModel
            boolean isLastChapter = directorEngine.isLastChapterOfAct(state);
            llmOutput = invokeLlm(systemPrompt, speaker, room.getUser(), state,
                params.justBranched(), isLastChapter);
        } catch (Exception e) {
            log.error("🎭 [BATCH-GEN] LLM call failed | roomId={} | batchId={}: {}",
                room.getId(), state.getCurrentBatchId(), e.getMessage());
            throw new ExternalApiException("Theater 배치 생성에 실패했습니다: " + e.getMessage());
        }

        validateBatch(llmOutput, speaker, targetSize);

        SceneBatch batch = convertToSceneBatch(state, speaker, llmOutput);

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R2] 결정론적 분기 강제
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  resolvedBranchLevel이 있는데 LLM이 branchSignal을 빠뜨렸다면
        //  백엔드가 강제로 채워준다. 이렇게 해야 분기 빈도가 보장됨.
        //  또한 MAJOR가 발동된 경우 state에 마킹하여 같은 Chapter의 두 번 발동 차단.
        if (resolvedBranchLevel != null && !resolvedBranchLevel.isBlank()) {
            String level = resolvedBranchLevel.toUpperCase(Locale.ROOT);
            BranchSignal incoming = batch.branchSignal();
            if (incoming == null || incoming.level() == null
                || !level.equalsIgnoreCase(incoming.level())) {
                // LLM이 빠뜨렸거나 다른 레벨로 응답 → 백엔드가 보정
                String forcedContext = (incoming != null && incoming.context() != null)
                    ? incoming.context()
                    : "주인공이 결정의 순간을 마주한다.";
                batch = new SceneBatch(
                    batch.batchId(), batch.actNumber(), batch.chapterNumber(),
                    batch.speakerHeroineId(), batch.speakerHeroineName(),
                    batch.scenes(), batch.chapterEndAfter(),
                    new BranchSignal(level, forcedContext),
                    batch.locationChoiceAfter(),
                    batch.heroineAffectionDeltas()
                );
                log.warn("🎭 [BATCH-GEN] Forced branch signal | injected={} | LLM-returned={} | roomId={}",
                    level, incoming != null ? incoming.level() : "null", room.getId());
            }
            // MAJOR 발동 시 state 마킹 (같은 Chapter 재발동 방지)
            if ("MAJOR".equals(level)) {
                state.markMajorBranchDoneInChapter();
            }
        }

        // ─── [v2] Scene 로그 MongoDB 영구 저장 ───
        persistSceneLogs(room, state, speaker, batch);

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R3] 명령어 사용 마킹
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  consume된 명령어 noteId가 있으면 DB에 wasUsed=true / usedAt / usedInBatchId 마킹.
        //  실패해도 본 흐름엔 영향 없음 (try-catch 격리).
        if (consumedCommandNoteId != null) {
            try {
                directorNoteRepository.findById(consumedCommandNoteId).ifPresent(n -> {
                    n.markUsed(state.getCurrentBatchId());
                    directorNoteRepository.save(n);
                });
                log.debug("🎬 [BATCH-GEN] Command marked used | noteId={}", consumedCommandNoteId);
            } catch (Exception e) {
                log.warn("🎬 [BATCH-GEN] Mark command-used failed (non-fatal): noteId={}, err={}",
                    consumedCommandNoteId, e.getMessage());
            }
        }

        // ─── 캐시 저장 ───
        batchCache.putBatch(room.getId(), state.getCurrentBatchId(), batch);
        batchCache.putRawBatch(room.getId(), state.getCurrentBatchId(), llmOutput);
        if (llmOutput.rollingSummary() != null && !llmOutput.rollingSummary().isBlank()) {
            batchCache.putRollingSummary(room.getId(), llmOutput.rollingSummary());
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R6] AUTO_MOMENT / CHAPTER_END 자동 캡처
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  1. 호감도 ±2 검출 → AUTO_MOMENT 노트 + 일러스트 트리거
        //  2. chapterEnd 시 → CHAPTER_END 노트 + 일러스트 트리거
        //  실패해도 본 흐름엔 영향 없음 (autoNoteService 자체에 try-catch).
        captureAutoMomentsFromBatch(room, state, batch);
        if (batch.chapterEndAfter()) {
            captureChapterEndFromBatch(room, state, llmOutput, batch);
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R6] Location prefetch
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  배치 내 location 변화 검출 → 백그라운드 prefetch.
        //  유저가 씬을 한 장씩 넘기는 동안 배경이 미리 준비되어 latency 마스킹.
        prefetchBatchLocations(speaker.getId(), batch);

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("🎭 [BATCH-GEN] Complete | roomId={} | batchId={} | scenes={} | {}ms",
            room.getId(), state.getCurrentBatchId(),
            batch.scenes() == null ? 0 : batch.scenes().size(), elapsed);

        return batch;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5 UX Polish · R6] 자동 노트 트리거 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 배치의 heroineAffectionDeltas에서 ±2(또는 그 이상)를 잡아 AUTO_MOMENT 캡처.
     * 한 배치당 여러 히로인의 큰 변동이 있을 수 있지만, 비용 절감을 위해
     * 절대값이 가장 큰 1명만 트리거 (결정적 순간 1회).
     */
    private void captureAutoMomentsFromBatch(ChatRoom room, TheaterState state, SceneBatch batch) {
        if (batch.heroineAffectionDeltas() == null || batch.heroineAffectionDeltas().isEmpty()) return;

        Long topHeroineId = null;
        int topAbs = 0;
        int topDelta = 0;
        for (Map.Entry<Long, Integer> e : batch.heroineAffectionDeltas().entrySet()) {
            int delta = e.getValue() == null ? 0 : e.getValue();
            int abs = Math.abs(delta);
            if (abs >= AFFECTION_DELTA_MAX_PER_BATCH && abs > topAbs) {
                topAbs = abs;
                topDelta = delta;
                topHeroineId = e.getKey();
            }
        }
        if (topHeroineId == null) return;

        Character heroine = characterRepository.findById(topHeroineId).orElse(null);
        if (heroine == null) return;

        String sceneRefId = state.getCurrentBatchId() + ":auto-moment";
        autoNoteService.captureAffectionMoment(room, state, heroine, topDelta, sceneRefId);
    }

    /**
     * Chapter 종료 직전 호출 — 호감도 1위 히로인을 일러스트 화자로 캡처.
     */
    private void captureChapterEndFromBatch(ChatRoom room, TheaterState state,
                                            LlmSceneBatchOutput out, SceneBatch batch) {
        // LLM이 batch_meta.chapter_title을 제공했다면 사용
        String chapterTitle = (out.batchMeta() != null) ? out.batchMeta().chapterTitle() : null;

        // 호감도 1위 히로인 (이번 Chapter 누적 — 일러스트 화자)
        Character leader = pickLeaderHeroine(room.getId());
        autoNoteService.captureChapterEnd(room, state, chapterTitle, leader);
    }

    private Character pickLeaderHeroine(Long roomId) {
        try {
            return affectionRepository.findByRoom_Id(roomId).stream()
                .max(Comparator.comparingInt(TheaterHeroineAffection::getAffection))
                .map(TheaterHeroineAffection::getCharacter)
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 배치의 location 변화를 검출 → 새 location마다 BackgroundGenerationService.resolveBackground 호출.
     *
     * 효과:
     *  - cache HIT면 즉시 반환 (URL이 이미 있음 — Redis 캐시 업데이트)
     *  - cache MISS면 BackgroundGenerationService 내부에서 비동기 생성 트리거
     *
     * 이미 본 location은 Set으로 dedup. 비동기 호출이라 본 흐름 블로킹 없음.
     * 동일 batch 내 같은 character의 같은 location/time은 한 번만 prefetch.
     */
    private void prefetchBatchLocations(Long characterId, SceneBatch batch) {
        if (batch.scenes() == null || batch.scenes().isEmpty()) return;

        Set<String> seen = new HashSet<>();
        for (TheaterScene scene : batch.scenes()) {
            String loc = scene.location();
            String time = scene.time();
            if (loc == null || loc.isBlank()) continue;
            String key = loc + "|" + (time == null ? "" : time);
            if (!seen.add(key)) continue;
            try {
                // resolveBackground는 cache hit 시 즉시 반환, miss 시 generating result 반환.
                // miss인 경우 그 시점에 generateBackgroundAsync로 비동기 생성을 트리거해야 한다.
                var result = backgroundGenerationService.resolveBackground(loc, null, time, characterId);
                if (result != null && !result.isCacheHit()) {
                    // miss → 백그라운드 생성 시작 (async, fire-and-forget)
                    backgroundGenerationService.generateBackgroundAsync(loc, null, time, characterId);
                }
            } catch (Exception e) {
                log.debug("🎭 [BG-PREFETCH] failed for {}/{}: {}", loc, time, e.getMessage());
            }
        }
        log.debug("🎭 [BG-PREFETCH] checked {} unique locations | charId={}", seen.size(), characterId);
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
                // [Phase 5.5 UX Polish · R1] innerNarration 필드는 의미상 protagonist의 속내로 정확히 사용.
                //   MongoDB 필드명은 그대로 유지(인덱스 호환), Java 변수의 의미만 정정.
                .innerNarration(scene.protagonistInner())
                .heroineInner(scene.heroineInner())
                .dialogue(scene.dialogue())
                .speakerType(speakerType)
                .speakerName(speakerName)
                .heroineId(heroineId)
                .sceneType(scene.sceneType())
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

    private LlmSceneBatchOutput invokeLlm(String systemPrompt, Character speaker,
                                          com.spring.aichat.domain.user.User user,
                                          TheaterState state,
                                          boolean justBranched, boolean isLastChapter) {
        // [Phase III · 작업 3] ModelResolver가 정책 + 캐릭터 우선순위를 통합 결정
        String model = modelResolver.resolveBatchModel(
            user, speaker, state, justBranched, isLastChapter);

        log.info("🎭 [BATCH-GEN] model={} | speaker={} | justBranched={} | lastChapter={}",
            model, speaker.getName(), justBranched, isLastChapter);

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
        // [Polish-v2] 히로인의 허용 location/outfit enum 세트 미리 캐싱
        // LLM이 임의 문자열을 반환할 경우 기본값으로 폴백하여 BackgroundDisplay/CharacterDisplay가 정상 동작하도록
        Set<String> allowedLocations = null;
        Set<String> allowedOutfits = null;
        String fallbackLocation = speaker.getEffectiveDefaultLocation();
        String fallbackOutfit = speaker.getEffectiveDefaultOutfit();
        try {
            allowedLocations = speaker.getAllowedLocations(RelationStatus.STRANGER, false);
            allowedOutfits = speaker.getAllowedOutfits(RelationStatus.STRANGER, false);
        } catch (Exception ignored) {
            // 세트 조회 실패 시 sanitize 스킵
        }

        List<TheaterScene> scenes = new ArrayList<>();
        int seq = 0;
        for (LlmSceneBatchOutput.LlmScene s : out.scenes()) {
            String sanitizedLocation = sanitizeEnumValue(s.location(), allowedLocations, fallbackLocation);
            String sanitizedOutfit = sanitizeEnumValue(s.outfit(), allowedOutfits, fallbackOutfit);
            String sanitizedTime = sanitizeTime(s.time());
            String sanitizedBgm = sanitizeBgmMode(s.bgmMode());

            // [Phase 5.5 UX Polish · R1] 속내 필드 진화 처리
            //   - 우선순위: protagonist_inner > inner_narration (legacy fallback)
            //   - heroine_inner는 그대로 보존 (UI는 미노출)
            //   - 응답 호환을 위해 innerNarration alias도 동일 값으로 채움
            String resolvedProtagonistInner = s.resolvedProtagonistInner();
            String resolvedSceneType = s.sceneType(); // null 가능 — LLM이 빠뜨려도 무방

            scenes.add(new TheaterScene(
                seq++,
                resolveSpeakerName(s.speaker(), speaker, state),
                s.narration(),
                resolvedProtagonistInner,        // 신규: protagonistInner
                s.heroineInner(),                // 신규: heroineInner (백엔드 자산, UI 미노출)
                resolvedProtagonistInner,        // alias: innerNarration (구버전 클라이언트)
                s.dialogue(),
                resolvedSceneType,               // 신규: sceneType
                s.emotion(),
                sanitizedLocation,
                sanitizedTime,
                sanitizedOutfit,
                sanitizedBgm,
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Polish-v2] Enum 필드 sanitize
    //  LLM이 임의 문자열을 반환해도 에셋 해상도가 깨지지 않도록 방어
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 허용 enum 세트에 있으면 그대로, 없으면 fallback으로 대체. 세트가 null이면 raw 그대로. */
    private String sanitizeEnumValue(String raw, Set<String> allowed, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        if (allowed == null || allowed.isEmpty()) return raw;
        String trimmed = raw.trim();
        // 대소문자 무시 매칭
        for (String a : allowed) {
            if (a.equalsIgnoreCase(trimmed)) return a;
        }
        log.debug("🎭 [SANITIZE] enum out-of-range | raw='{}' | fallback='{}'", raw, fallback);
        return fallback != null ? fallback : (allowed.iterator().next());
    }

    /** TimeOfDay enum 검증 — 시스템이 쓰는 표준 세트로 한정 */
    private static final Set<String> VALID_TIMES = Set.of(
        "DAY", "NIGHT", "DAWN", "SUNSET", "MORNING", "AFTERNOON", "EVENING"
    );

    private String sanitizeTime(String raw) {
        if (raw == null || raw.isBlank()) return "NIGHT";
        String upper = raw.trim().toUpperCase();
        return VALID_TIMES.contains(upper) ? upper : "NIGHT";
    }

    /** BgmMode enum 검증 */
    private static final Set<String> VALID_BGM = Set.of(
        "DAILY", "ROMANTIC", "EXCITING", "TOUCHING", "TENSE", "EROTIC"
    );

    private String sanitizeBgmMode(String raw) {
        if (raw == null || raw.isBlank()) return "DAILY";
        String upper = raw.trim().toUpperCase();
        return VALID_BGM.contains(upper) ? upper : "DAILY";
    }
}