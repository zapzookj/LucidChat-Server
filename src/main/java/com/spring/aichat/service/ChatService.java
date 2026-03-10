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
 * [Phase 5.2]   분산 트랜잭션 보상 패턴 (JPA/MongoDB 쓰기 분리)
 *               RLHF 싫어요 사유(dislikeReason) 추가
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

    /**
     * [Phase 5.2] JPA TX-1 결과 — MongoDB 관련 필드 분리
     */
    private record JpaPreResult(
        ChatRoom room,
        Long userId,
        long logCount,
        boolean wasPromotionPending,
        String username,
        int energyCost
    ) {}

    /**
     * [Phase 5.2] 보상 트랜잭션용 롤백 컨텍스트
     */
    private record RollbackContext(
        Long userId,
        String username,
        int energyCost,
        String savedUserLogId   // MongoDB 문서 ID (null이면 아직 저장 안 됨)
    ) {}

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
    //
    //  [Phase 5.2] 분산 트랜잭션 보상 패턴
    //  MongoDB 쓰기는 반드시 JPA 커밋 성공 후에 실행
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

        // ── TX-1: JPA only (에너지 차감) ──
        long tx1Start = System.currentTimeMillis();
        JpaPreResult jpa = txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

            int cost = boostModeResolver.resolveEnergyCost(room.getChatMode(), room.getUser());
            room.getUser().consumeEnergy(cost);

            // [Phase 5.2] MongoDB countByRoomId는 읽기 전용이므로 TX 내에서 허용
            long logCount = chatLogRepository.countByRoomId(roomId);

            return new JpaPreResult(
                room, room.getUser().getId(), logCount,
                room.isPromotionPending(), room.getUser().getUsername(), cost
            );
        });

        log.info("⏱ [PERF] TX-1 (JPA preprocess): {}ms | promotionPending={}",
            System.currentTimeMillis() - tx1Start, jpa.wasPromotionPending());

        cacheService.evictUserProfile(jpa.username());

        // ── Prompt Injection Check (Non-blocking) ──
        PromptInjectionGuard.InjectionCheckResult injCheck =
            injectionGuard.checkChatMessage(userMessage, jpa.username());
        if (injCheck.detected()) {
            log.warn("⚠️ [INJECTION] Detected in chat: user={}, severity={}, pattern={}",
                jpa.username(), injCheck.severity(), injCheck.matchedPattern());
        }

        // ── [Phase 5.2] MongoDB: USER 메시지 저장 (JPA 커밋 성공 후) ──
        String savedUserLogId;
        try {
            ChatLogDocument savedLog = chatLogRepository.save(
                ChatLogDocument.user(jpa.room().getId(), userMessage));
            savedUserLogId = savedLog.getId();
        } catch (Exception e) {
            log.error("❌ [COMPENSATION] MongoDB USER save failed — refunding energy | roomId={}", roomId, e);
            compensateEnergy(jpa.userId(), jpa.energyCost(), jpa.username());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "메시지 저장에 실패했습니다.");
        }

        RollbackContext rollbackCtx = new RollbackContext(
            jpa.userId(), jpa.username(), jpa.energyCost(), savedUserLogId
        );

        // ── Non-TX Zone: LLM 호출 — 실패 시 보상 롤백 ──
        LlmResult llmResult;
        try {
            llmResult = callLlmAndParse(jpa.room(), jpa.logCount() + 1, userMessage);
        } catch (Exception e) {
            log.error("❌ [COMPENSATION] LLM call failed — rolling back | roomId={}", roomId, e);
            compensateFullRollback(rollbackCtx);
            throw e;
        }

        // ── TX-2: JPA only (호감도/씬/업적 업데이트) ──
        long tx2Start = System.currentTimeMillis();
        boolean isStory = jpa.room().isStoryMode();
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
                        jpa.wasPromotionPending()
                    );
                } else {
                    applyAffectionChange(freshRoom, llmResult.aiOutput().affectionChange());
                }

                // [Phase 5.2] JPA만 — MongoDB saveLog() 제거, updateLastActive만 수행
                freshRoom.updateLastActive(llmResult.mainEmotion());

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
                            achievementService.unlockEasterEgg(jpa.userId(), eggType);

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
            // [Phase 5.2] TX-2 JPA 실패 시 → USER 메시지 + 에너지 보상 롤백
            log.error("❌ [COMPENSATION] TX-2 failed — rolling back | roomId={}", roomId, e);
            compensateFullRollback(rollbackCtx);
            throw e;
        }

        log.info("⏱ [PERF] TX-2 (JPA postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        // ── [Phase 5.2] MongoDB: ASSISTANT 메시지 저장 (JPA 커밋 성공 후) ──
        try {
            ChatLogDocument assistantLog = ChatLogDocument.of(
                jpa.room().getId(), ChatRole.ASSISTANT,
                llmResult.cleanJson(), llmResult.combinedDialogue(),
                llmResult.mainEmotion(), null
            );
            chatLogRepository.save(assistantLog);
        } catch (Exception e) {
            // JPA 이미 커밋 — 유저에겐 응답 정상 전달, 히스토리에만 누락 (경미한 불일치)
            log.error("⚠️ [INCONSISTENCY] ASSISTANT log MongoDB save failed after JPA commit. " +
                "Response delivered to user but history may be incomplete. roomId={}", roomId, e);
        }

        cacheService.evictRoomInfo(roomId);

        log.info("⏱ [PERF] ====== sendMessage DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        triggerMemorySummarizationIfNeeded(roomId, jpa.userId(), jpa.logCount() + 1);

        return response;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.2] 보상 트랜잭션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 에너지만 환불 (MongoDB USER 메시지 저장 실패 시)
     */
    private void compensateEnergy(Long userId, int energyCost, String username) {
        try {
            txTemplate.execute(status -> {
                User user = userRepository.findById(userId)
                    .orElseThrow(() -> new NotFoundException("User not found for refund"));
                user.refundEnergy(energyCost);
                userRepository.save(user);
                log.info("🔄 [COMPENSATION] Energy refunded: userId={}, amount={}", userId, energyCost);
                return null;
            });
        } catch (Exception ex) {
            log.error("🔄 [COMPENSATION] Energy refund FAILED: userId={}, amount={}",
                userId, energyCost, ex);
        }
        cacheService.evictUserProfile(username);
    }

    /**
     * 전체 롤백: MongoDB USER 메시지 삭제 + 에너지 환불
     */
    private void compensateFullRollback(RollbackContext ctx) {
        // 1. MongoDB 유저 메시지 삭제
        if (ctx.savedUserLogId() != null) {
            try {
                chatLogRepository.deleteById(ctx.savedUserLogId());
                log.info("🔄 [COMPENSATION] User message deleted: logId={}", ctx.savedUserLogId());
            } catch (Exception ex) {
                log.error("🔄 [COMPENSATION] User message delete FAILED: logId={}",
                    ctx.savedUserLogId(), ex);
            }
        }

        // 2. 에너지 환불
        compensateEnergy(ctx.userId(), ctx.energyCost(), ctx.username());
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
    //  [Phase 5.2] 동일 보상 패턴 적용
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SendChatResponse generateResponseForSystemEvent(Long roomId, String systemDetail, int energyCost) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱ [PERF] ====== systemEvent START ====== roomId={}", roomId);

        // ── TX-1: JPA only ──
        long tx1Start = System.currentTimeMillis();
        JpaPreResult jpa = txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("ChatRoom not found: " + roomId));

            if (energyCost > 0) {
                room.getUser().consumeEnergy(energyCost);
            }

            long logCount = chatLogRepository.countByRoomId(roomId);

            return new JpaPreResult(
                room, room.getUser().getId(), logCount,
                room.isPromotionPending(), room.getUser().getUsername(), energyCost
            );
        });
        log.info("⏱ [PERF] TX-1 (event preprocess): {}ms", System.currentTimeMillis() - tx1Start);

        cacheService.evictUserProfile(jpa.username());

        // ── MongoDB: USER (system detail) 저장 (JPA 커밋 후) ──
        String savedLogId;
        try {
            ChatLogDocument savedLog = chatLogRepository.save(
                ChatLogDocument.user(jpa.room().getId(), systemDetail));
            savedLogId = savedLog.getId();
        } catch (Exception e) {
            log.error("❌ [COMPENSATION] MongoDB system detail save failed | roomId={}", roomId, e);
            compensateEnergy(jpa.userId(), jpa.energyCost(), jpa.username());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "이벤트 메시지 저장에 실패했습니다.");
        }

        RollbackContext rollbackCtx = new RollbackContext(
            jpa.userId(), jpa.username(), jpa.energyCost(), savedLogId
        );

        String ragQuery = fetchLastUserMessage(roomId);

        // ── LLM 호출 ──
        LlmResult llmResult;
        try {
            llmResult = callLlmAndParse(jpa.room(), jpa.logCount() + 1, ragQuery);
        } catch (Exception e) {
            log.error("❌ [COMPENSATION] LLM call failed for system event | roomId={}", roomId, e);
            compensateFullRollback(rollbackCtx);
            throw e;
        }

        // ── TX-2: JPA only ──
        long tx2Start = System.currentTimeMillis();
        SendChatResponse response;
        try {
            response = txTemplate.execute(status -> {
                ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                applyAffectionChange(freshRoom, llmResult.aiOutput().affectionChange());

                // [Phase 5.2] JPA만 — updateLastActive
                freshRoom.updateLastActive(llmResult.mainEmotion());

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
        } catch (Exception e) {
            log.error("❌ [COMPENSATION] TX-2 failed for system event | roomId={}", roomId, e);
            compensateFullRollback(rollbackCtx);
            throw e;
        }
        log.info("⏱ [PERF] TX-2 (event postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        // ── MongoDB: ASSISTANT 저장 (JPA 커밋 후) ──
        try {
            chatLogRepository.save(ChatLogDocument.of(
                jpa.room().getId(), ChatRole.ASSISTANT,
                llmResult.cleanJson(), llmResult.combinedDialogue(),
                llmResult.mainEmotion(), null));
        } catch (Exception e) {
            log.error("⚠️ [INCONSISTENCY] ASSISTANT log save failed for system event. roomId={}", roomId, e);
        }

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

    /**
     * [Phase 5.2] 초기화도 JPA → MongoDB 순서 보장
     */
    public void initializeChatRoom(Long roomId) {
        if (chatLogRepository.countByRoomId(roomId) > 0) return;

        // JPA TX: 방 상태 업데이트만
        Character character = txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
            room.updateLastActive(EmotionTag.NEUTRAL);
            room.resetSceneState();
            return room.getCharacter();
        });

        // MongoDB: 인트로 로그 저장 (JPA 커밋 성공 후)
        String introNarration = character.getIntroNarration();
        chatLogRepository.save(ChatLogDocument.system(roomId, introNarration));

        String firstGreeting = character.getFirstGreeting();
        chatLogRepository.save(ChatLogDocument.of(
            roomId, ChatRole.ASSISTANT, firstGreeting, firstGreeting, EmotionTag.NEUTRAL, null));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.1] 유저 평가 시스템 (RLHF 데이터 수집)
    //  [Phase 5.2] dislikeReason 추가
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * ASSISTANT 메시지에 좋아요/싫어요 평가를 토글
     *
     * @param logId         MongoDB 문서 ID
     * @param roomId        소유권 검증용
     * @param rating        "LIKE" | "DISLIKE"
     * @param dislikeReason "DISLIKE" 시 사유 카테고리 (nullable)
     * @return 업데이트된 rating 값 (토글 해제 시 null)
     */
    public String rateChatLog(String logId, Long roomId, String rating, String dislikeReason) {
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

        // [Phase 5.2] 싫어요 사유 저장 — DISLIKE일 때만 유효
        if ("DISLIKE".equals(doc.getRating()) && dislikeReason != null && !dislikeReason.isBlank()) {
            doc.updateDislikeReason(dislikeReason);
        } else {
            doc.updateDislikeReason(null);  // LIKE이거나 토글 해제 시 사유 초기화
        }

        chatLogRepository.save(doc);

        log.info("⭐ [RATING] logId={}, roomId={}, rating={} → {}, reason={}",
            logId, roomId, rating, doc.getRating(), doc.getDislikeReason());

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