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


    private final com.spring.aichat.config.LegacyFeatureProperties legacy;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final RedisCacheService cacheService;

    private static final String DIRECTIVE_KEY_PREFIX = "director:directive:";
    private static final String LAST_INTERVENTION_KEY_PREFIX = "director:last_turn:";
    private static final long DIRECTIVE_TTL_SECONDS = 600;
    private static final int RECENT_TURNS_FOR_DIRECTOR = 10;

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
            character, room, user, recentSummary, turnsSince, topicConcluded,
            legacy.getUnlock().isRelationGated());

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
        cacheBranchPricing(roomId, directive);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [블록 D · §G-13 복구 + docs/13 P0] BRANCH 서버 권위 과금
    //
    //  2026-02 `6d3ed07`부터 이벤트 선택 비용을 클라이언트가 요청 바디로 보내왔다.
    //  현재 백엔드는 그 값을 무시하고 cost=1로 고정하고 있어(과소 청구) FE 표기와도 어긋난다.
    //  옵션 원본은 consumeDirective가 evict하므로 선택 시점에는 남아 있지 않다 →
    //  **가격표만 별도 키로 따로 보관**해 두고 chosenIndex로 재판정한다.
    //  (기존 consume 의미를 건드리지 않는 것이 이 설계의 요점이다.)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final String BRANCH_PRICE_KEY_PREFIX = "director:branchprice:";

    private void cacheBranchPricing(Long roomId, DirectorDirective directive) {
        if (directive == null || !directive.checkBranch()
            || directive.branch() == null || directive.branch().options() == null) return;
        List<Integer> costs = directive.branch().options().stream()
            .map(DirectorDirective.BranchOption::energyCost)
            .toList();
        cacheService.put(BRANCH_PRICE_KEY_PREFIX + roomId, costs,
            DIRECTIVE_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 선택한 분기의 서버측 비용을 반환하고 가격표를 소비(evict)한다.
     *
     * @return 서버가 제시했던 비용. 인덱스 범위 밖·캐시 만료·미BRANCH면 {@link Optional#empty()}
     *         → 호출부는 레거시 기본값으로 폴백한다(관용 롤아웃).
     */
    public Optional<Integer> resolveBranchCost(Long roomId, Integer chosenIndex) {
        if (chosenIndex == null || chosenIndex < 0) return Optional.empty();
        String key = BRANCH_PRICE_KEY_PREFIX + roomId;
        try {
            Optional<List> cached = cacheService.get(key, List.class);
            if (cached.isEmpty()) {
                log.warn("🎬 [DIRECTOR] Branch pricing expired — falling back | roomId={}", roomId);
                return Optional.empty();
            }
            List<?> costs = cached.get();
            if (chosenIndex >= costs.size()) {
                log.warn("🎬 [DIRECTOR] chosenIndex out of range: {} / {} | roomId={}",
                    chosenIndex, costs.size(), roomId);
                return Optional.empty();
            }
            cacheService.evict(key);
            return Optional.of(((Number) costs.get(chosenIndex)).intValue());
        } catch (Exception e) {
            log.warn("🎬 [DIRECTOR] Branch cost resolve failed | roomId={}", roomId, e);
            return Optional.empty();
        }
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