package com.spring.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EasterEggType;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.RelationStatus;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.achievement.AchievementResponse;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.dto.chat.ChatRoomInfoResponse;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.dto.chat.SendChatResponse.EndingTrigger;
import com.spring.aichat.dto.chat.SendChatResponse.PromotionEvent;
import com.spring.aichat.dto.chat.SendChatResponse.UnlockInfo;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ContentModerationException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.payment.BoostModeResolver;
import com.spring.aichat.service.payment.SecretModeService;
import com.spring.aichat.service.prompt.CharacterPromptAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 채팅 핵심 서비스
 * <p>
 * [Phase 3]     트랜잭션 분리 + Smart RAG Skip + Redis 캐싱
 * [Phase 4]     Scene direction fields
 * [Phase 4.1]   씬 상태 영속화 + BGM 관성 시스템
 * [Phase 4.2]   관계 승급 이벤트 시스템
 * [Phase 4.3]   엔딩 트리거 감지 + 히스토리 정제(reasoning 오염 차단)
 * [Phase 5.1]   LLM 실패 시 유저 메시지 + 에너지 롤백
 *               유저 평가(LIKE/DISLIKE) 시스템
 *               개별 대화 삭제 기능
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final CharacterPromptAssembler promptAssembler;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;
    private final TransactionTemplate txTemplate;
    private final RedisCacheService cacheService;
    private final AchievementService achievementService;
    private final BoostModeResolver boostModeResolver;
    private final PromptInjectionGuard injectionGuard;
    private final ContentModerationService contentModerationService;
    private final UserRepository userRepository;        // [Phase 5.1] 에너지 환불용
    private final SecretModeService secretModeService;    // [Phase 5 Fix] 런타임 시크릿 모드 검증

    private static final long USER_TURN_MEMORY_CYCLE = 10;
    private static final long RAG_SKIP_LOG_THRESHOLD = USER_TURN_MEMORY_CYCLE * 2;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  TX 간 데이터 전달용 내부 DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record PreProcessResult(
        ChatRoom room,
        Long userId,
        long logCount,
        boolean wasPromotionPending,
        String username,
        String savedUserLogId,   // [Phase 5.1] 롤백용 MongoDB 문서 ID
        int energyCost           // [Phase 5.1] 롤백용 차감된 에너지량
    ) {
    }

    private record LlmResult(
        AiJsonOutput aiOutput,
        String cleanJson,
        String combinedDialogue,
        EmotionTag mainEmotion,
        List<SendChatResponse.SceneResponse> sceneResponses,
        String lastBgmMode,
        String lastLocation,
        String lastOutfit,
        String lastTimeOfDay,
        Integer moodScore,
        String easterEggTrigger
    ) {
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  유저 채팅 메시지 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SendChatResponse sendMessage(Long roomId, String userMessage) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱ [PERF] ====== sendMessage START ====== roomId={}", roomId);

        // ── [Phase 5] Content Moderation — TX-1 전에 실행 (에너지 차감 전 차단) ──
        {
            ChatRoom roomForCheck = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
            // [Phase 5 Fix] 런타임 시크릿 모드 검증 — User 플래그만으로 판단하지 않음
            boolean isSecret = roomForCheck.getUser().getIsSecretMode()
                && secretModeService.canAccessSecretMode(
                roomForCheck.getUser(), roomForCheck.getCharacter().getId());

            ContentModerationService.ModerationVerdict verdict =
                contentModerationService.moderate(userMessage, isSecret);

            if (!verdict.passed()) {
                log.info("⛔ [MODERATION] Message blocked: roomId={}, step={}, category={}",
                    roomId, verdict.blockedAtStep(), verdict.category());
                throw new ContentModerationException(
                    verdict.userMessage(), verdict.category(), verdict.blockedAtStep());
            }
        }

        // ── TX-1: 전처리 ──
        long tx1Start = System.currentTimeMillis();
        PreProcessResult pre = txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

            // [Phase 5.1] 에너지 비용 계산 및 저장 (롤백용)
            int cost = boostModeResolver.resolveEnergyCost(room.getChatMode(), room.getUser());
            room.getUser().consumeEnergy(cost);

            // [Phase 5.1] 유저 메시지 저장 후 ID 캡처 (롤백용)
            ChatLogDocument savedLog = chatLogRepository.save(ChatLogDocument.user(room.getId(), userMessage));
            long logCount = chatLogRepository.countByRoomId(roomId);

            return new PreProcessResult(
                room, room.getUser().getId(), logCount,
                room.isPromotionPending(), room.getUser().getUsername(),
                savedLog.getId(), cost    // [Phase 5.1] 롤백 정보
            );
        });

        PromptInjectionGuard.InjectionCheckResult injCheck =
            injectionGuard.checkChatMessage(userMessage, pre.username());
        if (injCheck.detected()) {
            log.warn("⚠️ [INJECTION] Detected in chat: user={}, severity={}, pattern={}",
                pre.username(), injCheck.severity(), injCheck.matchedPattern());
        }

        log.info("⏱ [PERF] TX-1 (preprocess): {}ms | promotionPending={}",
            System.currentTimeMillis() - tx1Start, pre.wasPromotionPending());

        cacheService.evictUserProfile(pre.username());

        // ── [Phase 5.1] Non-TX Zone: LLM 호출 — 실패 시 롤백 ──
        LlmResult llmResult;
        try {
            llmResult = callLlmAndParse(pre.room(), pre.logCount(), userMessage);
        } catch (Exception e) {
            // ⚡ LLM 호출 실패 → 유저 메시지 삭제 + 에너지 환불
            log.error("❌ [ROLLBACK] LLM call failed — rolling back user message and energy | roomId={}", roomId, e);
            rollbackPreProcess(pre);
            throw e;  // 원래 예외를 그대로 다시 던져서 프론트에 에러 전달
        }

        // ── TX-2: 후처리 (승급 + 엔딩 감지) ──
        long tx2Start = System.currentTimeMillis();
        boolean isStory = pre.room().isStoryMode();
        SendChatResponse response;
        try {
            response = txTemplate.execute(status -> {
                ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                PromotionEvent promoEvent = null;
                if (isStory) {
                    promoEvent = resolveAffectionAndPromotion(
                        freshRoom,
                        llmResult.aiOutput().affectionChange(),
                        llmResult.moodScore(),
                        pre.wasPromotionPending()
                    );
                } else {
                    applyAffectionChange(freshRoom, llmResult.aiOutput().affectionChange());
                }

                saveLog(
                    freshRoom,
                    ChatRole.ASSISTANT,
                    llmResult.cleanJson(),
                    llmResult.combinedDialogue(),
                    llmResult.mainEmotion(),
                    null
                );

                if (isStory) {
                    freshRoom.updateSceneState(
                        llmResult.lastBgmMode(),
                        llmResult.lastLocation(),
                        llmResult.lastOutfit(),
                        llmResult.lastTimeOfDay()
                    );
                }

                EndingTrigger endingTrigger = null;
                if (isStory) {
                    if (!freshRoom.isEndingReached()) {
                        if (freshRoom.getAffectionScore() >= 100) {
                            endingTrigger = new EndingTrigger("HAPPY");
                            log.info("🎬 [ENDING] HAPPY ending triggered! affection={} | roomId={}",
                                freshRoom.getAffectionScore(), roomId);
                        } else if (freshRoom.getAffectionScore() <= -100) {
                            endingTrigger = new EndingTrigger("BAD");
                            log.info("🎬 [ENDING] BAD ending triggered! affection={} | roomId={}",
                                freshRoom.getAffectionScore(), roomId);
                        }
                    }
                }

                SendChatResponse.EasterEggEvent easterEggEvent = null;
                if (llmResult.easterEggTrigger() != null && !llmResult.easterEggTrigger().isBlank()) {
                    try {
                        EasterEggType eggType = EasterEggType.valueOf(llmResult.easterEggTrigger().toUpperCase());

                        AchievementResponse.UnlockNotification unlock =
                            achievementService.unlockEasterEgg(pre.userId(), eggType);

                        boolean revertAfter = (eggType == EasterEggType.FOURTH_WALL);

                        easterEggEvent = new SendChatResponse.EasterEggEvent(
                            eggType.name(),
                            new SendChatResponse.AchievementInfo(
                                unlock.code(), unlock.title(), unlock.titleKo(),
                                unlock.description(), unlock.icon(), unlock.isNew()
                            ),
                            revertAfter
                        );

                        log.info("🥚 [EASTER_EGG] Triggered: {} | new={} | roomId={}",
                            eggType.name(), unlock.isNew(), roomId);

                    } catch (IllegalArgumentException ex) {
                        log.warn("🥚 [EASTER_EGG] Unknown trigger: {}", llmResult.easterEggTrigger());
                    }
                }

                return new SendChatResponse(
                    roomId,
                    llmResult.sceneResponses(),
                    freshRoom.getAffectionScore(),
                    freshRoom.getStatusLevel().name(),
                    promoEvent,
                    endingTrigger,
                    easterEggEvent
                );
            });
        } catch (Exception e) {
            // [Phase 5.1] TX-2 실패 시에도 롤백 (ASSISTANT 로그는 TX-2 안에서 저장되므로 JPA 롤백됨)
            // 하지만 유저 메시지는 MongoDB에 이미 저장된 상태 → 삭제 필요
            log.error("❌ [ROLLBACK] TX-2 failed — rolling back user message and energy | roomId={}", roomId, e);
            rollbackPreProcess(pre);
            throw e;
        }

        log.info("⏱ [PERF] TX-2 (postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        cacheService.evictRoomInfo(roomId);

        log.info("⏱ [PERF] ====== sendMessage DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        triggerMemorySummarizationIfNeeded(roomId, pre.userId(), pre.logCount());

        return response;
    }

    /**
     * [Phase 5.1] LLM 호출 실패 시 TX-1 결과 롤백
     *
     * 1. MongoDB에 저장된 유저 메시지 삭제
     * 2. JPA 새 트랜잭션으로 에너지 환불
     */
    private void rollbackPreProcess(PreProcessResult pre) {
        // 1. MongoDB 유저 메시지 삭제
        try {
            if (pre.savedUserLogId() != null) {
                chatLogRepository.deleteById(pre.savedUserLogId());
                log.info("🔄 [ROLLBACK] User message deleted from MongoDB: logId={}", pre.savedUserLogId());
            }
        } catch (Exception ex) {
            log.error("🔄 [ROLLBACK] Failed to delete user message: logId={}", pre.savedUserLogId(), ex);
        }

        // 2. 에너지 환불 (새 JPA 트랜잭션)
        try {
            txTemplate.execute(status -> {
                User user = userRepository.findById(pre.userId())
                    .orElseThrow(() -> new NotFoundException("User not found for refund"));
                user.refundEnergy(pre.energyCost());
                userRepository.save(user);
                log.info("🔄 [ROLLBACK] Energy refunded: userId={}, amount={}", pre.userId(), pre.energyCost());
                return null;
            });
        } catch (Exception ex) {
            log.error("🔄 [ROLLBACK] Failed to refund energy: userId={}, amount={}",
                pre.userId(), pre.energyCost(), ex);
        }

        // 3. 캐시 무효화 (에너지가 변경되었으므로)
        cacheService.evictUserProfile(pre.username());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4.2] 승급 이벤트 핵심 로직
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private PromotionEvent resolveAffectionAndPromotion(
        ChatRoom room, int affectionChange, Integer moodScore, boolean wasPending) {

        if (wasPending) {
            int resolvedMood = (moodScore != null) ? moodScore : 1;
            if (moodScore == null) {
                log.warn("[PROMOTION] mood_score is NULL - LLM omitted it. Defaulting to 1 | roomId={}", room.getId());
            }
            room.advancePromotionTurn(resolvedMood);
            log.info("🎯 [PROMOTION] Turn {}/{} | moodScore +{} (total: {}) | roomId={}",
                room.getPromotionTurnCount(), RelationStatusPolicy.PROMOTION_MAX_TURNS,
                resolvedMood, room.getPromotionMoodScore(), room.getId());

            if (room.getPromotionTurnCount() >= RelationStatusPolicy.PROMOTION_MAX_TURNS) {
                return resolvePromotionResult(room);
            }

            RelationStatus target = room.getPendingTargetStatus();
            return new PromotionEvent(
                "IN_PROGRESS",
                target.name(),
                RelationStatusPolicy.getDisplayName(target),
                RelationStatusPolicy.PROMOTION_MAX_TURNS - room.getPromotionTurnCount(),
                room.getPromotionMoodScore(),
                null
            );

        } else {
            RelationStatus oldStatus = room.getStatusLevel();
            applyAffectionChange(room, affectionChange);
            RelationStatus newStatus = RelationStatusPolicy.fromScore(room.getAffectionScore());

            if (RelationStatusPolicy.isUpgrade(oldStatus, newStatus)) {
                log.info("🎯 [PROMOTION] Upgrade detected: {} → {} | affection={} | roomId={}",
                    oldStatus, newStatus, room.getAffectionScore(), room.getId());

                int thresholdEdge = RelationStatusPolicy.getThresholdScore(newStatus) - 1;
                room.updateAffection(thresholdEdge);
                room.updateStatusLevel(oldStatus);
                room.startPromotion(newStatus);

                log.info("🎯 [PROMOTION] Affection rolled back to {} (threshold edge) | roomId={}",
                    thresholdEdge, room.getId());

                return new PromotionEvent(
                    "STARTED",
                    newStatus.name(),
                    RelationStatusPolicy.getDisplayName(newStatus),
                    RelationStatusPolicy.PROMOTION_MAX_TURNS,
                    0,
                    null
                );
            }

            return null;
        }
    }

    private PromotionEvent resolvePromotionResult(ChatRoom room) {
        int totalMood = room.getPromotionMoodScore();
        RelationStatus target = room.getPendingTargetStatus();
        boolean success = totalMood >= RelationStatusPolicy.PROMOTION_SUCCESS_THRESHOLD;

        log.info("🎯 [PROMOTION] RESULT: {} | mood={}/{} | target={} | roomId={}",
            success ? "SUCCESS" : "FAILURE",
            totalMood, RelationStatusPolicy.PROMOTION_SUCCESS_THRESHOLD,
            target, room.getId());

        if (success) {
            room.completePromotionSuccess();
            int thresholdScore = RelationStatusPolicy.getThresholdScore(target);
            room.updateAffection(thresholdScore);

            List<UnlockInfo> unlocks = room.getCharacter().getUnlocksForRelation(target)
                .stream()
                .map(u -> new UnlockInfo(u.type(), u.name(), u.displayName()))
                .collect(Collectors.toList());

            return new PromotionEvent(
                "SUCCESS", target.name(), RelationStatusPolicy.getDisplayName(target),
                0, totalMood, unlocks
            );
        } else {
            room.completePromotionFailure();
            int penalty = RelationStatusPolicy.PROMOTION_FAILURE_PENALTY;
            int thresholdEdge = RelationStatusPolicy.getThresholdScore(target) - 1;
            int penalizedScore = Math.max(0, thresholdEdge - penalty);
            room.updateAffection(penalizedScore);
            room.updateStatusLevel(RelationStatusPolicy.fromScore(penalizedScore));

            return new PromotionEvent(
                "FAILURE", target.name(), RelationStatusPolicy.getDisplayName(target),
                0, totalMood, null
            );
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  시스템 이벤트에 대한 캐릭터 반응 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SendChatResponse generateResponseForSystemEvent(Long roomId, String systemDetail, int energyCost) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱ [PERF] ====== systemEvent START ====== roomId={}", roomId);

        long tx1Start = System.currentTimeMillis();
        PreProcessResult pre = txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("ChatRoom not found: " + roomId));

            if (energyCost > 0) {
                room.getUser().consumeEnergy(energyCost);
            }

            ChatLogDocument savedLog = chatLogRepository.save(ChatLogDocument.user(room.getId(), systemDetail));
            long logCount = chatLogRepository.countByRoomId(roomId);

            return new PreProcessResult(
                room, room.getUser().getId(), logCount,
                room.isPromotionPending(), room.getUser().getUsername(),
                savedLog.getId(), energyCost
            );
        });
        log.info("⏱ [PERF] TX-1 (event preprocess): {}ms", System.currentTimeMillis() - tx1Start);

        cacheService.evictUserProfile(pre.username());

        String ragQuery = fetchLastUserMessage(roomId);

        // [Phase 5.1] 시스템 이벤트도 LLM 실패 시 롤백
        LlmResult llmResult;
        try {
            llmResult = callLlmAndParse(pre.room(), pre.logCount(), ragQuery);
        } catch (Exception e) {
            log.error("❌ [ROLLBACK] LLM call failed for system event | roomId={}", roomId, e);
            rollbackPreProcess(pre);
            throw e;
        }

        long tx2Start = System.currentTimeMillis();
        SendChatResponse response = txTemplate.execute(status -> {
            ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

            applyAffectionChange(freshRoom, llmResult.aiOutput().affectionChange());
            saveLog(freshRoom, ChatRole.ASSISTANT,
                llmResult.cleanJson(), llmResult.combinedDialogue(), llmResult.mainEmotion(), null);

            freshRoom.updateSceneState(
                llmResult.lastBgmMode(), llmResult.lastLocation(),
                llmResult.lastOutfit(), llmResult.lastTimeOfDay()
            );

            return new SendChatResponse(
                roomId,
                llmResult.sceneResponses(),
                freshRoom.getAffectionScore(),
                freshRoom.getStatusLevel().name()
            );
        });
        log.info("⏱ [PERF] TX-2 (event postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        cacheService.evictRoomInfo(roomId);

        log.info("⏱ [PERF] ====== systemEvent DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        return response;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Non-TX 공통 로직
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private LlmResult callLlmAndParse(ChatRoom room, long logCount, String ragQuery) {
        String longTermMemory = "";
        if (logCount >= RAG_SKIP_LOG_THRESHOLD && ragQuery != null && !ragQuery.isEmpty()) {
            long ragStart = System.currentTimeMillis();
            try {
                longTermMemory = memoryService.retrieveContext(room.getUser().getId(), ragQuery);
            } catch (Exception e) {
                log.warn("⏱ [PERF] RAG failed (non-blocking): {}", e.getMessage());
            }
            log.info("⏱ [PERF] RAG: {}ms | found={}",
                System.currentTimeMillis() - ragStart, !longTermMemory.isEmpty());
        } else {
            log.info("⏱ [PERF] RAG SKIPPED (logCount={} < threshold={})", logCount, RAG_SKIP_LOG_THRESHOLD);
        }

        // [Phase 5 Fix] 런타임 시크릿 모드 결정
        boolean effectiveSecretMode = room.getUser().getIsSecretMode()
            && secretModeService.canAccessSecretMode(
            room.getUser(), room.getCharacter().getId());

        String systemPrompt = promptAssembler.assembleSystemPrompt(
            room.getCharacter(), room, room.getUser(), longTermMemory, effectiveSecretMode
        );

        List<OpenAiMessage> messages = buildMessageHistory(room.getId(), systemPrompt);

        String model = boostModeResolver.resolveModel(room.getUser());
        long llmStart = System.currentTimeMillis();
        log.info("⏱ [PERF] LLM call START | model={} | messages={} | promptChars={}",
            model, messages.size(),
            messages.stream().mapToInt(m -> m.content().length()).sum());

        String rawAssistant = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.8)
        );
        log.info("⏱ [PERF] LLM call DONE: {}ms | responseChars={}",
            System.currentTimeMillis() - llmStart, rawAssistant.length());

        try {
            String cleanJson = stripMarkdown(rawAssistant);
            AiJsonOutput aiOutput = objectMapper.readValue(cleanJson, AiJsonOutput.class);

            String combinedDialogue = aiOutput.scenes().stream()
                .map(AiJsonOutput.Scene::dialogue)
                .collect(Collectors.joining(" "));

            String lastEmotionStr = aiOutput.scenes().isEmpty() ? "NEUTRAL"
                : aiOutput.scenes().get(aiOutput.scenes().size() - 1).emotion();
            EmotionTag mainEmotion = parseEmotion(lastEmotionStr);

            List<SendChatResponse.SceneResponse> sceneResponses = aiOutput.scenes().stream()
                .map(s -> new SendChatResponse.SceneResponse(
                    s.narration(),
                    s.dialogue(),
                    parseEmotion(s.emotion()),
                    safeUpperCase(s.location()),
                    safeUpperCase(s.time()),
                    safeUpperCase(s.outfit()),
                    safeUpperCase(s.bgmMode())
                ))
                .collect(Collectors.toList());

            String lastBgm = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::bgmMode);
            String lastLoc = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::location);
            String lastOutfit = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::outfit);
            String lastTime = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::time);

            Integer moodScore = aiOutput.moodScore();

            String easterEggTrigger = aiOutput.easterEggTrigger();

            return new LlmResult(aiOutput, cleanJson, combinedDialogue, mainEmotion, sceneResponses,
                lastBgm, lastLoc, lastOutfit, lastTime, moodScore, easterEggTrigger);

        } catch (JsonProcessingException e) {
            log.error("JSON Parsing Error: {}", rawAssistant, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 응답 형식이 올바르지 않습니다.");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  채팅방 관리 영역
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public ChatRoomInfoResponse getChatRoomInfo(Long roomId) {
        return cacheService.getRoomInfo(roomId, ChatRoomInfoResponse.class)
            .orElseGet(() -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

                var character = room.getCharacter();
                // [Phase 5 Fix] 런타임 검증된 시크릿 모드
                boolean isSecret = room.getUser().getIsSecretMode()
                    && secretModeService.canAccessSecretMode(
                    room.getUser(), room.getCharacter().getId());
                ChatRoomInfoResponse response = new ChatRoomInfoResponse(
                    room.getId(),
                    character.getName(),
                    character.getSlug(),
                    character.getId(),
                    character.getDefaultImageUrl(),
                    "background_default.png",
                    room.getAffectionScore(),
                    room.getStatusLevel().name(),
                    room.getChatMode().name(),
                    room.getCurrentBgmMode() != null ? room.getCurrentBgmMode().name() : "DAILY",
                    room.getCurrentLocation() != null ? room.getCurrentLocation().name() : character.getEffectiveDefaultLocation(),
                    room.getCurrentOutfit() != null ? room.getCurrentOutfit().name() : character.getEffectiveDefaultOutfit(),
                    room.getCurrentTimeOfDay() != null ? room.getCurrentTimeOfDay().name() : "NIGHT",
                    character.getEffectiveDefaultOutfit(),
                    character.getEffectiveDefaultLocation(),
                    room.isEndingReached(),
                    room.getEndingType() != null ? room.getEndingType().name() : null,
                    room.getEndingTitle(),
                    new java.util.ArrayList<>(character.getAllowedOutfits(room.getStatusLevel(), isSecret)),
                    new java.util.ArrayList<>(character.getAllowedLocations(room.getStatusLevel(), isSecret))
                );

                cacheService.cacheRoomInfo(roomId, response);
                log.debug("🏠 [CACHE] ChatRoomInfo cached: roomId={}", roomId);
                return response;
            });
    }

    @Transactional
    public void deleteChatRoom(Long roomId) {
        chatLogRepository.deleteByRoomId(roomId);
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow(
            () -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId)
        );
        room.resetAll();

        cacheService.evictRoomInfo(roomId);
        cacheService.evictRoomOwner(roomId);
    }

    @Transactional
    public void initializeChatRoom(Long roomId) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("Room not found"));

        if (chatLogRepository.countByRoomId(roomId) > 0) return;

        var character = room.getCharacter();
        String introNarration = character.getIntroNarration();

        chatLogRepository.save(ChatLogDocument.system(room.getId(), introNarration));

        String firstGreeting = character.getFirstGreeting();
        ChatLogDocument assistantLog = ChatLogDocument.of(
            room.getId(), ChatRole.ASSISTANT, firstGreeting, firstGreeting, EmotionTag.NEUTRAL, null);
        chatLogRepository.save(assistantLog);

        room.updateLastActive(EmotionTag.NEUTRAL);
        room.resetSceneState();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.1] 유저 평가 시스템 (RLHF 데이터 수집)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * ASSISTANT 메시지에 좋아요/싫어요 평가를 토글
     *
     * @param logId  MongoDB 문서 ID
     * @param roomId 소유권 검증용
     * @param rating "LIKE" | "DISLIKE"
     * @return 업데이트된 rating 값 (토글 해제 시 null)
     */
    public String rateChatLog(String logId, Long roomId, String rating) {
        ChatLogDocument doc = chatLogRepository.findById(logId)
            .orElseThrow(() -> new NotFoundException("채팅 로그를 찾을 수 없습니다."));

        // 소유권 검증: 해당 로그가 요청된 방에 속하는지 확인
        if (!doc.getRoomId().equals(roomId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "해당 채팅방의 로그가 아닙니다.");
        }

        // ASSISTANT 메시지만 평가 가능
        if (doc.getRole() != ChatRole.ASSISTANT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "캐릭터 응답에만 평가할 수 있습니다.");
        }

        doc.updateRating(rating);
        chatLogRepository.save(doc);

        log.info("⭐ [RATING] logId={}, roomId={}, rating={} → {}",
            logId, roomId, rating, doc.getRating());

        return doc.getRating();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.1] 개별 대화 삭제
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 단건 채팅 로그 삭제
     *
     * USER 또는 ASSISTANT 메시지를 개별 삭제한다.
     * SYSTEM 메시지(나레이션)는 삭제 불가.
     *
     * @param logId  MongoDB 문서 ID
     * @param roomId 소유권 검증용
     */
    public void deleteSingleChatLog(String logId, Long roomId) {
        ChatLogDocument doc = chatLogRepository.findById(logId)
            .orElseThrow(() -> new NotFoundException("채팅 로그를 찾을 수 없습니다."));

        // 소유권 검증
        if (!doc.getRoomId().equals(roomId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "해당 채팅방의 로그가 아닙니다.");
        }

        // SYSTEM 메시지는 삭제 불가 (인트로 나레이션 등 구조적 데이터)
        if (doc.getRole() == ChatRole.SYSTEM) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "시스템 메시지는 삭제할 수 없습니다.");
        }

        chatLogRepository.deleteById(logId);
        log.info("🗑️ [DELETE] Single log deleted: logId={}, roomId={}, role={}",
            logId, roomId, doc.getRole());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 헬퍼 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String fetchLastUserMessage(Long roomId) {
        return chatLogRepository
            .findTop1ByRoomIdAndRoleOrderByCreatedAtDesc(roomId, ChatRole.USER)
            .map(ChatLogDocument::getCleanContent)
            .orElse("");
    }

    private void triggerMemorySummarizationIfNeeded(Long roomId, Long userId, long totalLogCount) {
        long userMsgCount = chatLogRepository.countByRoomIdAndRole(roomId, ChatRole.USER);

        if (userMsgCount > 0 && userMsgCount % USER_TURN_MEMORY_CYCLE == 0) {
            log.info("🧠 [MEMORY] Summarization TRIGGERED | roomId={} | userMsgCount={}",
                roomId, userMsgCount);
            memoryService.summarizeAndSaveMemory(roomId, userId);
        }
    }

    /**
     * [Phase 4.3] 히스토리 구성 — ASSISTANT 로그에서 reasoning 오염 차단
     */
    private List<OpenAiMessage> buildMessageHistory(Long roomId, String systemPrompt) {
        List<ChatLogDocument> recent = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(systemPrompt));

        for (ChatLogDocument chatLog : recent) {
            switch (chatLog.getRole()) {
                case USER -> messages.add(OpenAiMessage.user(chatLog.getRawContent()));
                case ASSISTANT -> {
                    String sanitized = buildSanitizedAssistantContent(chatLog);
                    messages.add(OpenAiMessage.assistant(sanitized));
                }
                case SYSTEM -> messages.add(
                    OpenAiMessage.user("[NARRATION]\n" + chatLog.getRawContent())
                );
            }
        }

        return messages;
    }

    private String buildSanitizedAssistantContent(ChatLogDocument chatLog) {
        try {
            String raw = chatLog.getRawContent();
            if (raw == null || raw.isBlank()) {
                return chatLog.getCleanContent() != null ? chatLog.getCleanContent() : "";
            }

            String cleaned = stripMarkdown(raw);
            AiJsonOutput parsed = objectMapper.readValue(cleaned, AiJsonOutput.class);

            StringBuilder sb = new StringBuilder();
            for (AiJsonOutput.Scene scene : parsed.scenes()) {
                if (scene.narration() != null && !scene.narration().isBlank()) {
                    sb.append("(").append(scene.narration().trim()).append(") ");
                }
                if (scene.dialogue() != null && !scene.dialogue().isBlank()) {
                    sb.append(scene.dialogue().trim());
                }
                if (scene.emotion() != null) {
                    sb.append(" [").append(scene.emotion()).append("]");
                }
                sb.append("\n");
            }

            String result = sb.toString().trim();
            return result.isEmpty()
                ? (chatLog.getCleanContent() != null ? chatLog.getCleanContent() : "")
                : result;

        } catch (Exception e) {
            return chatLog.getCleanContent() != null ? chatLog.getCleanContent() : chatLog.getRawContent();
        }
    }

    private void applyAffectionChange(ChatRoom room, int change) {
        if (change == 0) return;
        int newScore = room.getAffectionScore() + change;
        newScore = Math.max(-100, Math.min(100, newScore));
        room.updateAffection(newScore);
        room.updateStatusLevel(RelationStatusPolicy.fromScore(newScore));
    }

    private void saveLog(ChatRoom room, ChatRole role, String raw, String clean,
                         EmotionTag emotion, String audioUrl) {
        ChatLogDocument chatLog = ChatLogDocument.of(room.getId(), role, raw, clean, emotion, audioUrl);
        chatLogRepository.save(chatLog);
        room.updateLastActive(emotion);
    }

    private String stripMarkdown(String text) {
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    private EmotionTag parseEmotion(String emotionStr) {
        try {
            return EmotionTag.valueOf(emotionStr.toUpperCase());
        } catch (Exception e) {
            return EmotionTag.NEUTRAL;
        }
    }

    private String safeUpperCase(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value.toUpperCase().trim();
    }

    private String extractLastNonNull(
        List<SendChatResponse.SceneResponse> scenes,
        java.util.function.Function<SendChatResponse.SceneResponse, String> extractor) {
        for (int i = scenes.size() - 1; i >= 0; i--) {
            String val = extractor.apply(scenes.get(i));
            if (val != null) return val;
        }
        return null;
    }
}