package com.spring.aichat.service.director;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.dto.director.DirectorDirective;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.prompt.DirectorPromptAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * [Phase 5.5-Director] 디렉터 엔진 서비스
 *
 * [v2 Fix] requestManualIntervention() Redis 캐시 누락 수정
 *   - 수동 요청에서도 Redis에 캐시하여 consume 플로우 통일
 *   - callDirectorLlm() 공통 메서드로 자동/수동 LLM 호출 통합
 *   - 디버깅 로그 강화 (나레이션 유무, 필드 검증)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DirectorService {

    private final DirectorPromptAssembler directorPromptAssembler;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final RedisCacheService cacheService;

    private static final String DIRECTIVE_KEY_PREFIX = "director:directive:";
    private static final String LAST_INTERVENTION_KEY_PREFIX = "director:last_turn:";
    private static final long DIRECTIVE_TTL_SECONDS = 600;
    private static final int MIN_INTERVENTION_GAP = 3;
    private static final int RECENT_TURNS_FOR_DIRECTOR = 10;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 비동기 디렉터 판단 (매 턴 후처리에서 호출)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void evaluateAndCache(Long roomId, long currentTurnCount) {
        long start = System.currentTimeMillis();

        try {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId).orElse(null);
            if (room == null) { log.warn("[DIRECTOR] Room not found: {}", roomId); return; }
            if (!ChatModePolicy.supportsDirectorMode(room.getChatMode())) return;
            if (room.isEventActive() || room.isPromotionPending() || room.isPromotionWaitingForTopic()) return;
            if (room.isEndingReached()) return;

            int turnsSince = getTurnsSinceLastIntervention(roomId, currentTurnCount);
            if (turnsSince < MIN_INTERVENTION_GAP) return;
            if (hasDirective(roomId)) return;

            String recentSummary = buildRecentSummary(roomId, room.getCharacter().getName());
            DirectorDirective directive = callDirectorLlm(
                room.getCharacter(), room, room.getUser(),
                recentSummary, turnsSince, room.isTopicConcluded(), null);

            log.info("🎬 [DIRECTOR-AUTO] Decision: {} | beat={} | gap={} | roomId={} | took={}ms",
                directive.decision(), directive.narrativeBeat(),
                turnsSince, roomId, System.currentTimeMillis() - start);

            if (directive.isPass()) return;
            if (!isDecisionAllowed(directive, room.isTopicConcluded())) {
                log.warn("[DIRECTOR-AUTO] Decision {} rejected — topic={} | roomId={}",
                    directive.decision(), room.isTopicConcluded(), roomId);
                return;
            }

            cacheDirective(roomId, directive);
            updateLastInterventionTurn(roomId, currentTurnCount);

        } catch (Exception e) {
            log.error("[DIRECTOR-AUTO] Evaluation failed | roomId={}", roomId, e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. Directive 조회/소비
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public Optional<DirectorDirective> peekDirective(Long roomId) {
        return cacheService.get(DIRECTIVE_KEY_PREFIX + roomId, DirectorDirective.class);
    }

    public Optional<DirectorDirective> consumeDirective(Long roomId) {
        String key = DIRECTIVE_KEY_PREFIX + roomId;
        try {
            Optional<DirectorDirective> directive = cacheService.get(key, DirectorDirective.class);
            if (directive.isPresent()) {
                cacheService.evict(key);
                log.info("🎬 [DIRECTOR] Consumed: {} | roomId={}", directive.get().decision(), roomId);
            } else {
                log.warn("🎬 [DIRECTOR] Consume — nothing in Redis | roomId={}", roomId);
            }
            return directive;
        } catch (Exception e) {
            log.warn("[DIRECTOR] Consume failed | roomId={}", roomId, e);
            return Optional.empty();
        }
    }

    public boolean hasDirective(Long roomId) {
        return cacheService.getString(DIRECTIVE_KEY_PREFIX + roomId).isPresent();
    }

    public void discardDirective(Long roomId) {
        cacheService.evict(DIRECTIVE_KEY_PREFIX + roomId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 유저 수동 디렉터 호출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [v2 Fix] 수동 요청도 Redis에 캐시 → consume 플로우 통일
     *
     * 플로우:
     *   1. LLM 호출 → Directive 생성
     *   2. ★ Redis에 캐시 (자동 요청과 동일)
     *   3. Directive 반환 → 프론트에서 인터루드 표시
     *   4. 프론트: consumeDirectorDirective() → Redis에서 정상 소비
     */
    public DirectorDirective requestManualIntervention(Long roomId) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new RuntimeException("Room not found: " + roomId));

        String recentSummary = buildRecentSummary(roomId, room.getCharacter().getName());
        int turnsSince = getTurnsSinceLastIntervention(roomId,
            chatLogRepository.countByRoomIdAndRole(roomId, ChatRole.USER));
        boolean topicConcluded = room.isTopicConcluded();

        // ── 1차 시도: 일반 프롬프트 ──
        String allowedTypes = topicConcluded
            ? "BRANCH or TRANSITION (INTERLUDE is FORBIDDEN because topic is concluded)"
            : "INTERLUDE (BRANCH and TRANSITION are FORBIDDEN because topic is still ongoing)";

        String forcePrompt = """
            The user has MANUALLY requested your intervention.
            You MUST choose %s — PASS is NOT allowed.
            topic_concluded=%s
            """.formatted(allowedTypes, topicConcluded);

        DirectorDirective directive = callDirectorLlm(
            room.getCharacter(), room, room.getUser(),
            recentSummary, turnsSince, topicConcluded, forcePrompt);

        log.info("🎬 [DIRECTOR-MANUAL] 1st attempt: {} | topic={} | roomId={}",
            directive.decision(), topicConcluded, roomId);

        // ── 가드레일 체크 → 실패 시 1회 재시도 (더 강력한 프롬프트) ──
        if (directive.isPass() || !isDecisionAllowed(directive, topicConcluded)) {
            log.warn("[DIRECTOR-MANUAL] 1st attempt rejected ({}), retrying with strict prompt | roomId={}",
                directive.decision(), roomId);

            String strictPrompt = buildStrictRetryPrompt(topicConcluded, recentSummary);

            directive = callDirectorLlm(
                room.getCharacter(), room, room.getUser(),
                recentSummary, turnsSince, topicConcluded, strictPrompt);

            log.info("🎬 [DIRECTOR-MANUAL] 2nd attempt: {} | roomId={}", directive.decision(), roomId);

            // 2차도 실패하면 포기
            if (directive.isPass() || !isDecisionAllowed(directive, topicConcluded)) {
                log.warn("[DIRECTOR-MANUAL] 2nd attempt also failed ({}) | roomId={}",
                    directive.decision(), roomId);
                return new DirectorDirective(DirectorDirective.DECISION_PASS,
                    "LLM failed to produce valid type after retry", null, null, null, null);
            }
        }

        // ── 성공: Redis에 캐시 ──
        cacheDirective(roomId, directive);
        updateLastInterventionTurn(roomId,
            chatLogRepository.countByRoomIdAndRole(roomId, ChatRole.USER));

        log.info("🎬 [DIRECTOR-MANUAL] Cached | decision={} | roomId={}", directive.decision(), roomId);

        return directive;
    }

    /**
     * 1차 시도 실패 시 사용하는 강제 프롬프트.
     * 허용 타입을 극도로 명확히 지정하고, 금지 타입을 반복 강조.
     */
    private String buildStrictRetryPrompt(boolean topicConcluded, String recentSummary) {
        if (topicConcluded) {
            return """
                ⚠️ STRICT INSTRUCTION — READ VERY CAREFULLY:
                The conversation topic has CONCLUDED. The user wants a NEW scene.
                
                You MUST output EXACTLY ONE of these two types:
                
                1. "decision": "BRANCH" — Give 2-3 choices for what happens next.
                2. "decision": "TRANSITION" — Skip time or change location with a narration.
                
                ❌ "INTERLUDE" is ABSOLUTELY FORBIDDEN. Do NOT output INTERLUDE.
                ❌ "PASS" is ABSOLUTELY FORBIDDEN. Do NOT output PASS.
                
                Choose BRANCH if there's a meaningful choice point.
                Choose TRANSITION if a scene change would be more natural.
                
                Output valid JSON only.
                """;
        } else {
            return """
                ⚠️ STRICT INSTRUCTION — READ VERY CAREFULLY:
                The conversation is STILL ONGOING. The user wants a surprise event.
                
                You MUST output:
                "decision": "INTERLUDE" — A surprise event that interrupts the current conversation.
                
                ❌ "BRANCH" is ABSOLUTELY FORBIDDEN.
                ❌ "TRANSITION" is ABSOLUTELY FORBIDDEN.
                ❌ "PASS" is ABSOLUTELY FORBIDDEN.
                
                Create a surprising, plausible event in the current setting.
                
                Output valid JSON only.
                """;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 LLM 호출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private DirectorDirective callDirectorLlm(Character character, ChatRoom room, User user,
                                              String recentSummary, int turnsSince,
                                              boolean topicConcluded, String additionalPrompt) {
        String systemPrompt = directorPromptAssembler.assembleDirectorPrompt(
            character, room, user, recentSummary, turnsSince, topicConcluded);

        String userPrompt = "Analyze the conversation and decide your intervention. Output JSON only.";
        if (additionalPrompt != null && !additionalPrompt.isBlank()) {
            userPrompt = additionalPrompt + "\n\n" + userPrompt;
        }

        List<OpenAiMessage> messages = List.of(
            OpenAiMessage.system(systemPrompt),
            OpenAiMessage.user(userPrompt)
        );

        String model = props.sentimentModel();
        long llmStart = System.currentTimeMillis();

        String rawJson = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.85)
        ).trim();

        log.info("🎬 [DIRECTOR-LLM] Model={} | took={}ms | rawLen={} | roomId={}",
            model, System.currentTimeMillis() - llmStart, rawJson.length(), room.getId());

        try {
            String cleanJson = extractJson(rawJson);

            DirectorDirective directive = objectMapper.readValue(cleanJson, DirectorDirective.class);
            logDirectiveDetails(directive, cleanJson);
            return directive;

        } catch (Exception e) {
            log.error("[DIRECTOR-LLM] Parse failed | raw(first 500)={}",
                rawJson.length() > 500 ? rawJson.substring(0, 500) : rawJson, e);
            return new DirectorDirective(DirectorDirective.DECISION_PASS,
                "Parse error: " + e.getMessage(), null, null, null, null);
        }
    }

    /** 디렉터 결과 필드 검증 로그 — payload NULL 시 raw JSON 포함 */
    private void logDirectiveDetails(DirectorDirective d, String rawJson) {
        if (d.isInterlude() && d.interlude() != null) {
            log.info("🎬 [DETAIL] INTERLUDE — narration={} | constraint={} | agency={}",
                d.interlude().narration() != null ? d.interlude().narration().length() + "chars" : "⚠️ NULL",
                d.interlude().actorConstraint() != null ? "OK" : "⚠️ NULL",
                d.interlude().userAgency() != null ? d.interlude().userAgency() : "⚠️ NULL");
        } else if (d.isBranch() && d.branch() != null) {
            log.info("🎬 [DETAIL] BRANCH — situation={} | options={}",
                d.branch().situation() != null ? d.branch().situation().length() + "chars" : "⚠️ NULL",
                d.branch().options() != null ? d.branch().options().size() + "개" : "⚠️ NULL");
        } else if (d.isTransition() && d.transition() != null) {
            log.info("🎬 [DETAIL] TRANSITION — narration={} | time={} | location={}",
                d.transition().narration() != null ? d.transition().narration().length() + "chars" : "⚠️ NULL",
                d.transition().newTime(),
                d.transition().newLocationName());
        } else if (!d.isPass()) {
            // ★ payload가 NULL인 경우 raw JSON 출력하여 LLM 출력 구조 확인
            log.warn("🎬 [DETAIL] Decision={} but payload is NULL! Raw JSON:\n{}",
                d.decision(), rawJson != null && rawJson.length() > 1000
                    ? rawJson.substring(0, 1000) + "..." : rawJson);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void cacheDirective(Long roomId, DirectorDirective directive) {
        cacheService.put(DIRECTIVE_KEY_PREFIX + roomId, directive,
            DIRECTIVE_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    private int getTurnsSinceLastIntervention(Long roomId, long currentTurnCount) {
        Optional<String> last = cacheService.getString(LAST_INTERVENTION_KEY_PREFIX + roomId);
        if (last.isEmpty()) return Integer.MAX_VALUE;
        try { return (int) (currentTurnCount - Long.parseLong(last.get())); }
        catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    private void updateLastInterventionTurn(Long roomId, long currentTurnCount) {
        cacheService.putString(LAST_INTERVENTION_KEY_PREFIX + roomId, String.valueOf(currentTurnCount));
    }

    private boolean isDecisionAllowed(DirectorDirective directive, boolean topicConcluded) {
        if (directive.isPass()) return true;
        return topicConcluded
            ? (directive.isBranch() || directive.isTransition())
            : directive.isInterlude();
    }

    private String buildRecentSummary(Long roomId, String characterName) {
        List<ChatLogDocument> recent = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));
        int start = Math.max(0, recent.size() - RECENT_TURNS_FOR_DIRECTOR);

        StringBuilder sb = new StringBuilder();
        for (ChatLogDocument doc : recent.subList(start, recent.size())) {
            String prefix = switch (doc.getRole()) {
                case USER -> "[User]";
                case ASSISTANT -> "[" + characterName + "]";
                case SYSTEM -> "[Narration]";
            };
            String content = doc.getCleanContent() != null ? doc.getCleanContent() : doc.getRawContent();
            if (content != null && !content.isBlank()) {
                sb.append(prefix).append(" ").append(
                    content.length() > 200 ? content.substring(0, 200) + "..." : content
                ).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        text = text.trim();
        if (text.startsWith("```json")) text = text.substring(7);
        if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        text = text.trim();
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first >= 0 && last > first) text = text.substring(first, last + 1);
        return text.trim();
    }
}