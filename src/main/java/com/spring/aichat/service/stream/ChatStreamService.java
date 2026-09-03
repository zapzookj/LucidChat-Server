package com.spring.aichat.service.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.*;
import com.spring.aichat.domain.world.World;
import com.spring.aichat.domain.world.WorldRepository;
import com.spring.aichat.domain.user.EnergySplit;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.dto.chat.SendChatResponse.*;
import com.spring.aichat.dto.director.DirectorDirective;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.LlmCircuitBreaker;
import com.spring.aichat.external.LlmCircuitBreaker.TtftTimeoutException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.external.OpenRouterStreamClient;
import com.spring.aichat.external.OpenRouterStreamClient.StreamResult;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.AchievementService;
import com.spring.aichat.service.ChatService;
import com.spring.aichat.service.ContentModerationService;
import com.spring.aichat.service.MemoryService;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.director.DirectorService;
import com.spring.aichat.service.illustration.BackgroundGenerationService;
import com.spring.aichat.service.illustration.IllustrationService;
import com.spring.aichat.service.payment.BoostModeResolver;
import com.spring.aichat.service.payment.SecretModeService;
import com.spring.aichat.service.prompt.CharacterPromptAssembler;
import com.spring.aichat.service.prompt.DirectorPromptAssembler;
import com.spring.aichat.service.theater.TheaterInterventionService;
import com.spring.aichat.service.util.LlmOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * [Phase 5.5-Perf] SSE Dual-Streaming 채팅 서비스
 *
 * [Phase 5.5-EV] 이벤트 시스템 강화:
 *   - topic_concluded: LLM이 판단한 주제 종료 플래그
 *   - Director Mode: 이벤트를 "스노우볼" 형태로 진행
 *   - sendDirectorWatchStream(): [👀 계속 지켜보기] → SYSTEM_DIRECTOR 주입
 *   - sendTimeSkipStream(): [시간 넘기기] → 시간/장소 전환 나레이션
 *   - 승급 판정: 5종 스탯 변화량 합산 기반
 *   - 승급 게이팅: topic_concluded=true 시에만 발동
 *
 * [Phase 5.5-Stability] LLM Provider 서킷 브레이커:
 *   - TTFT 기반 AI Studio ↔ Vertex 동적 라우팅
 *   - Per-request fallback: TTFT 2초 초과 시 즉시 Vertex 재시도
 *   - Circuit breaker: 연속 3회 초과 → 5분간 Vertex 전환 → 프로브 복구
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatStreamService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final CharacterPromptAssembler promptAssembler;
    private final com.spring.aichat.config.LegacyFeatureProperties legacy;
    private final OpenRouterStreamClient streamClient;
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
    private final com.spring.aichat.service.moderation.ModerationEventService moderationEventService;
    private final UserRepository userRepository;
    private final SecretModeService secretModeService;
    private final ChatService chatService;
    private final IllustrationService illustrationService;
    private final BackgroundGenerationService backgroundGenerationService;
    // [2026-07-30 A-1 재피벗] 매턴 씬 일러 — illustration.scene.enabled 기본 off
    private final com.spring.aichat.service.illustration.scene.SceneRenderService sceneRenderService;
    private final LlmCircuitBreaker llmCircuitBreaker;
    private final DirectorService directorService;
    /** [Phase 6-Illust] World 컨텍스트 조회 — 배경 prompt mood prefix 이중 안전망용. */
    private final WorldRepository worldRepository;
    /**
     * [Phase III · 작업 4] Theater 난입 통합용
     *
     * Theater 모드에서 유저가 "난입" 후 ChatService를 통해 직접 대화할 때,
     * 매 ASSISTANT 응답의 logId를 InterventionService에 알려줘야 한다.
     * 이게 빠지면 resumeFromIntervention의 redirectHint에 "마지막 로그 ID: null"이
     * 박혀 들어가서 LLM이 개입의 맥락을 정확히 받지 못한다.
     *
     * 비-Theater 방에는 아무 영향도 주지 않으므로 안전하게 주입.
     */
    private final TheaterInterventionService theaterInterventionService;
    /** [Phase6/Tier3 / C-9] ASSISTANT log retry + deadletter wrapper */
    private final ChatLogPersister chatLogPersister;

    private static final long USER_TURN_MEMORY_CYCLE = 10;
    private static final long RAG_SKIP_LOG_THRESHOLD = USER_TURN_MEMORY_CYCLE * 2;

    /** [Phase 5.5-EV] 시간 넘기기 에너지 비용 */
    private static final int TIME_SKIP_ENERGY_COST = 1;

    // [Phase 5.5-Fix-IT] 속마음 히스토리 포함 윈도우 — 최근 N개 ASSISTANT 메시지에만 속마음 포함
    private static final int INNER_THOUGHT_HISTORY_WINDOW = 3;

    /**
     * [E-5.1.b] {@code eventContext} 길이 상한.
     *
     * <p>이 값은 SYSTEM 롤 로그로 <b>영구</b> 저장되고 이후 <b>매 턴</b> role="system"으로 재주입되며
     * (:1348), 유저가 지울 수도 없다({@code ChatService:409-411}). 상한이 없으면 임의 길이 텍스트가
     * 방의 모든 후속 턴에 부착돼 토큰 비용이 무한 증폭된다.
     *
     * <p>레지스터 수정안은 300을 제안했으나 <b>500</b>으로 잡는다 — 300은 정상 분기 카드 문구를
     * 자를 수 있다는 지적이 있었고, 프로드 나레이션 길이 분포(Mongo)를 아직 실측하지 못했다.
     * 절단이 실제로 일어나면 아래 WARN이 남으므로 그 빈도를 보고 조이거나 풀 것.
     */
    private static final int EVENT_CONTEXT_MAX = 500;

    /** [Phase 5.5-EV] SYSTEM_DIRECTOR 프롬프트 (지켜보기 시 LLM에 주입) */
    private static final String SYSTEM_DIRECTOR_PROMPT = """
        [SYSTEM_DIRECTOR] 유저는 아직 개입하지 않고 상황을 숨죽여 지켜보고 있습니다.
        현재의 갈등이나 상황을 한 단계 더 심화시키고 긴장감을 높이는 방향으로 2~3개의 씬을 추가 연출하세요.
        상황을 스스로 종료시키지 마세요. 캐릭터를 더 곤경에 빠뜨리거나, 묘한 분위기를 고조시키세요.
        반드시 "event_status": "ONGOING" 을 출력하세요.
        """;

    /** [Phase 5.5-EV] 시간 넘기기 시스템 나레이션 프롬프트 */
    private static final String TIME_SKIP_PROMPT = """
        [TIME_SKIP] 시간이 흘렀습니다. 자연스러운 시간 경과를 나레이션으로 표현하고,
        새로운 시간대/장소에서 캐릭터가 유저에게 먼저 말을 거는 씬을 1~2개 만들어주세요.
        반드시 location, time 필드를 적절히 변경하세요.
        """;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  TX 간 데이터 전달 DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // [D-1.5] energyCost(int) → energy(EnergySplit): TX-1 차감의 free/paid 분할을 실어 보상이
    //   정확히 되돌린다. 이 record를 SSE 엔트리 4곳이 공유하므로 필드 1개로 전 경로가 닫힌다.
    private record JpaPreResult(
        ChatRoom room, Long userId, long logCount,
        String username, EnergySplit energy
    ) {}

    private record RollbackContext(
        Long userId, String username, EnergySplit energy, String savedUserLogId
    ) {}

    /** LLM 결과 파싱 후 중간 데이터 */
    private record ParsedLlmResult(
        AiJsonOutput aiOutput, String cleanJson, String combinedDialogue,
        EmotionTag mainEmotion, List<SceneResponse> sceneResponses,
        String lastBgm, String lastLoc, String lastOutfit, String lastTime,
        AiJsonOutput.StatChanges statChanges,
        String innerThought, boolean topicConcluded, String eventStatus,
        String scenesJson,      // [Phase 5.5-Fix] 구조화된 씬 JSON
        boolean generateIllustration,
        String newLocationName,
        String locationDescription
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 메인 스트리밍 (유저 채팅) — 기존 + topic_concluded/promotion gating
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void sendMessageStream(Long roomId, String userMessage, SseEmitter emitter) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱ [STREAM-PERF] ====== sendMessageStream START ====== roomId={}", roomId);

        // [D-2.b] 최외곽 catch가 보상 판정을 할 수 있도록 호이스팅한다. 상세는 그 catch의 주석.
        //   ⚠ jpa(JpaPreResult)는 TX-2 람다가 캡처하므로 절대 호이스팅하지 마라 — effectively final을
        //     잃어 컴파일이 깨진다. rollbackCtx는 어떤 람다에도 캡처되지 않아 재대입이 안전하다(전수 확인).
        RollbackContext rollbackCtx = null;
        boolean committed = false;

        try {
            // ── [V2 분리] STORY 모드는 ChatStreamServiceV2가 담당 — 방어적 가드 ──
            ChatRoom modeCheck = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
            if (modeCheck.isStoryMode()) {
                log.warn("⚠️ [V1-STREAM] STORY V2 room routed to V1 service. roomId={}", roomId);
                sendSseError(emitter, "INVALID_ROUTE", "STORY 모드는 V2 엔드포인트를 사용해야 합니다.");
                return;
            }

            // ── Content Moderation ──
            ChatRoom roomForCheck = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

            // ── [2026-07-30 P0 공개 철회] UGC 접근 재검증 — 철회/반려된 캐릭터의 기존 방 신규 대화 차단.
            // 방 생성 시점 isAccessibleBy 검증은 멱등 재입장(기존 방 반환)을 막지 못한다.
            // 소유자 본인 방은 계속 허용 · 과거 로그 열람은 별도 경로라 영향 없음(읽기 보존 정책).
            if (blockIfUgcInaccessible(roomForCheck, emitter)) return;
            boolean isSecretCheck = roomForCheck.isSecretModeActive()
                && secretModeService.canAccessSecretMode(
                roomForCheck.getUser(), roomForCheck.getCharacter().getId());

            ContentModerationService.ModerationVerdict verdict =
                contentModerationService.moderate(userMessage, isSecretCheck);
            if (!verdict.passed()) {
                moderationEventService.recordModeration(
                    roomForCheck.getUser().getId(), roomForCheck.getId(), "CHAT",
                    verdict.blockedAtStep(), verdict.category(), verdict.totalLatencyMs(), userMessage);
                sendSseError(emitter, "CONTENT_BLOCKED", verdict.userMessage());
                return;
            }

            // ── TX-1 ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
                int cost = boostModeResolver.resolveEnergyCost(room.getChatMode(), room.getUser());
                EnergySplit charge = room.getUser().consumeEnergy(cost);
                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.getUser().getUsername(), charge);
            });
            // [D-2.b] 차감이 커밋된 **직후** 보상 컨텍스트를 세운다. 종전에는 이 지점이 로그 저장
            //   이후(아래)라, 차감 ~ 로그 저장 사이(evict·인젝션 검사)의 예외가 최외곽 catch로 새어
            //   무보상으로 끝났다. savedUserLogId는 아직 없으므로 null — compensateFullRollback이
            //   null을 이미 검사한다.
            rollbackCtx = new RollbackContext(jpa.userId(), jpa.username(), jpa.energy(), null);
            cacheService.evictUserProfile(jpa.username());

            // ── Prompt Injection Check ──
            PromptInjectionGuard.InjectionCheckResult injCheck =
                injectionGuard.checkChatMessage(userMessage, jpa.username());
            if (injCheck.detected()) {
                log.warn("⚠️ [INJECTION] Detected: user={}", jpa.username());
                moderationEventService.recordInjection(
                    roomForCheck.getUser().getId(), jpa.username(), roomForCheck.getId(), "CHAT",
                    injCheck.severity().name(), injCheck.matchedPattern(), userMessage);
            }

            // ── MongoDB: USER 메시지 저장 ──
            String savedUserLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    ChatLogDocument.user(roomId, userMessage));
                savedUserLogId = savedLog.getId();
            } catch (Exception e) {
                compensateEnergy(jpa.userId(), jpa.energy(), jpa.username());
                sendSseError(emitter, "INTERNAL_ERROR", "메시지 저장에 실패했습니다.");
                return;
            }

            // [D-2.b] 로그 id가 확정됐으니 보상 컨텍스트를 갱신한다(위에서 이미 에너지분은 세워 뒀다).
            rollbackCtx = new RollbackContext(
                jpa.userId(), jpa.username(), jpa.energy(), savedUserLogId);

            // [Phase 5.5-EV] 유저 개입인지 판단 (디렉터 모드 중 유저가 직접 채팅)
            boolean isUserIntervention = jpa.room().isEventActive();

            // ── LLM 호출 + 파싱 ──
            boolean effectiveSecretMode = resolveSecretMode(jpa.room());
            ParsedLlmResult parsed = streamLlmAndParse(jpa.room(), jpa.logCount() + 1,
                effectiveSecretMode, emitter, rollbackCtx);
            if (parsed == null) return; // 에러 시 이미 emitter 처리됨

            // [이관] isStory 변수 제거 — 모든 게이트가 ChatModePolicy로 정책화됨 (SANDBOX 이관 완성)

            // [Phase 5.5-EV] 디렉터 모드 중 유저 개입 → RESOLVED 판정
            boolean wasEventActive = jpa.room().isEventActive();

            // ── TX-2 ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    // [Phase 5.5-Sep] topic_concluded + event: 스토리 모드 전용
                    if (ChatModePolicy.supportsTopicConcluded(freshRoom.getChatMode())) {
                        freshRoom.updateTopicConcluded(parsed.topicConcluded());
                    }

                    if (wasEventActive && ChatModePolicy.supportsEvents(freshRoom.getChatMode())) {
                        // [Fix 1] 유저가 실제 채팅으로 개입한 경우 → 무조건 RESOLVED
                        // LLM이 ONGOING을 뱉어도 백엔드에서 강제 오버라이드
                        freshRoom.updateEventStatus("RESOLVED");
                        log.info("🎬 [DIRECTOR] User intervention → forced RESOLVED | roomId={} | llmSaid={}", roomId, parsed.eventStatus());
                    }

                    // 스탯 적용 (이벤트 ONGOING 중에는 스탯 동결)
                    boolean suppressStats = freshRoom.isEventActive() && !isUserIntervention;
                    if (!suppressStats) {
                        applyStatChanges(freshRoom, parsed.statChanges(), effectiveSecretMode);
                    }

                    // [Phase 5.5-Director] 디렉터 constraint 소비 (일회성)
                    // 이 턴에서 constraint가 사용되었으면 클리어
                    if (freshRoom.hasActiveDirectorConstraint() && !freshRoom.isEventActive()) {
                        freshRoom.clearDirectorInterlude();
                    }

                    // 이벤트 모드에서는 RESOLVED될 때까지 constraint 유지
                    if (wasEventActive && "RESOLVED".equalsIgnoreCase(parsed.eventStatus())) {
                        freshRoom.clearDirectorInterlude();
                    }

                    // 승급 이벤트 처리 — [블록 D · §G-1] 임계 도달 즉시 승급
                    PromotionEvent promoEvent = null;
                    if (ChatModePolicy.supportsPromotion(freshRoom.getChatMode())) {  // [이관] isStory→정책 (SANDBOX 활성)
                        promoEvent = resolvePromotionLogic(freshRoom, parsed);
                    }

                    freshRoom.updateLastActive(parsed.mainEmotion());
                    if (ChatModePolicy.supportsSceneDirection(freshRoom.getChatMode())) {  // [이관]
                        freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
                            parsed.lastOutfit(), parsed.lastTime());
                    }

                    // [블록 D · §G-1] 태그 갱신 전용 — 단계 판정은 resolvePromotionLogic이 이미 했다.
                    //   (승급 대기/시험 개시 경로는 폐지됐다.)
                    freshRoom.refreshRelationFromStats();

                    // 엔딩 트리거
                    EndingTrigger endingTrigger = null;
                    // [블록 D · §C#6] 게이트 오프 시 트리거 자체를 발화하지 않는다 —
                    //   생성이 막힌 엔딩을 SSE로 예고하면 프론트가 도달 불가 상태로 잠긴다.
                    if (legacy.getEnding().isDialogueEnabled()
                        && ChatModePolicy.supportsEnding(freshRoom.getChatMode())) {  // [이관]
                        String endingCheck = freshRoom.checkEndingTrigger();
                        if (endingCheck != null) {
                            endingTrigger = new EndingTrigger(endingCheck);

                            // ★ [Phase 5.5-Illust] 엔딩 도달 시 자동 일러스트 생성
                            illustrationService.generateAutoIllustration(
                                freshRoom.getUser().getId(), freshRoom.getCharacter().getId(),
                                freshRoom.getId(), "ENDING", null);
                        }
                    }

                    // [Phase 5.5-Sep] 이스터에그: 스토리 모드 전용
                    EasterEggEvent easterEgg = ChatModePolicy.supportsEasterEggs(freshRoom.getChatMode())  // [이관]
                        ? processEasterEgg(parsed.aiOutput(), jpa.userId()) : null;

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, parsed.sceneResponses(),
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        promoEvent, endingTrigger, easterEgg,
                        statsSnapshot,
                        freshRoom.getDynamicRelationTag(), null,
                        false, null,
                        ChatModePolicy.supportsTopicConcluded(freshRoom.getChatMode()) ? freshRoom.isTopicConcluded() : false,  // [이관]
                        ChatModePolicy.supportsEvents(freshRoom.getChatMode()) ? (wasEventActive ? "RESOLVED" : (freshRoom.isEventActive() ? freshRoom.getEventStatus() : null)) : null);
                });
            } catch (Exception e) {
                log.error("❌ TX-2 failed | roomId={}", roomId, e);
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "응답 처리 중 오류가 발생했습니다.");
                return;
            }
            // [D-2.b] ★ 여기서부터 보상 면제. TX-2가 커밋됐다 = 유저가 대금에 상응하는 것
            //   (응답·스탯·로그)을 이미 받았다는 뜻이고, 이후의 결손은 '전달'뿐이다.
            //   이 플래그가 없으면 최외곽 catch가 그 구간까지 환불해 이중 지급이 된다.
            committed = true;

            // ── MongoDB: ASSISTANT 저장 ──
            // [Phase6/Tier3 / C-9] 단순 try-catch → ChatLogPersister(retry + deadletter)로 위임.
            //   기존 흐름: save 실패 → 로그만 → SSE 정상 전송 → history 누락 → 정합성 파괴.
            //   신규 흐름: 3회 재시도 + 데드레터 보존 + null 시 운영 alert.
            String assistantLogId = null;
            boolean hasInnerThought = false;
            String innerThoughtToSave = ChatModePolicy.supportsInnerThought(jpa.room().getChatMode())
                ? parsed.innerThought() : null;  // [이관] 속마음 SANDBOX 활성
            ChatLogDocument assistantLog = ChatLogDocument.assistantWithThought(
                roomId, parsed.cleanJson(), parsed.combinedDialogue(),
                parsed.mainEmotion(), null, innerThoughtToSave, parsed.scenesJson());
            ChatLogDocument saved = chatLogPersister.saveWithRetry(assistantLog);
            if (saved != null) {
                assistantLogId = saved.getId();
                hasInnerThought = saved.hasInnerThought();
            } else {
                log.error("⚠️ [CHAT-LOG] ASSISTANT_LOG_PERSIST_FAILED — deadlettered | roomId={}", roomId);
                // SSE는 이미 final_result로 전송됨 → 유저 경험은 유지. 운영자가 데드레터 점검 후 수동 복구.
            }
            cacheService.evictRoomInfo(roomId);

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            //  [Phase III · 작업 4] Theater 난입 통합
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            //  Theater 모드 + 난입 활성 상태에서 발생한 ASSISTANT 응답이라면,
            //  마지막 logId를 InterventionService에 알려서 resumeFromIntervention
            //  시 redirectHint에 정확한 컨텍스트가 박히도록 한다.
            //
            //  - 비-Theater 방: chatMode 체크에서 즉시 fallthrough
            //  - 난입 비활성: recordInterventionLog 내부에서 조용히 noop
            //  - 어떤 실패도 채팅 흐름을 깨지 않는다 (try-catch 격리)
            if (assistantLogId != null
                && jpa.room().getChatMode() == ChatMode.THEATER) {
                try {
                    theaterInterventionService.recordInterventionLog(roomId, assistantLogId);
                } catch (Exception e) {
                    log.warn("🎭 [INTERVENTION] log relay failed | roomId={} | logId={}: {}",
                        roomId, assistantLogId, e.getMessage());
                }
            }

            // ★ [Phase 5.5-Illust] 새로운 장소 전환 처리 ★
            // [Phase 6-Illust hotfix] canonical_key 기반 중복 가드 + 같은 장소면 transition 응답 생략
            LocationTransition locationTransition = null;
            // [Q2-Fix] isStory 하드코딩 → ChatModePolicy.supportsSceneDirection 정책 게이트.
            //   프롬프트는 이미 정책 게이트(SANDBOX 포함)로 location 출력을 지시하면서, 처리만 STORY로
            //   막혀 있어 SANDBOX 장소이동/동적배경이 통째로 무시되던 이관 누락의 수정.
            if (ChatModePolicy.supportsSceneDirection(jpa.room().getChatMode())
                && parsed.newLocationName() != null && !parsed.newLocationName().isBlank()) {
                if (jpa.room().getCurrentDynamicLocationName() != null
                    && isSameDynamicLocation(jpa.room(), parsed)) {
                    // 같은 canonical_key → 의미상 동일 장소. 표시명만 달라도(예: "어둡고 축축한 뒷골목" /
                    // "가로등 깜빡이는 밤골목") canonical_key가 같으면 전환 컴포넌트를 띄우지 않는다.
                    // locationTransition은 null 유지 → 응답에 실리지 않음 → 프론트 전환 미발동.
                    log.info("🛡️ [BG-GUARD] Same dynamic location (canonical_key) — transition suppressed | curKey={} | roomId={}",
                        jpa.room().getCurrentDynamicCanonicalKey(), roomId);
                } else {
                    String timeOfDay = resolveBgTimeOfDay(jpa.room(), parsed.lastTime()); // [E-4.16b]
                    final String canonicalKey = parsedCanonicalKey(parsed);
                    final World world = resolveWorldOrNull(jpa.room());
                    // [블록 B 리뷰픽스 P1] 배경 트랙도 게이트 경유 — raw 플래그는 자격 소실
                    //   (페르소나 나이 하향·패스 만료) 후에도 NSFW 트랙에 태운다. 매턴 재판정.
                    final boolean secretMode = resolveSecretMode(jpa.room());

                    BackgroundGenerationService.BackgroundResult bgResult =
                        backgroundGenerationService.resolveBackground(
                            parsed.newLocationName(), canonicalKey, parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId());

                    if (bgResult.cacheHit()) {
                        locationTransition = LocationTransition.cached(
                            parsed.newLocationName(), bgResult.imageUrl());
                    } else {
                        locationTransition = LocationTransition.generating(
                            parsed.newLocationName(), bgResult.cacheHash());
                        backgroundGenerationService.generateBackgroundAsync(
                            parsed.newLocationName(), canonicalKey, parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId(), world, secretMode);
                    }

                    final String bgUrlToStore = bgResult.cacheHit() ? bgResult.imageUrl() : null;
                    final String locationNameToStore = parsed.newLocationName();
                    final String canonicalKeyToStore = canonicalKey;
                    try {
                        txTemplate.execute(status -> {
                            ChatRoom bgRoom = chatRoomRepository.findById(roomId).orElse(null);
                            if (bgRoom != null) {
                                if (bgUrlToStore != null) {
                                    bgRoom.updateDynamicBackground(
                                        locationNameToStore, canonicalKeyToStore, bgUrlToStore);
                                } else {
                                    bgRoom.updateDynamicLocationName(
                                        locationNameToStore, canonicalKeyToStore);
                                }
                            }
                            return null;
                        });
                    } catch (Exception e) {
                        log.warn("⚠️ [BG] Dynamic background persistence failed (non-blocking): {}", e.getMessage());
                    }
                    cacheService.evictRoomInfo(roomId);
                }
            }
            // [Phase 6-Illust] illustration_scene_hint 영속화 (동적 장소 처리와 무관하게 매 응답마다)
            applyParsedToRoom(roomId, parsed);

            // ── [2026-07-30 A-1 재피벗] 매턴 씬 일러 — 인밴드 자동 경로. 실패해도 채팅 흐름 불침해.
            //    [2026-07-31 에픽 B] 트리거 기본값 manual 전환으로 휴면 — trigger=auto일 때만 가동
            //    (수동 경로는 SceneRequestService — 채팅 스트림과 직교). ──
            SendChatResponse.SceneIllustrationInfo sceneIllust = null;
            if (sceneRenderService.autoReady()) {
                try {
                    // [리뷰픽스 수위 게이트] 비시크릿 방은 sfw 강제 — 시크릿(성인인증+BM 통과)만 해제
                    com.spring.aichat.service.illustration.scene.SceneRenderService.SceneView view = sceneRenderService.resolveForTurn(
                        roomId, List.of(jpa.room().getCharacter()),
                        parsed.aiOutput(), (int) (jpa.logCount() + 1), !effectiveSecretMode);
                    if (view != null) {
                        sceneIllust = new SendChatResponse.SceneIllustrationInfo(
                            view.id(), view.turnIndex(), view.status(), view.imageUrl());
                    }
                } catch (Exception e) {
                    log.warn("⚠️ [SCENE-RENDER] resolve 실패 (non-blocking): {}", e.getMessage());
                }
            }

            // ── SSE: final_result ──
            sendFinalResult(emitter, response,
                ChatModePolicy.supportsInnerThought(jpa.room().getChatMode()) && hasInnerThought,  // [이관]
                assistantLogId,
                ChatModePolicy.supportsSceneDirection(jpa.room().getChatMode()) && parsed.generateIllustration(),  // [이관]
                locationTransition, sceneIllust);
            emitter.complete();

            log.info("⏱ [STREAM-PERF] sendMessageStream DONE: {}ms", System.currentTimeMillis() - totalStart);

            triggerPostProcessing(roomId, jpa.userId(), jpa.logCount() + 1, effectiveSecretMode, jpa.room().getChatMode());

        } catch (Exception e) {
            // [docs/19 §F D-23 · 계약] TX-2 **커밋 이후**라면 환불하지 않는다 — 유저가 대금에 상응하는 것
            //   (응답·스탯·로그)을 이미 받았고 결손은 '전달'뿐이다. 거기에 보상을 넣으면 이중 지급이자
            //   전송 실패를 유도하는 무료 획득면이 된다.
            //
            // [D-2.b 정정 2026-09-04] ★ 종전 주석은 "여기는 TX-2 커밋 이후 구간이다"라고 단언했으나
            //   **사실이 아니었다.** 이 catch가 감싸는 try는 :176에서 시작해 TX-1(차감)·evict·인젝션
            //   검사·resolveSecretMode를 전부 포함한다. 즉 차감은 됐는데 TX-2까지 못 간 구간의 예외가
            //   여기로 새어 **무보상**으로 끝났다(로그 저장 실패만 자체 보상이 있었다).
            //   레지스터의 '환불하지 않는다' 결론은 **커밋 이후 구간에 한정**된 것이지 이 catch 전체가
            //   아니다. 그 서술 하나 때문에 이 결함이 '결정으로 종결됨'으로 두 세션을 살아남았다.
            //   → committed 플래그로 두 구간을 가른다. 커밋 전이면 보상, 이후면 면제.
            if (!committed && rollbackCtx != null) {
                log.warn("↩️ [COMPENSATE] TX-2 커밋 전 예외 — 차감·유저로그 되돌림 | roomId={}", roomId);
                compensateFullRollback(rollbackCtx);
            }
            log.error("❌ Unexpected error | roomId={} | committed={}", roomId, committed, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. [Phase 5.5-EV] 👀 계속 지켜보기 (SSE)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void sendDirectorWatchStream(Long roomId, SseEmitter emitter) {
        log.info("👀 [DIRECTOR-WATCH] START | roomId={}", roomId);

        // [D-2.b] 4개 스트림 경로 공통 — 상세는 sendMessageStream의 같은 선언 주석 참조.
        RollbackContext rollbackCtx = null;
        boolean committed = false;

        try {
            // [2026-07-30 P0 공개 철회 리뷰픽스] 우회 경로 차단
            ChatRoom accessCheck = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
            if (blockIfUgcInaccessible(accessCheck, emitter)) return;

            // ── TX-1: 에너지 차감 ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                if (!room.isEventActive()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "진행 중인 이벤트가 없습니다.");
                }

                int cost = 1; // 지켜보기 비용
                EnergySplit charge = room.getUser().consumeEnergy(cost);
                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.getUser().getUsername(), charge);
            });
            // [D-2.b] 차감 직후 보상 컨텍스트 확보 (로그 id는 아래에서 갱신).
            rollbackCtx = new RollbackContext(jpa.userId(), jpa.username(), jpa.energy(), null);
            cacheService.evictUserProfile(jpa.username());

            // ── [Director] 강화된 지켜보기 프롬프트 ──
            // 기존: 하드코딩된 SYSTEM_DIRECTOR_PROMPT
            // 개선: DirectorPromptAssembler가 캐릭터/상황에 맞춤 생성
            String eventContext = buildRecentEventContext(roomId);
            String watchPrompt = new DirectorPromptAssembler().assembleWatchDirective(
                jpa.room().getCharacter(), jpa.room(), eventContext);

            // MongoDB에 SYSTEM_DIRECTOR 메시지 저장
            String savedLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    ChatLogDocument.hiddenSystem(roomId, watchPrompt));
                savedLogId = savedLog.getId();
            } catch (Exception e) {
                compensateEnergy(jpa.userId(), jpa.energy(), jpa.username());
                sendSseError(emitter, "INTERNAL_ERROR", "메시지 저장 실패");
                return;
            }

            // [D-2.b] 로그 id 확정 → 보상 컨텍스트 갱신 (에너지분은 차감 직후 이미 세워 뒀다).
            rollbackCtx = new RollbackContext(
                jpa.userId(), jpa.username(), jpa.energy(), savedLogId);

            boolean effectiveSecretMode = resolveSecretMode(jpa.room());

            // ── LLM 스트림 ──
            ParsedLlmResult parsed = streamLlmAndParse(jpa.room(), jpa.logCount() + 1,
                effectiveSecretMode, emitter, rollbackCtx);
            if (parsed == null) return;

            // ── TX-2: 이벤트 상태만 업데이트 (스탯 동결) ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    // 지켜보기 중에는 스탯 동결
                    freshRoom.updateLastActive(parsed.mainEmotion());

                    // event_status 업데이트 (LLM이 자체 종료하면 RESOLVED)
                    if (parsed.eventStatus() != null) {
                        freshRoom.updateEventStatus(parsed.eventStatus());
                    }

                    // 지켜보기에서도 씬 상태는 업데이트 (장소 이동 등)
                    freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
                        parsed.lastOutfit(), parsed.lastTime());

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, parsed.sceneResponses(),
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        null, null, null,
                        statsSnapshot,
                        freshRoom.getDynamicRelationTag(), null,
                        false, null,
                        false, freshRoom.getEventStatus());
                });
            } catch (Exception e) {
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "지켜보기 처리 실패");
                return;
            }
            committed = true;   // [D-2.b] 이후 구간은 보상 면제

            String assistantLogId = saveAssistantLog(roomId, jpa.room().getChatMode(), parsed);
            cacheService.evictRoomInfo(roomId);

            sendFinalResult(emitter, response, false, assistantLogId, false, null);
            emitter.complete();

            log.info("👀 [DIRECTOR-WATCH] DONE | roomId={}", roomId);

        } catch (Exception e) {
            // [D-2.g/D-2.b] TX-2 커밋 전 예외는 보상한다 — 종전엔 이 catch에 보상이 아예 없어
            //   차감만 되고 아무것도 못 받는 구간이 있었다.
            if (!committed && rollbackCtx != null) {
                log.warn("↩️ [COMPENSATE] 지켜보기 TX-2 커밋 전 예외 — 차감 되돌림 | roomId={}", roomId);
                compensateFullRollback(rollbackCtx);
            }
            log.error("❌ Director watch error | roomId={} | committed={}", roomId, committed, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "지켜보기 처리 중 오류 발생");
        }
    }

    /**
     * 최근 이벤트 컨텍스트 구성 (지켜보기 프롬프트용)
     */
    private String buildRecentEventContext(Long roomId) {
        List<ChatLogDocument> recent = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));

        // 이벤트 시작 이후의 로그만 추출
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = recent.size() - 1; i >= 0 && count < 6; i--) {
            ChatLogDocument doc = recent.get(i);
            String content = doc.getCleanContent() != null ? doc.getCleanContent() : "";
            if (content.length() > 150) content = content.substring(0, 150) + "...";
            sb.insert(0, "[" + doc.getRole().name() + "] " + content + "\n");
            count++;
        }
        return sb.toString().trim();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. [Phase 5.5-EV] 시간 넘기기 (SSE)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void sendTimeSkipStream(Long roomId, SseEmitter emitter) {
        log.info("⏭ [TIME_SKIP] START | roomId={}", roomId);

        // [D-2.b] 4개 스트림 경로 공통 — 상세는 sendMessageStream의 같은 선언 주석 참조.
        RollbackContext rollbackCtx = null;
        boolean committed = false;

        try {
            // [Phase 5.5-Sep] 시간 넘기기: 스토리 모드 전용
            // [2026-07-30 리뷰픽스] findById → fetch join — 철회 가드가 LAZY 밖에서 안전하게 동작
            ChatRoom modeCheck = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
            if (!ChatModePolicy.supportsDirectorMode(modeCheck.getChatMode())) {
                sendSseError(emitter, "MODE_RESTRICTED", "시간 넘기기는 자유(샌드박스) 모드에서만 사용할 수 있습니다.");
                return;
            }
            // [2026-07-30 P0 공개 철회 리뷰픽스] 우회 경로 차단
            if (blockIfUgcInaccessible(modeCheck, emitter)) return;
            // ── TX-1: 에너지 1 차감 ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
                EnergySplit charge = room.getUser().consumeEnergy(TIME_SKIP_ENERGY_COST);
                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.getUser().getUsername(), charge);
            });
            // [D-2.b] 차감 직후 보상 컨텍스트 확보 (로그 id는 아래에서 갱신).
            rollbackCtx = new RollbackContext(jpa.userId(), jpa.username(), jpa.energy(), null);
            cacheService.evictUserProfile(jpa.username());

            // ── MongoDB: 시간 넘기기 시스템 메시지 저장 (프론트 미노출) ──
            String savedLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    ChatLogDocument.hiddenSystem(roomId, TIME_SKIP_PROMPT));
                savedLogId = savedLog.getId();
            } catch (Exception e) {
                compensateEnergy(jpa.userId(), jpa.energy(), jpa.username());
                sendSseError(emitter, "INTERNAL_ERROR", "메시지 저장 실패");
                return;
            }

            // [D-2.b] 로그 id 확정 → 보상 컨텍스트 갱신 (에너지분은 차감 직후 이미 세워 뒀다).
            rollbackCtx = new RollbackContext(
                jpa.userId(), jpa.username(), jpa.energy(), savedLogId);

            boolean effectiveSecretMode = resolveSecretMode(jpa.room());

            // ── LLM 스트림 ──
            ParsedLlmResult parsed = streamLlmAndParse(jpa.room(), jpa.logCount() + 1,
                effectiveSecretMode, emitter, rollbackCtx);
            if (parsed == null) return;

            // ── TX-2: 일반적 스탯 적용 + topic 리셋 ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    applyStatChanges(freshRoom, parsed.statChanges(), effectiveSecretMode);
                    freshRoom.updateLastActive(parsed.mainEmotion());
                    freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
                        parsed.lastOutfit(), parsed.lastTime());
                    freshRoom.updateTopicConcluded(false); // 시간 넘기기 후 → 새 주제 시작

                    // [docs/19 §F D-25] 이 경로에 단계 판정 주체가 없었다.
                    //   블록 D가 refreshRelationFromStats에서 statusLevel 무조건 대입을 제거하면서
                    //   단계 변경을 resolvePromotionLogic으로 일원화했는데, 시간 넘기기에는 그 호출을 안 붙였다
                    //   → 승급·강등이 1턴 지연되고, 같은 TX에서 dynamicRelationTag가 stale한 단계 기준으로
                    //   다시 만들어져 SSE의 relationStatus/tag가 실제 스탯과 어긋난 채 내려갔다.
                    //   (커밋 메시지가 지적한 '시간 넘기기는 승급 로직을 아예 안 탔다'가 방향만 바뀐 채 남아 있었다.)
                    PromotionEvent promoEvent = resolvePromotionLogic(freshRoom, parsed);

                    freshRoom.refreshRelationFromStats();   // [블록 D · §G-1] 태그 갱신 전용

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, parsed.sceneResponses(),
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        promoEvent, null, null,
                        statsSnapshot,
                        freshRoom.getDynamicRelationTag(), null,
                        false, null,
                        false, null); // topic/event 리셋
                });
            } catch (Exception e) {
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "시간 넘기기 처리 실패");
                return;
            }
            committed = true;   // [D-2.b] 이후 구간은 보상 면제

            String assistantLogId = saveAssistantLog(roomId, jpa.room().getChatMode(), parsed);
            cacheService.evictRoomInfo(roomId);

            //   // ★ [Phase 5.5-Illust] 시간 넘기기에서도 장소 전환 가능 ★
            // [Phase 6-Illust hotfix] canonical_key 기반 중복 가드 + 같은 장소면 transition 생략
            SendChatResponse.LocationTransition timeSkipLocationTransition = null;
            if (parsed.newLocationName() != null && !parsed.newLocationName().isBlank()) {
                if (jpa.room().getCurrentDynamicLocationName() != null
                    && isSameDynamicLocation(jpa.room(), parsed)) {
                    log.info("🛡️ [BG-GUARD] TimeSkip same dynamic location (canonical_key) — transition suppressed | curKey={} | roomId={}",
                        jpa.room().getCurrentDynamicCanonicalKey(), roomId);
                } else {
                    String timeOfDay = resolveBgTimeOfDay(jpa.room(), parsed.lastTime()); // [E-4.16b]
                    final String canonicalKey = parsedCanonicalKey(parsed);
                    final World world = resolveWorldOrNull(jpa.room());
                    // [블록 B 리뷰픽스 P1] 배경 트랙도 게이트 경유 — raw 플래그는 자격 소실
                    //   (페르소나 나이 하향·패스 만료) 후에도 NSFW 트랙에 태운다. 매턴 재판정.
                    final boolean secretMode = resolveSecretMode(jpa.room());

                    BackgroundGenerationService.BackgroundResult bgResult =
                        backgroundGenerationService.resolveBackground(
                            parsed.newLocationName(), canonicalKey, parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId());

                    if (bgResult.cacheHit()) {
                        timeSkipLocationTransition = SendChatResponse.LocationTransition.cached(
                            parsed.newLocationName(), bgResult.imageUrl());
                    } else {
                        timeSkipLocationTransition = SendChatResponse.LocationTransition.generating(
                            parsed.newLocationName(), bgResult.cacheHash());
                        backgroundGenerationService.generateBackgroundAsync(
                            parsed.newLocationName(), canonicalKey, parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId(), world, secretMode);
                    }

                    final String bgUrlToStore = bgResult.cacheHit() ? bgResult.imageUrl() : null;
                    final String locationNameToStore = parsed.newLocationName();
                    final String canonicalKeyToStore = canonicalKey;
                    try {
                        txTemplate.execute(status -> {
                            ChatRoom bgRoom = chatRoomRepository.findById(roomId).orElse(null);
                            if (bgRoom != null) {
                                if (bgUrlToStore != null) {
                                    bgRoom.updateDynamicBackground(
                                        locationNameToStore, canonicalKeyToStore, bgUrlToStore);
                                } else {
                                    bgRoom.updateDynamicLocationName(
                                        locationNameToStore, canonicalKeyToStore);
                                }
                            }
                            return null;
                        });
                    } catch (Exception e) {
                        log.warn("⚠️ [BG] Dynamic background persistence failed (non-blocking): {}", e.getMessage());
                    }
                    cacheService.evictRoomInfo(roomId);
                }
            }
            // [Phase 6-Illust] illustration_scene_hint 영속화 (TimeSkip 응답에서도 매번)
            applyParsedToRoom(roomId, parsed);

            sendFinalResult(emitter, response, false, assistantLogId, false, timeSkipLocationTransition);
            emitter.complete();

            log.info("⏭ [TIME_SKIP] DONE | roomId={}", roomId);

        } catch (Exception e) {
            // [D-2.g/D-2.b] TX-2 커밋 전 예외는 보상한다.
            if (!committed && rollbackCtx != null) {
                log.warn("↩️ [COMPENSATE] 시간 넘기기 TX-2 커밋 전 예외 — 차감 되돌림 | roomId={}", roomId);
                compensateFullRollback(rollbackCtx);
            }
            log.error("❌ Time skip error | roomId={} | committed={}", roomId, committed, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "시간 넘기기 처리 중 오류 발생");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [블록 D · §G-1] 관계 승급 — 임계 도달 즉시 승급
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 5턴 '승급 시험'을 폐지하고 임계 도달 시 곧바로 승급시킨다(종원 확정 (b)안).
     *
     * <p>폐지 이유 — 시험이 관문으로 기능하지 않았다:
     * <ul>
     *   <li>진행도가 스탯 변화량의 <b>절댓값 합</b>이라 캐릭터를 모욕해도 합격했다(docs/13 E-4.2).</li>
     *   <li>{@code refreshRelationFromStats}가 매 턴 statusLevel을 덮어써서, 유발 축이 affection이
     *       아니면 시험을 거치지 않고 승급했고 실패 강등도 같은 TX에서 되돌려졌다.</li>
     *   <li>실패→강등은 '무한 관계 시뮬'이라는 제품 방향과 정면 충돌한다(§G-1).</li>
     * </ul>
     *
     * <p>세리머니(Relationship Up 연출)는 유지한다 — §G-1이 명시적으로 남기라고 한 부분이다.
     * 단 <b>ENEMY에서의 회복은 세리머니 없이 단계만 조용히 복원</b>한다(종원 확정).
     */
    private PromotionEvent resolvePromotionLogic(ChatRoom room, ParsedLlmResult parsed) {
        RelationStatus oldStatus = room.getStatusLevel();
        room.applyLegacyAffectionChange(parsed.aiOutput().affectionChange());

        RelationStatus newStatus = RelationStatusPolicy.fromStats(
            room.getStatAffection(),
            room.getStatIntimacy(), room.getStatAffection(),
            room.getStatDependency(), room.getStatPlayfulness(), room.getStatTrust()
        );
        if (newStatus == oldStatus) return null;

        room.updateStatusLevel(newStatus);

        // 적대에서 벗어나는 것은 새 단계의 획득이 아니라 원상복귀 — 연출 없이 복원만.
        if (RelationStatusPolicy.isEnemyRecovery(oldStatus, newStatus)) {
            log.info("🎯 [PROMOTION] ENEMY recovery (silent) | {} → {} | roomId={}",
                oldStatus, newStatus, room.getId());
            return null;
        }
        if (!RelationStatusPolicy.isUpgrade(oldStatus, newStatus)) {
            log.info("🎯 [PROMOTION] Downgrade | {} → {} | roomId={}", oldStatus, newStatus, room.getId());
            return null;
        }

        log.info("🎯 [PROMOTION] Upgrade | {} → {} | maxStat={} | roomId={}",
            oldStatus, newStatus, room.getMaxNormalStatValue(), room.getId());

        // [§G-5 정합] 관계 해금이 꺼져 있으면 이미 전 복장·장소가 열려 있다 →
        //   "New Unlocks" 카드를 띄우면 이미 쓸 수 있던 것을 해금이라 광고하게 된다.
        List<UnlockInfo> unlocks = legacy.getUnlock().isRelationGated()
            ? room.getCharacter().getUnlocksForRelation(newStatus).stream()
                .map(u -> new UnlockInfo(u.type(), u.name(), u.displayName()))
                .collect(Collectors.toList())
            : List.of();

        return new PromotionEvent("SUCCESS", newStatus.name(),
            RelationStatusPolicy.getDisplayName(newStatus), 0, 0, unlocks);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 LLM 스트림 호출 + 파싱
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ParsedLlmResult streamLlmAndParse(ChatRoom room, long logCountForRag,
                                              boolean effectiveSecretMode,
                                              SseEmitter emitter, RollbackContext rollbackCtx) {
        // RAG 메모리
        String longTermMemory = "";
        long ragThreshold = ChatModePolicy.getRagSkipThreshold(room.getChatMode());
        if (logCountForRag >= ragThreshold) {
            try {
                longTermMemory = memoryService.retrieveContext(room.getId());
            } catch (Exception e) {
                log.warn("RAG failed (non-blocking): {}", e.getMessage());
            }
        }

        // 프롬프트 조립
        CharacterPromptAssembler.SystemPromptPayload systemPrompt =
            promptAssembler.assembleSystemPrompt(
                room.getCharacter(), room, room.getUser(),
                longTermMemory, effectiveSecretMode);

        List<OpenAiMessage> messages = buildMessageHistory(
            room.getId(), systemPrompt,
            room.getCharacter().getName(), room.getUser().getNickname());
        String model = boostModeResolver.resolveModel(room.getUser());

        // ━━━ [Phase 5.5-Stability] 서킷 브레이커 기반 Provider 결정 ━━━
        LlmCircuitBreaker.ProviderDecision decision = llmCircuitBreaker.decide();
        log.info("🔌 [CIRCUIT] Provider decision: {} | deadline={}ms | state={} | roomId={}",
            decision.provider(), decision.ttftDeadlineMs(), llmCircuitBreaker.getState(), room.getId());

        // ── SSE 콜백 정의 (Primary/Fallback 재시도 시에도 동일하게 사용) ──
        // [Polish · P1 #2] dialogue prefix sanitizer를 위해 알려진 화자 이름 모음.
        //   STORY/SANDBOX 모드는 캐릭터(메인 화자) + 유저 nickname만 안다.
        //   NPC 화자가 등장할 수 있으므로 안전한 화이트리스트 매칭만 수행.
        final java.util.Set<String> sanitizerSpeakers = new java.util.LinkedHashSet<>();
        if (room.getCharacter() != null && room.getCharacter().getName() != null) {
            sanitizerSpeakers.add(room.getCharacter().getName().trim());
        }
        if (room.getUser() != null && room.getUser().getNickname() != null) {
            sanitizerSpeakers.add(room.getUser().getNickname().trim());
        }

        Consumer<String> onFirstScene = firstSceneJson -> {
            try {
                AiJsonOutput.Scene scene = objectMapper.readValue(firstSceneJson, AiJsonOutput.Scene.class);
                EmotionTag emotion = LlmOutputParser.parseEmotion(scene.emotion());
                // [Polish · P1 #2] dialogue / narration 화자 prefix 제거
                String sanitizedDialogue = com.spring.aichat.service.util.DialogueSanitizer
                    .stripSpeakerPrefix(scene.dialogue(), sanitizerSpeakers);
                String sanitizedNarration = com.spring.aichat.service.util.DialogueSanitizer
                    .stripSpeakerPrefix(scene.narration(), sanitizerSpeakers);
                SceneResponse firstScene = new SceneResponse(
                    scene.speaker(),
                    sanitizedNarration, sanitizedDialogue, emotion,
                    LlmOutputParser.safeUpperCase(scene.location()), LlmOutputParser.safeUpperCase(scene.time()),
                    LlmOutputParser.safeUpperCase(scene.outfit()), LlmOutputParser.safeUpperCase(scene.bgmMode()));
                emitter.send(SseEmitter.event().name("first_scene")
                    .data(objectMapper.writeValueAsString(firstScene)));
            } catch (Exception e) {
                log.warn("first_scene send failed: {}", e.getMessage());
            }
        };
        Consumer<String> onEventStatus = eventStatus -> {
            try {
                Map<String, String> meta = Map.of("eventStatus", eventStatus);
                emitter.send(SseEmitter.event().name("event_meta")
                    .data(objectMapper.writeValueAsString(meta)));
                log.info("🎬 [SSE] event_meta sent: {} (before first_scene)", eventStatus);
            } catch (Exception e) {
                log.warn("event_meta send failed: {}", e.getMessage());
            }
        };

        // ── LLM 스트림 (서킷 브레이커 연동) ──
        StreamResult streamResult;
        try {
            Map<String, Object> providerRouting = Map.of(
                "order", List.of(decision.provider()),
                "allow_fallbacks", false
            );
            OpenAiChatRequest llmRequest = new OpenAiChatRequest(
                model, messages, 0.8, true, 0.3, 0.15, providerRouting, Map.of("type", "json_object"),
                6144);  // [Q2-Fix] 멀티씬(2~3)+location 필드 한글 JSON 여유 — scenes 배열 중간 잘림(파스 에러) 방지

            streamResult = streamClient.streamCompletion(
                llmRequest, onFirstScene, onEventStatus, decision.ttftDeadlineMs());

            // ✅ AI Studio 성공 → 서킷 브레이커 기록
            if (decision.isPrimary()) {
                llmCircuitBreaker.recordSuccess(streamResult.ttft());
            }

        } catch (TtftTimeoutException ttftEx) {
            // ━━━ [Stability] TTFT 데드라인 초과 → 실패 기록 + Vertex 즉시 폴백 ━━━
            llmCircuitBreaker.recordFailure(ttftEx.getDeadlineMs());
            log.warn("🔄 [CIRCUIT] TTFT 초과 → Vertex 폴백 | deadline={}ms | state={} | roomId={}",
                ttftEx.getDeadlineMs(), llmCircuitBreaker.getState(), room.getId());

            try {
                Map<String, Object> fallbackRouting = Map.of(
                    "order", List.of(LlmCircuitBreaker.PROVIDER_VERTEX),
                    "allow_fallbacks", false
                );
                OpenAiChatRequest fallbackRequest = new OpenAiChatRequest(
                    model, messages, 0.8, true, 0.3, 0.15, fallbackRouting, Map.of("type", "json_object"),
                    6144);  // [Q2-Fix]

                streamResult = streamClient.streamCompletion(
                    fallbackRequest, onFirstScene, onEventStatus, 0); // Vertex는 데드라인 없음

                log.info("✅ [CIRCUIT] Vertex 폴백 성공 | TTFT={}ms | roomId={}", streamResult.ttft(), room.getId());

            } catch (Exception fallbackEx) {
                log.error("❌ [CIRCUIT] Vertex 폴백마저 실패 | roomId={}", room.getId(), fallbackEx);
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "LLM_ERROR", "AI 응답 생성 실패 (폴백 포함)");
                return null;
            }

        } catch (Exception e) {
            // 기타 예외 (네트워크 에러, 5xx 등) — AI Studio 시도였다면 실패 기록
            if (decision.isPrimary()) {
                llmCircuitBreaker.recordFailure(-1);
            }
            log.error("LLM stream failed | provider={} | roomId={}", decision.provider(), room.getId(), e);
            compensateFullRollback(rollbackCtx);
            sendSseError(emitter, "LLM_ERROR", "AI 응답 생성 실패");
            return null;
        }

        // ── JSON 파싱 ──
        AiJsonOutput aiOutput;
        String cleanJson;
        try {
            cleanJson = LlmOutputParser.extractJson(streamResult.fullResponse());
            aiOutput = objectMapper.readValue(cleanJson, AiJsonOutput.class);
        } catch (JsonProcessingException e) {
            log.error("JSON Parse Error: {}", streamResult.fullResponse(), e);
            compensateFullRollback(rollbackCtx);
            sendSseError(emitter, "PARSE_ERROR", "AI 응답 형식 오류");
            return null;
        }

        // ── 결과 정리 ──
        // [Phase 5.5-Fix] cleanContent: 나레이션 + 대사 통합 (재로딩 시 fallback 표시용)
        String combinedDialogue = buildRichCleanContent(aiOutput.scenes());

        String lastEmotionStr = aiOutput.scenes().isEmpty() ? "NEUTRAL"
            : aiOutput.scenes().get(aiOutput.scenes().size() - 1).emotion();
        EmotionTag mainEmotion = LlmOutputParser.parseEmotion(lastEmotionStr);

        List<SceneResponse> sceneResponses = aiOutput.scenes().stream()
            .map(s -> new SceneResponse(
                s.speaker(),                        // [Phase 5.5-NPC] 화자
                // [Polish · P1 #2] narration / dialogue 화자 prefix 제거.
                //   final_result에서도 일관성 유지 — first_scene과 같은 sanitizerSpeakers 사용.
                com.spring.aichat.service.util.DialogueSanitizer.stripSpeakerPrefix(
                    s.narration(), sanitizerSpeakers),
                com.spring.aichat.service.util.DialogueSanitizer.stripSpeakerPrefix(
                    s.dialogue(), sanitizerSpeakers),
                LlmOutputParser.parseEmotion(s.emotion()),
                LlmOutputParser.safeUpperCase(s.location()), LlmOutputParser.safeUpperCase(s.time()),
                LlmOutputParser.safeUpperCase(s.outfit()), LlmOutputParser.safeUpperCase(s.bgmMode())))
            .collect(Collectors.toList());

        // [Phase 5.5-Fix] scenesJson: 씬 배열 구조화 저장 (재로딩 시 씬별 분리 복원용)
        String scenesJson = buildScenesJson(sceneResponses);

        String innerThought = aiOutput.innerThought();
        if (innerThought != null && innerThought.isBlank()) innerThought = null;

        return new ParsedLlmResult(
            aiOutput, cleanJson, combinedDialogue, mainEmotion, sceneResponses,
            LlmOutputParser.extractLastNonNull(sceneResponses, SceneResponse::bgmMode),
            LlmOutputParser.extractLastNonNull(sceneResponses, SceneResponse::location),
            LlmOutputParser.extractLastNonNull(sceneResponses, SceneResponse::outfit),
            LlmOutputParser.extractLastNonNull(sceneResponses, SceneResponse::time),
            aiOutput.statChanges(), innerThought,
            aiOutput.isTopicConcluded(),
            aiOutput.eventStatus(),
            scenesJson,
            aiOutput.shouldGenerateIllustration(),
            aiOutput.newLocationName(),
            aiOutput.locationDescription()
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // [Bug #3 Fix] Room-level 시크릿 모드 판정
    private boolean resolveSecretMode(ChatRoom room) {
        return room.isSecretModeActive()
            && secretModeService.canAccessSecretMode(room.getUser(), room.getCharacter().getId());
    }

    /**
     * [E-5.1.b] {@code eventContext} 길이 상한 적용.
     *
     * <p>400으로 <b>거부하지 않고</b> 절단 + WARN으로 둔다 — 이 값은 서버가 발급한 분기 오퍼에서
     * 오는 것이 정상이라 상한에 걸리는 것 자체가 비정상 경로이고, 여기서 거부하면 서버가 긴
     * 나레이션을 생성한 날 정상 유저가 통째로 막힌다(CLAUDE.md §D — 착취를 막기 전에 정상 유저를
     * 막는가를 먼저 묻는다). 절단은 은폐가 아니다: 로그가 남고 그 빈도가 상한 재조정의 근거가 된다.
     */
    private String capEventContext(Long roomId, String raw) {
        if (raw == null || raw.length() <= EVENT_CONTEXT_MAX) return raw;
        log.warn("✂️ [EVENT-CTX] 길이 상한 초과 — 절단 | roomId={} | {}자 → {}자",
            roomId, raw.length(), EVENT_CONTEXT_MAX);
        return raw.substring(0, EVENT_CONTEXT_MAX);
    }

    /**
     * [D-6.5 · D-6.4] 지켜보기·시간넘기기·자동응답(AWAY/BRANCH) 3경로의 ASSISTANT 저장.
     *
     * <p>종전에는 {@code chatLogRepository.save}를 직접 부르고 예외를 삼켰다. 같은 클래스가
     * {@link ChatLogPersister}를 <b>이미 주입받고 있는데</b> 정상 경로(:369-383)만 그것을 썼다.
     * 그래서 이 3경로에서만 3회 지수백오프 재시도와 deadletter 보존이 통째로 빠졌고,
     * 저장이 실패하면 SSE는 정상 전송되는데 로그만 사라져
     * <b>history 누락 · 다음 LLM 컨텍스트 손실 · 새로고침 시 응답 영구 손실</b>이 됐다
     * ({@code ChatLogPersister} 클래스 주석이 명시한 피해 그대로).
     * 특히 이벤트 카드 선택의 실질 경로가 자동응답이라 트래픽이 가장 많다(D-6.4).
     *
     * <p>부가: 종전에는 {@code parsed.innerThought()}를 <b>무조건</b> 넘겨 정상 경로가 통과시키는
     * {@code ChatModePolicy.supportsInnerThought} 게이트도 함께 우회했다. 그래서 모드를 인자로 받는다
     * — §2-6대로 구 2-인자 오버로드는 남기지 않는다(컴파일러가 호출부를 전수로 드러내야 한다).
     */
    private String saveAssistantLog(Long roomId, ChatMode mode, ParsedLlmResult parsed) {
        String inner = ChatModePolicy.supportsInnerThought(mode) ? parsed.innerThought() : null;
        ChatLogDocument doc = ChatLogDocument.assistantWithThought(
            roomId, parsed.cleanJson(), parsed.combinedDialogue(),
            parsed.mainEmotion(), null, inner, parsed.scenesJson());
        ChatLogDocument saved = chatLogPersister.saveWithRetry(doc);
        if (saved != null) return saved.getId();

        log.error("⚠️ [CHAT-LOG] ASSISTANT_LOG_PERSIST_FAILED — deadlettered | roomId={} | mode={}",
            roomId, mode);
        return null;
    }

    /**
     * [2026-07-30 P0 공개 철회 리뷰픽스] UGC 접근 재검증 공용 가드 — 메시지 전송뿐 아니라
     * 이벤트/지켜보기/시간넘기기/디렉터 자동응답 SSE 전 경로에 적용(우회 차단).
     * 호출 전 방은 반드시 fetch join(findWithMemberAndCharacterById)으로 로드할 것.
     *
     * @return true면 차단됨(SSE 에러 전송 완료) — 호출측은 즉시 return
     */
    private boolean blockIfUgcInaccessible(ChatRoom room, SseEmitter emitter) {
        if (room.getCharacter().isUgc()
            && !room.getCharacter().isAccessibleBy(room.getUser().getId())) {
            sendSseError(emitter, "CHARACTER_UNAVAILABLE", "이 캐릭터는 더 이상 대화할 수 없어요.");
            return true;
        }
        return false;
    }

    private void sendFinalResult(SseEmitter emitter, SendChatResponse response,
                                 boolean hasInnerThought, String assistantLogId,
                                 boolean generateIllustration,
                                 LocationTransition locationTransition) {
        sendFinalResult(emitter, response, hasInnerThought, assistantLogId,
            generateIllustration, locationTransition, null);
    }

    private void sendFinalResult(SseEmitter emitter, SendChatResponse response,
                                 boolean hasInnerThought, String assistantLogId,
                                 boolean generateIllustration,
                                 LocationTransition locationTransition,
                                 SendChatResponse.SceneIllustrationInfo sceneIllustration) {
        try {
            SendChatResponse finalResponse = new SendChatResponse(
                response.roomId(), response.scenes(),
                response.currentAffection(), response.relationStatus(),
                response.promotionEvent(), response.endingTrigger(), response.easterEgg(),
                response.stats(),
                response.dynamicRelationTag(), response.characterThought(),
                hasInnerThought, assistantLogId,
                response.topicConcluded(), response.eventStatus(),
                generateIllustration, locationTransition, null, sceneIllustration);

            emitter.send(SseEmitter.event().name("final_result")
                .data(objectMapper.writeValueAsString(finalResponse)));
        } catch (Exception e) {
            log.warn("final_result send failed: {}", e.getMessage());
        }
    }

    private EasterEggEvent processEasterEgg(AiJsonOutput aiOutput, Long userId) {
        String eggTrigger = aiOutput.easterEggTrigger();
        if (eggTrigger == null || eggTrigger.isBlank()) return null;
        try {
            EasterEggType eggType = EasterEggType.valueOf(eggTrigger.toUpperCase());
            var unlock = achievementService.unlockEasterEgg(userId, eggType);
            boolean revert = (eggType == EasterEggType.FOURTH_WALL);
            // [docs/19 §C-2 · P0] 업적 게이트 오프(기본값)면 unlockEasterEgg가 null을 반환한다
            //   (AchievementService:67). 여기서 무가드로 역참조하면 NPE가 아래 catch(IllegalArgumentException)에
            //   안 잡히고 TX-2 밖 catch(Exception)로 가서 compensateFullRollback + TX_ERROR —
            //   유저가 본문을 다 본 뒤 턴 전체(로그·메시지·스탯)를 잃는다. SANDBOX 주력 표면이다.
            //   docs/14 §C#6이 '이스터에그 연출은 유지, 업적만 오프'로 확정했으므로 achievement만 null로 내린다.
            AchievementInfo achievement = (unlock == null) ? null
                : new AchievementInfo(unlock.code(), unlock.title(), unlock.titleKo(),
                    unlock.description(), unlock.icon(), unlock.isNew());
            return new EasterEggEvent(eggType.name(), achievement, revert);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void applyStatChanges(ChatRoom room, AiJsonOutput.StatChanges sc, boolean isSecretMode) {
        if (sc == null) return;
        // [2026-07-31 난이도] 이중 게이트 결정론 층 — 양수 델타 확률 반올림 배율(음수는 통과)
        if (room.getCharacter() != null) {
            sc = sc.scaledGains(room.getCharacter().getDifficultyOrDefault());
        }
        room.applyNormalStatChanges(sc.safeIntimacy(), sc.safeAffection(),
            sc.safeDependency(), sc.safePlayfulness(), sc.safeTrust());
        if (isSecretMode) {
            room.applySecretStatChanges(sc.safeLust(), sc.safeCorruption(), sc.safeObsession());
        }
    }

    private StatsSnapshot buildStatsSnapshot(ChatRoom room, boolean isSecretMode) {
        return new StatsSnapshot(room.getStatIntimacy(), room.getStatAffection(),
            room.getStatDependency(), room.getStatPlayfulness(), room.getStatTrust(),
            isSecretMode ? room.getStatLust() : null,
            isSecretMode ? room.getStatCorruption() : null,
            isSecretMode ? room.getStatObsession() : null);
    }

    private void sendSseError(SseEmitter emitter, String errorCode, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                .data(objectMapper.writeValueAsString(Map.of("errorCode", errorCode, "message", message))));
            emitter.complete();
        } catch (Exception e) {
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }
    }

    /** [D-1.5] TX-1 차감의 free/paid 분할을 그대로 되돌린다 — 총액 환불은 유료분을 free로 흡수시켰다. */
    private void compensateEnergy(Long userId, EnergySplit charge, String username) {
        try {
            txTemplate.execute(status -> {
                User user = userRepository.findById(userId)
                    .orElseThrow(() -> new NotFoundException("User not found"));
                user.refundEnergy(charge);
                userRepository.save(user);
                return null;
            });
        } catch (Exception ex) {
            log.error("Energy refund FAILED: userId={}", userId, ex);
        }
        cacheService.evictUserProfile(username);
    }

    private void compensateFullRollback(RollbackContext ctx) {
        if (ctx.savedUserLogId() != null) {
            try { chatLogRepository.deleteById(ctx.savedUserLogId()); }
            catch (Exception ex) { log.error("User msg delete FAILED", ex); }
        }
        compensateEnergy(ctx.userId(), ctx.energy(), ctx.username());
    }

    /**
     * [Phase 5.5-Director] 비동기 후처리 — 디렉터 판단 통합
     *
     * 기존: 메모리 요약 + 캐릭터 생각
     * 추가: 디렉터 비동기 판단 (스토리 모드 전용)
     */
    private void triggerPostProcessing(Long roomId, Long userId, long totalLogCount,
                                       boolean isSecretMode, ChatMode chatMode) {
        long userMsgCount = chatLogRepository.countByRoomIdAndRole(roomId, ChatRole.USER);

        // ── 기존: 메모리 요약 ──
        long memoryCycle = ChatModePolicy.getMemorySummarizationCycle(chatMode);
        if (userMsgCount > 0 && userMsgCount % memoryCycle == 0) {
            memoryService.summarizeAndSaveMemory(roomId, userId);
        }

        // ── 기존: 캐릭터 생각 ──
        long thoughtCycle = ChatModePolicy.getCharacterThoughtCycle(chatMode);
        long thoughtOffset = ChatModePolicy.getCharacterThoughtOffset(chatMode);
        if (userMsgCount > 0 && userMsgCount % thoughtCycle == thoughtOffset) {
            chatService.generateCharacterThoughtAsync(roomId, userId, (int) userMsgCount, isSecretMode);
        }

        // ── [Phase 6 도그푸딩 #1] 자동 디렉터 인터루드 폐기 ──
        // 도그푸딩 결과: 자동 인터루드가 유저의 깊은 대화 흐름을 끊어 UX 저하.
        // 폴리시: 유저의 명시적 의지(StoryController#requestDirector "다음 씬" 버튼)만 유지.
        // 향후 부활 시 이 블록 + DirectorService.evaluateAndCache의 @Deprecated 표식 함께 정리.
    }

    /**
     * [Phase 5.5-Fix-IT] LLM 히스토리 구성 (이름표 부착 + 속마음 컨텍스트 주입)
     *
     * 최근 N개의 ASSISTANT 메시지에 속마음(inner_thought)을 포함하여
     /**
     * [Hallucination Fix] LLM 컨텍스트용 대화 히스토리 구성
     *
     * 환각 방지를 위한 핵심 원칙:
     *   1. USER 메시지: role="user"만으로 화자 식별 — 텍스트 태그([유저]:) 제거
     *   2. SYSTEM 나레이션: role="system"으로 전환 — 유저 발화와 구조적 분리
     *   3. ASSISTANT 메시지: 감정 메타데이터 제거 — 대사+나레이션만 유지
     *
     * [속마음 히스토리]
     * 최근 N개 ASSISTANT 메시지에만 이전 속마음을 포함하여
     * LLM이 이전 속마음을 인지하고 반복을 회피하도록 유도.
     */
    private List<OpenAiMessage> buildMessageHistory(Long roomId, CharacterPromptAssembler.SystemPromptPayload systemPrompt,
                                                    String characterName, String userNickname) {
        List<ChatLogDocument> history = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
        history.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();

        if (history.size() == 3 || history.size() % 20 == 0) {
            messages.add(OpenAiMessage.systemCached(systemPrompt.staticRules(), Map.of("type", "ephemeral")));
        } else messages.add(OpenAiMessage.system(systemPrompt.staticRules()));

        // [Phase 5.5-Fix-IT] ASSISTANT 메시지 역순 카운팅 — 최근 N개 판별
        int totalAssistantCount = 0;
        for (ChatLogDocument log : history) {
            if (log.getRole() == ChatRole.ASSISTANT) totalAssistantCount++;
        }
        int assistantThreshold = totalAssistantCount - INNER_THOUGHT_HISTORY_WINDOW;

        // [Bug Fix] 인트로 환각 방지: 첫 ASSISTANT 앞에 USER가 없으면 합성 삽입
        boolean needsSyntheticUserMsg = false;
        if (!history.isEmpty()) {
            for (ChatLogDocument log : history) {
                if (log.getRole() == ChatRole.USER) { break; }
                if (log.getRole() == ChatRole.ASSISTANT) { needsSyntheticUserMsg = true; break; }
            }
        }

        int assistantIdx = 0;
        for (ChatLogDocument chatLog : history) {
            if (needsSyntheticUserMsg && chatLog.getRole() == ChatRole.ASSISTANT) {
                messages.add(OpenAiMessage.user("(입장)"));
                needsSyntheticUserMsg = false;
            }

            switch (chatLog.getRole()) {
                // [Fix] USER: role="user"가 화자 신호 — 텍스트 태그 불필요
                case USER -> messages.add(OpenAiMessage.user(chatLog.getRawContent()));

                // [Fix] ASSISTANT: 감정 메타데이터 제거, 대사+나레이션만 유지
                case ASSISTANT -> {
                    boolean includeThought = assistantIdx >= assistantThreshold;
                    String sanitized = LlmOutputParser.buildSanitizedAssistantContent(
                        objectMapper, chatLog, characterName, includeThought);
                    messages.add(OpenAiMessage.assistant(sanitized));
                    assistantIdx++;
                }

                // [Fix 핵심] SYSTEM 나레이션: role="system"으로 전환
                // 기존: role="user" + [NARRATION] 태그 → 유저 발화로 오귀속
                // 수정: role="system" → 구조적으로 제3자 서술임을 명시
                case SYSTEM -> messages.add(
                    OpenAiMessage.system("[NARRATION] " + chatLog.getRawContent())
                );
            }
        }

        messages.add(OpenAiMessage.system(systemPrompt.dynamicRules()));
        messages.add(OpenAiMessage.system(systemPrompt.outputFormat()));

        return messages;
    }

    // [Bug #6 Fix] buildSanitizedAssistantContent, extractJson, parseEmotion,
    // safeUpperCase, extractLastNonNull → LlmOutputParser로 통합 이관

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Fix] 3-Layer 통일 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 씬 배열에서 나레이션 + 대사 + speaker를 포함한 풍부한 cleanContent 생성.
     * 재로딩 시 scenesJson이 없을 경우의 fallback 표시용.
     */
    private String buildRichCleanContent(List<AiJsonOutput.Scene> scenes) {
        if (scenes == null || scenes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scenes.size(); i++) {
            AiJsonOutput.Scene scene = scenes.get(i);
            if (i > 0) sb.append("\n---\n"); // 씬 구분자
            if (scene.speaker() != null && !scene.speaker().isBlank()) {
                sb.append("[").append(scene.speaker()).append("] ");
            }
            if (scene.narration() != null && !scene.narration().isBlank()) {
                sb.append("*").append(scene.narration().trim()).append("*\n");
            }
            if (scene.dialogue() != null && !scene.dialogue().isBlank()) {
                sb.append(scene.dialogue().trim());
            }
        }
        return sb.toString().trim();
    }

    /**
     * SceneResponse 리스트를 JSON 배열 문자열로 직렬화.
     * 프론트에서 재로딩 시 씬별 분리/speaker/narration을 완전히 복원.
     */
    private String buildScenesJson(List<SceneResponse> scenes) {
        if (scenes == null || scenes.isEmpty()) return null;
        try {
            // SceneResponse를 가벼운 Map으로 변환 (불필요한 필드 제거)
            List<Map<String, Object>> simplified = scenes.stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                if (s.speaker() != null) m.put("speaker", s.speaker());
                if (s.narration() != null) m.put("narration", s.narration());
                if (s.dialogue() != null) m.put("dialogue", s.dialogue());
                if (s.emotion() != null) m.put("emotion", s.emotion().name());
                // [2026-08-07 리플레이] 씬 컨텍스트 보존(additive) — 과거 씬 재현 시 복장·장소·
                // 시간 복원용. 레거시 로그(필드 없음)는 프론트가 현재값 폴백.
                if (s.location() != null) m.put("location", s.location());
                if (s.time() != null) m.put("time", s.time());
                if (s.outfit() != null) m.put("outfit", s.outfit());
                if (s.bgmMode() != null) m.put("bgmMode", s.bgmMode());
                return m;
            }).collect(Collectors.toList());
            return objectMapper.writeValueAsString(simplified);
        } catch (Exception e) {
            log.warn("scenesJson serialization failed", e);
            return null;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [v3] 투명 디렉터 자동 응답 (INTERLUDE / TRANSITION / AWAY)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 디렉터 Directive가 소비되고 constraint가 ChatRoom에 적용된 상태에서
     * 캐릭터의 자동 응답을 SSE로 생성.
     *
     * INTERLUDE/TRANSITION: 원샷 응답 → constraint 자동 클리어
     * AWAY: 이벤트 ONGOING 진입 → 유저 개입 전까지 멀티턴
     */
    @Async
    public void sendAutoDirectorResponse(Long roomId, String directiveType, String eventContext,
                                         Integer chosenIndex, SseEmitter emitter) {
        log.info("🎬 [DIRECTOR-AUTO-RESPOND] START | type={} | context={} | roomId={}",
            directiveType, eventContext != null ? eventContext.length() + "chars" : "null", roomId);
        boolean isAway = "AWAY".equalsIgnoreCase(directiveType);
        boolean isBranchResponse = "BRANCH".equalsIgnoreCase(directiveType);

        // [E-5.1.b] eventContext는 클라이언트가 완전히 제어하는 문자열인데(StoryController:99
        //   AutoRespondRequest — 검증 애노테이션 0개) SYSTEM 롤 로그로 영구 저장되고 매 턴
        //   role="system"으로 재주입된다(:1348). PromptInjectionGuard가 "채팅은 user role이라
        //   위험도가 낮다"는 전제로 감지만 하도록 설계됐는데(:183-188) 이 경로는 그 전제가
        //   성립하지 않는다. 게다가 유저가 SYSTEM 로그를 지울 수도 없다(ChatService:409-411).
        //   ⚠ 파라미터는 TX-1 람다가 캡처하므로(setDirectorInterlude) 재대입할 수 없다 —
        //     진입부에서 정제한 별도 지역변수를 만들어 **모든 소비처**를 그것으로 바꾼다.
        //     람다 안의 setDirectorInterlude도 LLM에 흘러가므로 반드시 포함해야 한다.
        final String safeEventContext = capEventContext(roomId, eventContext);

        // [D-2.g] ★ 이 경로가 가장 비싸다 — BRANCH 이벤트 카드는 최대 4E다.
        //   종전 최외곽 catch에는 보상이 전혀 없어, 차감 후 TX-2 전에 예외가 나면 4E가 통째로
        //   소멸하는데 프론트는 환불된 것처럼 보였다. 상세는 sendMessageStream의 같은 선언 주석.
        RollbackContext rollbackCtx = null;
        boolean committed = false;

        try {
            // [2026-07-30 P0 공개 철회 리뷰픽스] 우회 경로 차단
            ChatRoom accessCheck = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
            if (blockIfUgcInaccessible(accessCheck, emitter)) return;

            // ── TX-1: 에너지 차감 ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                // [블록 D · §G-13 복구 + docs/13 P0] BRANCH 비용은 **서버가 정한다**.
                //   2026-02 `6d3ed07` 이후 FE가 energyCost(2/3/4)를 보내왔지만 여기서는 1로 고정돼
                //   과소 청구 + FE 표기 불일치 상태였다. 이제 요청 시 캐싱해 둔 가격표를
                //   chosenIndex로 재판정한다(클라이언트 값은 신뢰하지 않는다).
                //   캐시 만료·구 FE(인덱스 미전송)는 기존 동작대로 1로 폴백한다.
                int cost = isBranchResponse
                    ? directorService.resolveBranchCost(roomId, chosenIndex).orElse(1)
                    : 1;
                EnergySplit charge = room.getUser().consumeEnergy(cost);

                if (isAway) {
                    room.updateEventStatus("ONGOING");
                    room.updateTopicConcluded(false);
                }

                // [Bug Fix] BRANCH 카드 선택 시: constraint로 detail 적용
                if (isBranchResponse && safeEventContext != null && !safeEventContext.isBlank()) {
                    room.setDirectorInterlude(safeEventContext,
                        "상황: " + safeEventContext + " — 이 상황에 자연스럽게 반응하세요.");
                }

                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.getUser().getUsername(), charge);
            });
            // [D-2.g] 차감 직후 보상 컨텍스트 확보 (로그 id는 아래에서 갱신).
            rollbackCtx = new RollbackContext(jpa.userId(), jpa.username(), jpa.energy(), null);
            cacheService.evictUserProfile(jpa.username());

            // [E-5.1.b] 인젝션 감지·적재. sendMessageStream(:222)과 같은 '감지 + 기록' 정책을 쓴다.
            //   ⚠ 레지스터는 "이 경로는 SYSTEM 롤이라 CRITICAL이면 **차단**해야 한다"고 권고하면서도
            //     오탐 시 UX 손상을 이유로 ❓결정 필요로 남겼다. 차단은 새 거부면이라 종원 판단
            //     전까지 넣지 않는다 — 우선 적재해 실제 감지율을 관측한다.
            //     (안건 13 (나) 'hiddenSystem 복귀'도 같은 묶음의 미확정 항목이다.)
            if (isBranchResponse && safeEventContext != null && !safeEventContext.isBlank()) {
                PromptInjectionGuard.InjectionCheckResult evtInj =
                    injectionGuard.checkChatMessage(safeEventContext, jpa.username());
                if (evtInj.detected()) {
                    log.warn("⚠️ [INJECTION] eventContext 감지 | severity={} | roomId={} | user={}",
                        evtInj.severity(), roomId, jpa.username());
                    moderationEventService.recordInjection(
                        jpa.userId(), jpa.username(), roomId, "EVENT",
                        evtInj.severity().name(), evtInj.matchedPattern(), safeEventContext);
                }
            }

            // ── MongoDB: 숨겨진 시스템 메시지 저장 (LLM 컨텍스트용) ──
            String systemMessage;
            if (isAway) {
                systemMessage = "[SYSTEM_DIRECTOR] 유저가 자리를 비웠습니다. 캐릭터는 혼자(또는 NPC와) 행동합니다.";
            } else if (isBranchResponse && safeEventContext != null) {
                systemMessage = "[NARRATION] " + safeEventContext;
            } else {
                systemMessage = "[SYSTEM_DIRECTOR] 상황이 발생했습니다. 캐릭터는 자연스럽게 반응합니다.";
            }

            String savedLogId;
            try {
                // [Bug Fix A] BRANCH 나레이션은 visible로 저장 (새로고침 시 히스토리에 표시)
                // AWAY/INTERLUDE/TRANSITION은 hidden (LLM 컨텍스트 전용)
                ChatLogDocument savedLog;
                if (isBranchResponse && safeEventContext != null) {
                    savedLog = chatLogRepository.save(
                        ChatLogDocument.system(roomId, safeEventContext));
                } else {
                    savedLog = chatLogRepository.save(
                        ChatLogDocument.hiddenSystem(roomId, systemMessage));
                }
                savedLogId = savedLog.getId();
            } catch (Exception e) {
                compensateEnergy(jpa.userId(), jpa.energy(), jpa.username());
                sendSseError(emitter, "INTERNAL_ERROR", "메시지 저장 실패");
                return;
            }

            // [D-2.b] 로그 id 확정 → 보상 컨텍스트 갱신 (에너지분은 차감 직후 이미 세워 뒀다).
            rollbackCtx = new RollbackContext(
                jpa.userId(), jpa.username(), jpa.energy(), savedLogId);

            boolean effectiveSecretMode = resolveSecretMode(jpa.room());

            // ── LLM 스트림 ──
            ParsedLlmResult parsed = streamLlmAndParse(jpa.room(), jpa.logCount() + 1,
                effectiveSecretMode, emitter, rollbackCtx);
            if (parsed == null) return;

            // ── TX-2: 상태 업데이트 ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    freshRoom.updateLastActive(parsed.mainEmotion());

                    if (isAway) {
                        // AWAY: 스탯 동결, 이벤트 상태 유지
                        freshRoom.updateEventStatus(
                            parsed.eventStatus() != null ? parsed.eventStatus() : "ONGOING");
                    } else {
                        // INTERLUDE/TRANSITION: 일반 스탯 적용 + constraint 클리어
                        applyStatChanges(freshRoom, parsed.statChanges(), effectiveSecretMode);
                        freshRoom.clearDirectorInterlude();
                    }

                    if (jpa.room().isStoryMode()) {
                        freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
                            parsed.lastOutfit(), parsed.lastTime());
                    }

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, parsed.sceneResponses(),
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        null, null, null,
                        statsSnapshot,
                        freshRoom.getDynamicRelationTag(), null,
                        false, null,
                        isAway ? false : parsed.topicConcluded(),
                        isAway ? freshRoom.getEventStatus() : null);
                });
            } catch (Exception e) {
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "자동 응답 처리 실패");
                return;
            }
            committed = true;   // [D-2.g] 이후 구간은 보상 면제

            // [docs/19 §F D-8] 가격표 소비는 TX-2 커밋 이후다.
            //   resolveBranchCost 안에서 evict하면 Redis가 DB 롤백을 안 따라가므로,
            //   에너지 부족·스트림 실패로 보상 롤백이 돌 때 가격표만 사라져
            //   재시도 시 4E 카드가 1E가 된다.
            if (isBranchResponse) {
                directorService.consumeBranchPricing(roomId);
            }

            // ── [Phase 6-Illust hotfix] 장소 전환 처리 — canonical_key 기반 ──
            LocationTransition locationTransition = null;
            if (parsed.newLocationName() != null && !parsed.newLocationName().isBlank()) {
                if (jpa.room().getCurrentDynamicLocationName() == null
                    || !isSameDynamicLocation(jpa.room(), parsed)) {
                    String timeOfDay = resolveBgTimeOfDay(jpa.room(), parsed.lastTime()); // [E-4.16b]
                    final String canonicalKey = parsedCanonicalKey(parsed);
                    final World world = resolveWorldOrNull(jpa.room());
                    // [블록 B 리뷰픽스 P1] 배경 트랙도 게이트 경유 — raw 플래그는 자격 소실
                    //   (페르소나 나이 하향·패스 만료) 후에도 NSFW 트랙에 태운다. 매턴 재판정.
                    final boolean secretMode = resolveSecretMode(jpa.room());

                    BackgroundGenerationService.BackgroundResult bgResult =
                        backgroundGenerationService.resolveBackground(
                            parsed.newLocationName(), canonicalKey, parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId());

                    if (bgResult.cacheHit()) {
                        locationTransition = LocationTransition.cached(parsed.newLocationName(), bgResult.imageUrl());
                    } else {
                        locationTransition = LocationTransition.generating(parsed.newLocationName(), bgResult.cacheHash());
                        backgroundGenerationService.generateBackgroundAsync(
                            parsed.newLocationName(), canonicalKey, parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId(), world, secretMode);
                    }

                    // 영속화: 다음 턴 가드 정확도를 위해 canonical_key까지 저장
                    final String bgUrlToStore = bgResult.cacheHit() ? bgResult.imageUrl() : null;
                    final String locationNameToStore = parsed.newLocationName();
                    final String canonicalKeyToStore = canonicalKey;
                    try {
                        txTemplate.execute(status -> {
                            ChatRoom bgRoom = chatRoomRepository.findById(roomId).orElse(null);
                            if (bgRoom != null) {
                                if (bgUrlToStore != null) {
                                    bgRoom.updateDynamicBackground(
                                        locationNameToStore, canonicalKeyToStore, bgUrlToStore);
                                } else {
                                    bgRoom.updateDynamicLocationName(
                                        locationNameToStore, canonicalKeyToStore);
                                }
                            }
                            return null;
                        });
                    } catch (Exception e) {
                        log.warn("⚠️ [BG] Dynamic background persistence failed (non-blocking): {}", e.getMessage());
                    }
                } else {
                    log.info("🛡️ [BG-GUARD] Director-auto same dynamic location (canonical_key) — transition suppressed | curKey={} | roomId={}",
                        jpa.room().getCurrentDynamicCanonicalKey(), roomId);
                }
            }
            // [Phase 6-Illust] illustration_scene_hint 영속화
            applyParsedToRoom(roomId, parsed);

            String assistantLogId = saveAssistantLog(roomId, jpa.room().getChatMode(), parsed);
            cacheService.evictRoomInfo(roomId);

            sendFinalResult(emitter, response, false, assistantLogId, false, locationTransition);
            emitter.complete();

            log.info("🎬 [DIRECTOR-AUTO-RESPOND] DONE | type={} | roomId={}", directiveType, roomId);

        } catch (Exception e) {
            // [D-2.g] ★ TX-2 커밋 전 예외는 보상한다 — BRANCH는 최대 4E다.
            if (!committed && rollbackCtx != null) {
                log.warn("↩️ [COMPENSATE] 자동응답 TX-2 커밋 전 예외 — 차감 되돌림 | type={} | roomId={}",
                    directiveType, roomId);
                compensateFullRollback(rollbackCtx);
            }
            log.error("❌ Director auto-respond error | type={} | roomId={} | committed={}",
                directiveType, roomId, committed, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "자동 응답 처리 중 오류 발생");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 6-Illust] 동적 장소 헬퍼 — canonical_key 기반 + World 컨텍스트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [Phase 6-Illust] character.worldId로 World 조회. null이면 null 반환.
     * BackgroundGenerationService에 mood prefix용으로 전달.
     */
    private World resolveWorldOrNull(ChatRoom room) {
        if (room == null || room.getCharacter() == null) return null;
        var worldId = room.getCharacter().getWorldId();
        if (worldId == null) return null;
        return worldRepository.findById(worldId).orElse(null);
    }

    /**
     * [Phase 6-Illust] parsed에서 canonical_key 추출 (없으면 null).
     * AiJsonOutput.locationCanonicalKey → BackgroundCache가 폴백으로 newLocationName 직해싱.
     */
    private String parsedCanonicalKey(ParsedLlmResult parsed) {
        if (parsed == null) return null;
        AiJsonOutput ai = parsed.aiOutput();
        return (ai != null && ai.hasCanonicalKey()) ? ai.locationCanonicalKey() : null;
    }

    /**
     * [Phase 6-Illust hotfix] 동적 장소 동일성 판정.
     *
     * <p>canonical_key가 양쪽 다 있으면 그것으로 정확 비교(캐시 동일성 기준과 일치).
     * 하나라도 없으면 기존 표시명 유사도(isSameLocation)로 폴백.
     *
     * <p>도입 이유: 한글 표시명은 "어둡고 축축한 뒷골목" / "가로등 깜빡이는 밤골목"처럼
     * 같은 장소라도 매번 바뀌어 표시명 유사도가 0.45 미만으로 떨어진다. 그래서
     * 가드를 통과 → 백엔드는 캐시 히트로 새 일러스트는 안 만들지만 LocationTransition을
     * 응답에 실어 보냄 → 프론트가 매번 전환 컴포넌트를 띄움. canonical_key 기준으로
     * 비교하면 의미가 같은 장소를 정확히 인식하여 전환 응답 자체를 생략할 수 있다.
     */
    /**
     * [E-4.16b] 배경 캐시 해시의 <b>timeOfDay 축</b>을 '방에 실제로 남는 값'과 일치시킨다.
     *
     * <p>종전에는 세 배경 블록이 모두 {@code parsed.lastTime() != null ? parsed.lastTime() : "DAY"}로
     * 해시했다. 그런데 {@link ChatRoom#updateSceneState}는 timeOfDay가 <b>null이거나 enum에 없는 값이면
     * 방을 갱신하지 않는다</b>(:645 try/catch). 신규 방 기본값은 {@code NIGHT}(:349)다.
     * 즉 LLM이 {@code time}을 생략한 턴에는 <b>캐시 행이 DAY로 구워지고 방에는 NIGHT가 남아</b>,
     * {@code ChatService}의 백필 조회({@code room.getCurrentTimeOfDay()} 기준)가 영구히 빗나갔다.
     * E-4.16이 canonicalKey 축을 맞췄어도 이 축이 어긋나면 '새로고침 후 배경 미표시'는 그대로다.
     *
     * <p>수용 규칙을 {@code updateSceneState}와 <b>정확히 같게</b> 맞춘다 — 거기서 받아들여지지 않는
     * 값(대소문자 불일치 포함)은 방에 남지 않으므로 캐시 키에도 쓰면 안 된다. 규칙이 갈리면
     * 종류만 바뀐 같은 버그가 된다.
     *
     * <p>{@code room}은 배경 블록이 쓰는 {@code jpa.room()}(TX 이전 스냅샷)이다. LLM이 값을 안 줬거나
     * 잘못 준 경우 방은 갱신되지 않으므로, 이 스냅샷의 값이 곧 <b>영속될 값</b>과 같다.
     *
     * <p>V2(ChatStreamServiceV2)는 {@code mapDayPartToTimeOfDay(room.getCurrentDayPart())}로 방에서
     * 파생하므로 애초에 이 어긋남이 없다 — V1 전용 결함이다.
     */
    private static String resolveBgTimeOfDay(ChatRoom room, String parsedTime) {
        if (parsedTime != null) {
            try {
                return com.spring.aichat.domain.enums.TimeOfDay.valueOf(parsedTime).name();
            } catch (IllegalArgumentException ignored) {
                // updateSceneState도 이 값을 버린다 → 방의 현재 값이 그대로 남는다
            }
        }
        return room.getCurrentTimeOfDay() != null ? room.getCurrentTimeOfDay().name() : "DAY";
    }

    private boolean isSameDynamicLocation(ChatRoom room, ParsedLlmResult parsed) {
        String curKey = room.getCurrentDynamicCanonicalKey();
        String inKey  = parsedCanonicalKey(parsed);
        if (curKey != null && !curKey.isBlank() && inKey != null && !inKey.isBlank()) {
            return curKey.equals(inKey);
        }
        // 폴백: 표시명 유사도
        String curName = room.getCurrentDynamicLocationName();
        return curName != null && parsed != null
            && isSameLocation(curName, parsed.newLocationName());
    }

    /**
     * [Phase 6-Illust] 매 LLM 응답 직후 호출. illustration_scene_hint를 ChatRoom에 영속화.
     * Manual/Auto 일러스트 트리거 모두 이 hint를 단일 source로 사용.
     */
    private void applyParsedToRoom(Long roomId, ParsedLlmResult parsed) {
        if (parsed == null || parsed.aiOutput() == null) return;
        AiJsonOutput ai = parsed.aiOutput();
        if (!ai.hasIllustrationSceneHint()) return;
        try {
            txTemplate.execute(status -> {
                chatRoomRepository.findById(roomId).ifPresent(r ->
                    r.updateLastIllustrationHint(ai.illustrationSceneHint())
                );
                return null;
            });
        } catch (Exception e) {
            log.warn("[ILLUST-HINT] persistence failed (non-blocking): {}", e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Bug Fix] 동적 장소 반복 전환 방지 — 유사도 가드 (canonical_key 폴백용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private boolean isSameLocation(String existing, String incoming) {
        if (existing == null || incoming == null) return false;
        String a = normalizeLocationName(existing);
        String b = normalizeLocationName(incoming);
        if (a.equals(b)) return true;
        if (a.contains(b) || b.contains(a)) return true;
        String coreA = extractCoreNoun(a);
        String coreB = extractCoreNoun(b);
        if (!coreA.isEmpty() && !coreB.isEmpty() && coreA.equals(coreB)) return true;
        return calculateBigramSimilarity(a, b) > 0.45;
    }

    private String normalizeLocationName(String name) {
        return name.replaceAll("[\\s·,.!?~'\"()（）]", "")
            .replace("의", "").replace("한", "").replace("인", "")
            .toLowerCase();
    }

    private String extractCoreNoun(String normalized) {
        String[] placeNouns = {
            "카페", "공원", "해변", "바다", "도서관", "학교", "교실", "옥상",
            "놀이공원", "영화관", "식당", "레스토랑", "편의점", "거리", "골목",
            "방", "침실", "거실", "부엌", "정원", "발코니", "테라스", "지하실",
            "사무실", "병원", "역", "미술관", "박물관", "체육관", "수영장",
            "온천", "신사", "절", "숲", "산", "강", "호수", "다리",
            "포장마차", "바", "클럽", "노래방", "서점", "꽃집",
            "시장", "백화점", "마트", "운동장", "광장", "동아리실", "강당"
        };
        for (String noun : placeNouns) {
            if (normalized.contains(noun)) return noun;
        }
        return normalized;
    }

    private double calculateBigramSimilarity(String a, String b) {
        if (a.length() < 2 || b.length() < 2) return 0.0;
        Set<String> bigramsA = new HashSet<>();
        Set<String> bigramsB = new HashSet<>();
        for (int i = 0; i < a.length() - 1; i++) bigramsA.add(a.substring(i, i + 2));
        for (int i = 0; i < b.length() - 1; i++) bigramsB.add(b.substring(i, i + 2));
        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);
        Set<String> union = new HashSet<>(bigramsA);
        union.addAll(bigramsB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
}