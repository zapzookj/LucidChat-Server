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

            if (directive.checkPass()) return;
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
     * [v3] 수동 호출 → 항상 BRANCH_SCENARIO (3장 시나리오 카드)
     *
     * 유저가 "다음 씬" 버튼 클릭 시 호출.
     * 디렉터가 맥락을 분석하여 3개의 시나리오를 제시하고,
     * 유저가 원하는 상황을 선택할 수 있도록 한다.
     */
    public DirectorDirective requestManualIntervention(Long roomId) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new RuntimeException("Room not found: " + roomId));

        String recentSummary = buildRecentSummary(roomId, room.getCharacter().getName());
        int turnsSince = getTurnsSinceLastIntervention(roomId,
            chatLogRepository.countByRoomIdAndRole(roomId, ChatRole.USER));

        // 수동 호출은 항상 BRANCH_SCENARIO 강제
        String forcePrompt = """
            The user has MANUALLY requested the next scene.
            You MUST output "decision": "BRANCH" with "branch_mode": "SCENARIO".
            
            Generate 3 distinct event SCENARIOS for the user to choose from.
            Each scenario is a different situation that could happen next.
            
            ## Card Structure (EXACTLY 3 options):
            1. **Normal** (tone: "normal", energy_cost: 2):
               A plausible, everyday event. Slice-of-life, comedy, or mild tension.
            2. **Affection** (tone: "affection", energy_cost: 3):
               A romantic or heartwarming scenario that deepens the relationship.
            3. **Secret** (tone: "secret", energy_cost: 4, is_secret: true):
               A bold, provocative, or intimate scenario. Secret Mode flavor.
            
            Each option's `label` should be a short title (2-5 words).
            Each option's `detail` should describe what happens (1-2 sentences).
            
            ❌ "PASS" is FORBIDDEN.
            ❌ "INTERLUDE" is FORBIDDEN.
            ❌ "TRANSITION" is FORBIDDEN.
            ❌ "AWAY" is FORBIDDEN.
            ❌ "branch_mode": "CHOICE" is FORBIDDEN. Use "SCENARIO" only.
            
            Output valid JSON only.
            """;

        DirectorDirective directive = callDirectorLlm(
            room.getCharacter(), room, room.getUser(),
            recentSummary, turnsSince, room.isTopicConcluded(), forcePrompt);

        log.info("🎬 [DIRECTOR-MANUAL] Decision: {} | branchMode={} | roomId={}",
            directive.decision(),
            directive.branch() != null ? directive.branch().branchMode() : "N/A",
            roomId);

        // BRANCH가 아니면 1회 재시도
        if (!directive.checkBranch()) {
            log.warn("[DIRECTOR-MANUAL] 1st attempt not BRANCH ({}), retrying | roomId={}",
                directive.decision(), roomId);

            directive = callDirectorLlm(
                room.getCharacter(), room, room.getUser(),
                recentSummary, turnsSince, room.isTopicConcluded(),
                forcePrompt + "\n\n⚠️ RETRY: You MUST output BRANCH. No other type is accepted.");

            if (!directive.checkBranch()) {
                log.warn("[DIRECTOR-MANUAL] 2nd attempt also failed ({}) | roomId={}", directive.decision(), roomId);
                return new DirectorDirective(DirectorDirective.DECISION_PASS,
                    "LLM failed to produce BRANCH after retry", null, null, null, null, null);
            }
        }

        // Redis에 캐시
        cacheDirective(roomId, directive);
        updateLastInterventionTurn(roomId,
            chatLogRepository.countByRoomIdAndRole(roomId, ChatRole.USER));

        log.info("🎬 [DIRECTOR-MANUAL] Cached BRANCH_SCENARIO | roomId={}", roomId);
        return directive;
    }

    /** 디렉터 결과 필드 검증 로그 — payload NULL 시 raw JSON 포함 */
    private void logDirectiveDetails(DirectorDirective d, String rawJson) {
        if (d.checkInterlude() && d.interlude() != null) {
            log.info("🎬 [DETAIL] INTERLUDE — narration={} | constraint={}",
                d.interlude().narration() != null ? d.interlude().narration().length() + "chars" : "⚠️ NULL",
                d.interlude().actorConstraint() != null ? "OK" : "⚠️ NULL");
        } else if (d.checkBranch() && d.branch() != null) {
            log.info("🎬 [DETAIL] BRANCH — mode={} | situation={} | options={}",
                d.branch().branchMode(),
                d.branch().situation() != null ? d.branch().situation().length() + "chars" : "null",
                d.branch().options() != null ? d.branch().options().size() + "개" : "⚠️ NULL");
        } else if (d.checkTransition() && d.transition() != null) {
            log.info("🎬 [DETAIL] TRANSITION — narration={} | time={} | location={}",
                d.transition().narration() != null ? d.transition().narration().length() + "chars" : "⚠️ NULL",
                d.transition().newTime(),
                d.transition().newLocationName());
        } else if (d.checkAway() && d.away() != null) {
            log.info("🎬 [DETAIL] AWAY — narration={} | constraint={} | npc={}",
                d.away().narration() != null ? d.away().narration().length() + "chars" : "⚠️ NULL",
                d.away().actorConstraint() != null ? "OK" : "⚠️ NULL",
                d.away().npcHint());
        } else if (!d.checkPass()) {
            log.warn("🎬 [DETAIL] Decision={} but payload is NULL! Raw JSON:\n{}",
                d.decision(), rawJson != null && rawJson.length() > 1000
                    ? rawJson.substring(0, 1000) + "..." : rawJson);
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
                "Parse error: " + e.getMessage(), null, null, null, null, null);
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
        if (directive.checkPass()) return true;
        if (topicConcluded) {
            // topic 종료 → BRANCH, TRANSITION, AWAY 허용
            return directive.checkBranch() || directive.checkTransition() || directive.checkAway();
        } else {
            // topic 진행 중 → INTERLUDE만 허용
            return directive.checkInterlude();
        }
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