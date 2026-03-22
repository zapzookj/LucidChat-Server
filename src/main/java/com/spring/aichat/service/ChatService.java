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
import com.spring.aichat.dto.chat.SendChatResponse.StatsSnapshot;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 채팅 핵심 서비스
 *
 * [Phase 3]     트랜잭션 분리 + Smart RAG Skip + Redis 캐싱
 * [Phase 4]     Scene direction fields
 * [Phase 4.1]   씬 상태 영속화 + BGM 관성 시스템
 * [Phase 4.2]   관계 승급 이벤트 시스템
 * [Phase 4.3]   엔딩 트리거 감지 + 히스토리 정제
 * [Phase 5.1]   LLM 실패 시 롤백, 유저 평가, 개별 삭제
 * [Phase 5.2]   분산 트랜잭션 보상 패턴, RLHF dislikeReason
 * [Phase 5.5]   입체적 상태창 시스템
 *               - 5개 노말 스탯 + 3개 시크릿 스탯 처리
 *               - 동적 관계 태그 갱신
 *               - BPM 심박수 처리
 *               - 캐릭터의 생각 비동기 생성 (10턴 간격)
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
    private final UserRepository userRepository;
    private final SecretModeService secretModeService;

    private static final long USER_TURN_MEMORY_CYCLE = 10;
    private static final long RAG_SKIP_LOG_THRESHOLD = USER_TURN_MEMORY_CYCLE * 2;

    // [Phase 5.5] 캐릭터 생각 갱신 주기 (메모리 주기와 오프셋)
    private static final long THOUGHT_UPDATE_CYCLE = 10;
    private static final long THOUGHT_UPDATE_OFFSET = 5; // 5, 15, 25턴에 트리거

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  TX 간 데이터 전달용 내부 DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record JpaPreResult(
        ChatRoom room,
        Long userId,
        long logCount,
        boolean wasPromotionPending,
        String username,
        int energyCost
    ) {}

    private record RollbackContext(
        Long userId,
        String username,
        int energyCost,
        String savedUserLogId
    ) {}

    /**
     * [Phase 5.5-IT] LLM 결과에 innerThought 추가
     */
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
        String easterEggTrigger,
        AiJsonOutput.StatChanges statChanges,
        Integer bpm,
        String innerThought          // [Phase 5.5-IT] 추가
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  유저 채팅 메시지 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SendChatResponse sendMessage(Long roomId, String userMessage) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱ [PERF] ====== sendMessage START ====== roomId={}", roomId);

        // ── Content Moderation ──
        {
            ChatRoom roomForCheck = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
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

            long logCount = chatLogRepository.countByRoomId(roomId);

            return new JpaPreResult(
                room, room.getUser().getId(), logCount,
                room.isPromotionPending(), room.getUser().getUsername(), cost
            );
        });

        log.info("⏱ [PERF] TX-1 (JPA preprocess): {}ms | promotionPending={}",
            System.currentTimeMillis() - tx1Start, jpa.wasPromotionPending());

        cacheService.evictUserProfile(jpa.username());

        // ── Prompt Injection Check ──
        PromptInjectionGuard.InjectionCheckResult injCheck =
            injectionGuard.checkChatMessage(userMessage, jpa.username());
        if (injCheck.detected()) {
            log.warn("⚠️ [INJECTION] Detected in chat: user={}, severity={}, pattern={}",
                jpa.username(), injCheck.severity(), injCheck.matchedPattern());
        }

        // ── MongoDB: USER 메시지 저장 ──
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

        // ── Non-TX Zone: LLM 호출 ──
        LlmResult llmResult;
        try {
            llmResult = callLlmAndParse(jpa.room(), jpa.logCount() + 1, userMessage);
        } catch (Exception e) {
            log.error("❌ [COMPENSATION] LLM call failed — rolling back | roomId={}", roomId, e);
            compensateFullRollback(rollbackCtx);
            throw e;
        }

        // ── TX-2: JPA only (호감도/스탯/씬/업적 업데이트) ──
        long tx2Start = System.currentTimeMillis();
        boolean isStory = jpa.room().isStoryMode();

        // [Phase 5.5] 시크릿 모드 여부 (스탯 스냅샷용)
        boolean effectiveSecretMode = jpa.room().getUser().getIsSecretMode()
            && secretModeService.canAccessSecretMode(
            jpa.room().getUser(), jpa.room().getCharacter().getId());

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

                freshRoom.updateLastActive(llmResult.mainEmotion());

                if (isStory) {
                    freshRoom.updateSceneState(
                        llmResult.lastBgmMode(),
                        llmResult.lastLocation(),
                        llmResult.lastOutfit(),
                        llmResult.lastTimeOfDay()
                    );
                }

                // ━━━ [Phase 5.5] 스탯 + BPM + 동적 관계 처리 ━━━
                applyStatChanges(freshRoom, llmResult.statChanges(), effectiveSecretMode);

                if (llmResult.bpm() != null) {
                    freshRoom.updateBpm(llmResult.bpm());
                }

                // 스탯 기반 관계 갱신 (승급 이벤트 중에는 스킵)
                if (!freshRoom.isPromotionPending()) {
                    boolean relationChanged = freshRoom.refreshRelationFromStats();
                    if (relationChanged) {
                        log.info("🔄 [STATS] Relation changed via stats: {} → {} | tag='{}' | roomId={}",
                            jpa.room().getStatusLevel(), freshRoom.getStatusLevel(),
                            freshRoom.getDynamicRelationTag(), roomId);
                    }
                }
                // ━━━ [Phase 5.5-P] 엔딩 트리거: 5개 노말 스탯 중 하나라도 ±100 도달 ━━━
                EndingTrigger endingTrigger = null;
                if (isStory) {
                    String endingCheck = freshRoom.checkEndingTrigger();
                    if (endingCheck != null) {
                        endingTrigger = new EndingTrigger(endingCheck);
                        log.info("🎬 [ENDING] {} ending triggered via stats! max={} min={} | roomId={}",
                            endingCheck, freshRoom.getMaxNormalStatValue(),
                            freshRoom.getMinNormalStatValue(), roomId);
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

                // [Phase 5.5] 스탯 스냅샷 생성
                StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                return new SendChatResponse(
                    roomId,
                    llmResult.sceneResponses(),
                    freshRoom.getAffectionScore(),
                    freshRoom.getStatusLevel().name(),
                    promoEvent,
                    endingTrigger,
                    easterEggEvent,
                    statsSnapshot,
                    freshRoom.getCurrentBpm(),
                    freshRoom.getDynamicRelationTag(),
                    null  // characterThought는 비동기 갱신 후 프론트에서 별도 fetch
                );
            });
        } catch (Exception e) {
            log.error("❌ [COMPENSATION] TX-2 failed — rolling back | roomId={}", roomId, e);
            compensateFullRollback(rollbackCtx);
            throw e;
        }

        log.info("⏱ [PERF] TX-2 (JPA postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        // ── MongoDB: ASSISTANT 메시지 저장 (속마음 포함) ──
        String assistantLogId = null;
        boolean hasInnerThought = false;
        try {
            ChatLogDocument assistantLog = ChatLogDocument.assistantWithThought(
                jpa.room().getId(),
                llmResult.cleanJson(), llmResult.combinedDialogue(),
                llmResult.mainEmotion(), null,
                llmResult.innerThought()   // [Phase 5.5-IT] 속마음 텍스트 (nullable)
            );
            ChatLogDocument savedAssistant = chatLogRepository.save(assistantLog);
            assistantLogId = savedAssistant.getId();
            hasInnerThought = savedAssistant.hasInnerThought();

            if (hasInnerThought) {
                log.info("💭 [INNER_THOUGHT] Saved: roomId={} | logId={} | preview='{}'",
                    roomId, assistantLogId,
                    llmResult.innerThought().substring(0, Math.min(30, llmResult.innerThought().length())));
            }
        } catch (Exception e) {
            log.error("⚠️ [INCONSISTENCY] ASSISTANT log MongoDB save failed after JPA commit. roomId={}", roomId, e);
        }

        cacheService.evictRoomInfo(roomId);

        log.info("⏱ [PERF] ====== sendMessage DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        triggerMemorySummarizationIfNeeded(roomId, jpa.userId(), jpa.logCount() + 1);

        // [Phase 5.5] 캐릭터 생각 비동기 생성 트리거
        triggerCharacterThoughtIfNeeded(roomId, jpa.userId(), jpa.logCount() + 1, effectiveSecretMode);

        // [Phase 5.5-IT] 응답에 속마음 플래그 + logId 추가
        // response는 TX-2에서 만들어졌으므로, 새 record로 래핑
        return new SendChatResponse(
            response.roomId(),
            response.scenes(),
            response.currentAffection(),
            response.relationStatus(),
            response.promotionEvent(),
            response.endingTrigger(),
            response.easterEgg(),
            response.stats(),
            response.bpm(),
            response.dynamicRelationTag(),
            response.characterThought(),
            hasInnerThought,           // [Phase 5.5-IT]
            assistantLogId             // [Phase 5.5-IT]
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5] 스탯 적용 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void applyStatChanges(ChatRoom room, AiJsonOutput.StatChanges statChanges, boolean isSecretMode) {
        if (statChanges == null) return;

        room.applyNormalStatChanges(
            statChanges.safeIntimacy(),
            statChanges.safeAffection(),
            statChanges.safeDependency(),
            statChanges.safePlayfulness(),
            statChanges.safeTrust()
        );

        if (isSecretMode) {
            room.applySecretStatChanges(
                statChanges.safeLust(),
                statChanges.safeCorruption(),
                statChanges.safeObsession()
            );
        }

        log.info("📊 [STATS] Applied: I={} A={} D={} P={} T={} | Current: I={} A={} D={} P={} T={} | roomId={}",
            statChanges.safeIntimacy(), statChanges.safeAffection(),
            statChanges.safeDependency(), statChanges.safePlayfulness(), statChanges.safeTrust(),
            room.getStatIntimacy(), room.getStatAffection(),
            room.getStatDependency(), room.getStatPlayfulness(), room.getStatTrust(),
            room.getId());
    }

    /**
     * [Phase 5.5] 스탯 스냅샷 생성 (응답 DTO용)
     */
    private StatsSnapshot buildStatsSnapshot(ChatRoom room, boolean isSecretMode) {
        return new StatsSnapshot(
            room.getStatIntimacy(),
            room.getStatAffection(),
            room.getStatDependency(),
            room.getStatPlayfulness(),
            room.getStatTrust(),
            isSecretMode ? room.getStatLust() : null,
            isSecretMode ? room.getStatCorruption() : null,
            isSecretMode ? room.getStatObsession() : null
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5] 캐릭터의 생각 생성 (비동기)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void triggerCharacterThoughtIfNeeded(Long roomId, Long userId, long totalLogCount, boolean isSecretMode) {
        long userMsgCount = chatLogRepository.countByRoomIdAndRole(roomId, ChatRole.USER);

        if (userMsgCount > 0 && userMsgCount % THOUGHT_UPDATE_CYCLE == THOUGHT_UPDATE_OFFSET) {
            log.info("💭 [THOUGHT] Generation TRIGGERED | roomId={} | userMsgCount={}",
                roomId, userMsgCount);
            generateCharacterThoughtAsync(roomId, userId, (int) userMsgCount, isSecretMode);
        }
    }

    /**
     * 캐릭터의 생각을 비동기로 생성하여 ChatRoom에 저장.
     *
     * sentiment 모델(경량)을 사용하여 비용과 레이턴시를 최소화.
     * 실패해도 유저 경험에 영향 없음 (다음 주기에 재시도).
     */
    @Async
    public void generateCharacterThoughtAsync(Long roomId, Long userId, int currentTurnCount, boolean isSecretMode) {
        long start = System.currentTimeMillis();
        try {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found for thought generation"));

            Character character = room.getCharacter();
            String nickname = room.getUser().getNickname();

            // 최근 대화 5턴 로드 (생각의 맥락)
            List<ChatLogDocument> recentLogs = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
            recentLogs.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));

            // 최근 10개만 사용
            List<ChatLogDocument> context = recentLogs.size() > 10
                ? recentLogs.subList(recentLogs.size() - 10, recentLogs.size())
                : recentLogs;

            String conversationContext = context.stream()
                .map(log -> log.getRole().name() + ": " + log.getCleanContent())
                .collect(Collectors.joining("\n"));

            String modeContext = isSecretMode ? "시크릿 모드 (친밀한 관계)" : "노말 모드";

            String thoughtPrompt = """
                당신은 '%s'이라는 이름의 캐릭터입니다.
                
                ## 캐릭터 정보
                - 이름: %s
                - 성격: %s
                - 현재 관계: %s
                - 모드: %s
                
                ## 현재 스탯 (0~100)
                친밀도: %d | 호감도: %d | 의존도: %d | 장난기: %d | 신뢰도: %d
                
                ## 최근 대화
                %s
                
                ## 지시사항
                위 대화를 바탕으로, '%s'가 '%s'에 대해 지금 마음속으로 생각하고 있을 법한 **내면의 독백**을 한 문장으로 작성하세요.
                
                규칙:
                - 캐릭터의 말투와 성격을 반영하세요
                - 15~40자 이내의 짧은 독백
                - 스탯 수치가 높은 감정을 반영하세요 (예: 호감도가 높으면 설렘, 의존도가 높으면 의지)
                - 메타적 표현(AI, 시스템, 스탯 등) 절대 금지
                - 독백만 출력하세요. 따옴표나 부연설명 없이.
                """.formatted(
                character.getName(),
                character.getName(),
                character.getEffectivePersonality(isSecretMode),
                room.getDynamicRelationTag() != null ? room.getDynamicRelationTag() : room.getStatusLevel().name(),
                modeContext,
                room.getStatIntimacy(), room.getStatAffection(),
                room.getStatDependency(), room.getStatPlayfulness(), room.getStatTrust(),
                conversationContext,
                character.getName(),
                nickname
            );

            String thought = openRouterClient.chatCompletion(
                OpenAiChatRequest.withoutPenalty(
                    props.sentimentModel(),
                    List.of(OpenAiMessage.system(thoughtPrompt)),
                    0.8
                )
            ).trim().replaceAll("[\"']", "");

            // DB 저장
            txTemplate.execute(status -> {
                ChatRoom freshRoom = chatRoomRepository.findById(roomId)
                    .orElseThrow(() -> new NotFoundException("Room not found"));
                freshRoom.updateCharacterThought(thought, currentTurnCount);
                return null;
            });

            cacheService.evictRoomInfo(roomId);

            log.info("💭 [THOUGHT] Generated: '{}' | roomId={} | {}ms",
                thought, roomId, System.currentTimeMillis() - start);

        } catch (Exception e) {
            log.warn("💭 [THOUGHT] Generation failed (non-blocking): roomId={} | {}",
                roomId, e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  보상 트랜잭션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

    private void compensateFullRollback(RollbackContext ctx) {
        if (ctx.savedUserLogId() != null) {
            try {
                chatLogRepository.deleteById(ctx.savedUserLogId());
                log.info("🔄 [COMPENSATION] User message deleted: logId={}", ctx.savedUserLogId());
            } catch (Exception ex) {
                log.error("🔄 [COMPENSATION] User message delete FAILED: logId={}", ctx.savedUserLogId(), ex);
            }
        }
        compensateEnergy(ctx.userId(), ctx.energyCost(), ctx.username());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  승급 이벤트 핵심 로직
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private PromotionEvent resolveAffectionAndPromotion(
        ChatRoom room, int affectionChange, Integer moodScore, boolean wasPending) {

        if (wasPending) {
            int resolvedMood = (moodScore != null) ? moodScore : 1;
            if (moodScore == null) {
                log.warn("[PROMOTION] mood_score is NULL - defaulting to 1 | roomId={}", room.getId());
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

        // ── TX-1 ──
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

        cacheService.evictUserProfile(jpa.username());

        // ── MongoDB: system detail 저장 ──
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

        // [Phase 5.5] 시크릿 모드 여부
        boolean effectiveSecretMode = jpa.room().getUser().getIsSecretMode()
            && secretModeService.canAccessSecretMode(
            jpa.room().getUser(), jpa.room().getCharacter().getId());

        // ── TX-2 ──
        SendChatResponse response;
        try {
            response = txTemplate.execute(status -> {
                ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                applyAffectionChange(freshRoom, llmResult.aiOutput().affectionChange());
                freshRoom.updateLastActive(llmResult.mainEmotion());

                freshRoom.updateSceneState(
                    llmResult.lastBgmMode(), llmResult.lastLocation(),
                    llmResult.lastOutfit(), llmResult.lastTimeOfDay()
                );

                // [Phase 5.5] 스탯 + BPM
                applyStatChanges(freshRoom, llmResult.statChanges(), effectiveSecretMode);
                if (llmResult.bpm() != null) {
                    freshRoom.updateBpm(llmResult.bpm());
                }
                if (!freshRoom.isPromotionPending()) {
                    freshRoom.refreshRelationFromStats();
                }

                StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                return new SendChatResponse(
                    roomId,
                    llmResult.sceneResponses(),
                    freshRoom.getAffectionScore(),
                    freshRoom.getStatusLevel().name(),
                    null, null, null,
                    statsSnapshot,
                    freshRoom.getCurrentBpm(),
                    freshRoom.getDynamicRelationTag(),
                    freshRoom.getCharacterThought()
                );
            });
        } catch (Exception e) {
            log.error("❌ [COMPENSATION] TX-2 failed for system event | roomId={}", roomId, e);
            compensateFullRollback(rollbackCtx);
            throw e;
        }

        // ── MongoDB: ASSISTANT 저장 ──
        String evtAssistantLogId = null;
        boolean evtHasInnerThought = false;
        try {
            ChatLogDocument assistantLog = ChatLogDocument.assistantWithThought(
                jpa.room().getId(),
                llmResult.cleanJson(), llmResult.combinedDialogue(),
                llmResult.mainEmotion(), null,
                llmResult.innerThought()
            );
            ChatLogDocument savedLog = chatLogRepository.save(assistantLog);
            evtAssistantLogId = savedLog.getId();
            evtHasInnerThought = savedLog.hasInnerThought();
        } catch (Exception e) {
            log.error("⚠️ [INCONSISTENCY] ASSISTANT log save failed for system event. roomId={}", roomId, e);
        }

        cacheService.evictRoomInfo(roomId);

        // response에 속마음 플래그 추가
        return new SendChatResponse(
            response.roomId(),
            response.scenes(),
            response.currentAffection(),
            response.relationStatus(),
            response.promotionEvent(),
            response.endingTrigger(),
            response.easterEgg(),
            response.stats(),
            response.bpm(),
            response.dynamicRelationTag(),
            response.characterThought(),
            evtHasInnerThought,
            evtAssistantLogId
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Non-TX 공통 로직
    //  [Phase 5.5] statChanges, bpm 추출 추가
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

        boolean effectiveSecretMode = room.getUser().getIsSecretMode()
            && secretModeService.canAccessSecretMode(
            room.getUser(), room.getCharacter().getId());

        CharacterPromptAssembler.SystemPromptPayload systemPrompt = promptAssembler.assembleSystemPrompt(
            room.getCharacter(), room, room.getUser(), longTermMemory, effectiveSecretMode
        );

        List<OpenAiMessage> messages = buildMessageHistory(room.getId(), systemPrompt);

        String model = boostModeResolver.resolveModel(room.getUser());

        Map<String, Object> providerRouting = Map.of(
            "order", List.of("Google AI Studio"),
            "allow_fallbacks", false
        );

        long llmStart = System.currentTimeMillis();
        log.info("⏱ [PERF] LLM call START | model={} | messages={} | promptChars={}",
            model, messages.size(),
            messages.stream().mapToInt(m -> m.content().length()).sum());

        String rawAssistant = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.8, providerRouting)
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

            // [Phase 5.5] 스탯/BPM 추출
            AiJsonOutput.StatChanges statChanges = aiOutput.statChanges();
            Integer bpm = aiOutput.bpm();

            // [Phase 5.5-IT] 속마음 추출 — null/blank는 null로 정규화
            String innerThought = aiOutput.innerThought();
            if (innerThought != null && innerThought.isBlank()) {
                innerThought = null;
            }

            return new LlmResult(aiOutput, cleanJson, combinedDialogue, mainEmotion, sceneResponses,
                lastBgm, lastLoc, lastOutfit, lastTime, moodScore, easterEggTrigger,
                statChanges, bpm, innerThought);

        } catch (JsonProcessingException e) {
            log.error("JSON Parsing Error: {}", rawAssistant, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 응답 형식이 올바르지 않습니다.");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  채팅방 관리 영역
    //  [Phase 5.5] 스탯 정보 포함
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public ChatRoomInfoResponse getChatRoomInfo(Long roomId) {
        return cacheService.getRoomInfo(roomId, ChatRoomInfoResponse.class)
            .orElseGet(() -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

                var character = room.getCharacter();
                boolean isSecret = room.getUser().getIsSecretMode()
                    && secretModeService.canAccessSecretMode(
                    room.getUser(), room.getCharacter().getId());

                // [Phase 5.5] 스탯 스냅샷
                StatsSnapshot statsSnapshot = buildStatsSnapshot(room, isSecret);

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
                    new java.util.ArrayList<>(character.getAllowedLocations(room.getStatusLevel(), isSecret)),
                    // [Phase 5.5] 입체적 상태창
                    statsSnapshot,
                    room.getCurrentBpm(),
                    room.getDynamicRelationTag(),
                    room.getCharacterThought()
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

    public void initializeChatRoom(Long roomId) {
        if (chatLogRepository.countByRoomId(roomId) > 0) return;

        Character character = txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
            room.updateLastActive(EmotionTag.NEUTRAL);
            room.resetSceneState();
            return room.getCharacter();
        });

        String introNarration = character.getIntroNarration();
        chatLogRepository.save(ChatLogDocument.system(roomId, introNarration));

        String firstGreeting = character.getFirstGreeting();
        chatLogRepository.save(ChatLogDocument.of(
            roomId, ChatRole.ASSISTANT, firstGreeting, firstGreeting, EmotionTag.NEUTRAL, null));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  유저 평가 시스템 (RLHF)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public String rateChatLog(String logId, Long roomId, String rating, String dislikeReason) {
        ChatLogDocument doc = chatLogRepository.findById(logId)
            .orElseThrow(() -> new NotFoundException("채팅 로그를 찾을 수 없습니다."));

        if (!doc.getRoomId().equals(roomId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "해당 채팅방의 로그가 아닙니다.");
        }

        if (doc.getRole() != ChatRole.ASSISTANT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "캐릭터 응답에만 평가할 수 있습니다.");
        }

        doc.updateRating(rating);

        if ("DISLIKE".equals(doc.getRating()) && dislikeReason != null && !dislikeReason.isBlank()) {
            doc.updateDislikeReason(dislikeReason);
        } else {
            doc.updateDislikeReason(null);
        }

        chatLogRepository.save(doc);

        log.info("⭐ [RATING] logId={}, roomId={}, rating={} → {}, reason={}",
            logId, roomId, rating, doc.getRating(), doc.getDislikeReason());

        return doc.getRating();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  개별 대화 삭제
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void deleteSingleChatLog(String logId, Long roomId) {
        ChatLogDocument doc = chatLogRepository.findById(logId)
            .orElseThrow(() -> new NotFoundException("채팅 로그를 찾을 수 없습니다."));

        if (!doc.getRoomId().equals(roomId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "해당 채팅방의 로그가 아닙니다.");
        }

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

    private List<OpenAiMessage> buildMessageHistory(Long roomId, CharacterPromptAssembler.SystemPromptPayload systemPrompt) {
        List<ChatLogDocument> history = chatLogRepository.findTop200ByRoomIdOrderByCreatedAtDesc(roomId);
        history.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();

        // 🥪 [Top Bread] 배열의 맨 처음: 절대 변하지 않는 정적 룰 (100% 캐시 히트 타겟)
        if (history.size() == 3 || history.size() % 20 == 0) {
            messages.add(OpenAiMessage.systemCached(systemPrompt.staticRules(), Map.of("type", "ephemeral")));
        } else messages.add(OpenAiMessage.system(systemPrompt.staticRules()));

//        for (int i = 0; i < history.size(); i++) {
//            ChatLogDocument chatLog = history.get(i);
//            boolean isLastItem = (i == history.size() - 1);
//
//            Map<String, Object> cacheControl = isLastItem ? Map.of("type", "ephemeral") : null;
//            switch (chatLog.getRole()) {
//                case USER -> messages.add(new OpenAiMessage("user", chatLog.getRawContent(), cacheControl));
//                case ASSISTANT -> {
//                    String sanitized = buildSanitizedAssistantContent(chatLog);
//                    messages.add(new OpenAiMessage("assistant", sanitized, cacheControl));
//                }
//                case SYSTEM -> messages.add(
//                    new OpenAiMessage("user", "[NARRATION]\n" + chatLog.getRawContent(), cacheControl)
//                );
//            }
//        }

        for (ChatLogDocument chatLog : history) {
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

        messages.add(OpenAiMessage.system(systemPrompt.dynamicRules()));
        messages.add(OpenAiMessage.system(systemPrompt.outputFormat()));

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
        // [Phase 5.5-P] 레거시 affection_change → statAffection에 통합 적용
        room.applyLegacyAffectionChange(change);
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-IT] 속마음 해금
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final int INNER_THOUGHT_UNLOCK_COST = 1;

    /**
     * 속마음(Inner Thought) 해금
     *
     * 1. 로그 유효성 검증 (존재 여부, 방 소유권, 속마음 존재 여부)
     * 2. 이미 해금된 경우 → 중복 과금 방지, 바로 텍스트 반환
     * 3. 에너지 차감 (1 에너지)
     * 4. thoughtUnlocked = true로 업데이트
     * 5. 실제 속마음 텍스트 반환
     *
     * @param logId  ASSISTANT 로그 ID
     * @param roomId 채팅방 ID (소유권 검증용)
     * @param username 유저명 (캐시 무효화용)
     * @return 속마음 텍스트
     */
    @Transactional
    public String unlockInnerThought(String logId, Long roomId, String username) {
        // 1. 로그 조회 및 검증
        ChatLogDocument doc = chatLogRepository.findById(logId)
            .orElseThrow(() -> new NotFoundException("채팅 로그를 찾을 수 없습니다."));

        if (!doc.getRoomId().equals(roomId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "해당 채팅방의 로그가 아닙니다.");
        }

        if (doc.getRole() != ChatRole.ASSISTANT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "캐릭터 응답에만 속마음이 존재합니다.");
        }

        if (!doc.hasInnerThought()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "이 응답에는 속마음이 없습니다.");
        }

        // 2. 이미 해금된 경우 — 에너지 차감 없이 바로 반환
        if (doc.isThoughtUnlocked()) {
            log.info("💭 [INNER_THOUGHT] Already unlocked: logId={}", logId);
            return doc.getInnerThought();
        }

        // 3. 에너지 차감
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
        user.consumeEnergy(INNER_THOUGHT_UNLOCK_COST);
        userRepository.save(user);

        // 4. 해금 처리
        doc.unlockThought();
        chatLogRepository.save(doc);

        // 5. 캐시 무효화
        cacheService.evictUserProfile(username);

        log.info("💭 [INNER_THOUGHT] Unlocked: logId={} | user={} | cost={}",
            logId, username, INNER_THOUGHT_UNLOCK_COST);

        return doc.getInnerThought();
    }
}