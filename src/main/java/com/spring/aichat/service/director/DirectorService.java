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

    /**
     * 분기 1회의 에너지 상한. 프롬프트가 지시하는 값은 normal 2 · affection 3 · secret 4이고
     * ({@code energy_cost} 규칙, 이 클래스의 SCENARIO 프롬프트 참조) 그보다 큰 값은 나올 이유가 없다.
     */
    private static final int MAX_BRANCH_ENERGY_COST = 4;

    /**
     * 가격표 TTL. 지시문 자체({@link #DIRECTIVE_TTL_SECONDS} = 10분)보다 길게 잡는다.
     *
     * <p>가격표가 먼저 만료되면 <b>느리게 고민한 정상 유저</b>가 자기가 고른 분기를 못 받는다
     * (아래 F1 픽스로 미검증 분기는 일반 턴으로 강등되기 때문). 반대로 오래 살아도 위험이 없다 —
     * 새 지시문이 나오면 같은 키를 덮어쓰고, 턴이 성공하면 {@link #consumeBranchPricing}이 지운다.
     * 즉 이 TTL을 늘리는 것은 착취면을 넓히지 않고 오탐만 줄인다.
     */
    private static final long BRANCH_PRICE_TTL_SECONDS = 3600;

    private void cacheBranchPricing(Long roomId, DirectorDirective directive) {
        if (directive == null || !directive.checkBranch()
            || directive.branch() == null || directive.branch().options() == null) return;
        // [F1] LLM 출력을 그대로 신뢰하지 않는다 — energy_cost는 모델이 만든 JSON 필드이고
        //   DirectorDirective(:130 int energyCost)에도 범위 검증이 없다. 여기가 그 값이
        //   '서버가 정한 가격'으로 승격되는 경계이므로 이 자리에서 [1, 4]로 조인다.
        //   (0 이하가 캐시되면 무료 분기가 되고, 큰 값이 캐시되면 과다 청구가 된다.)
        List<Integer> costs = directive.branch().options().stream()
            .map(DirectorDirective.BranchOption::energyCost)
            .map(DirectorService::clampBranchCost)
            .toList();
        cacheService.put(BRANCH_PRICE_KEY_PREFIX + roomId, costs,
            BRANCH_PRICE_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static int clampBranchCost(int raw) {
        return Math.max(1, Math.min(MAX_BRANCH_ENERGY_COST, raw));
    }

    /**
     * 선택한 분기의 서버측 비용을 <b>읽기만</b> 한다. 소비(evict)는 {@link #consumeBranchPricing(Long)}이 한다.
     *
     * <p>[docs/19 §F D-8] 예전에는 여기서 곧바로 evict했다. 그런데 이 메서드는 TX-1 안에서 불리고
     * <b>Redis evict는 DB 트랜잭션과 함께 롤백되지 않는다</b> — {@code consumeEnergy}가 에너지 부족으로
     * 던지거나 이후 스트림이 실패해 보상 롤백이 돌면 <b>가격표만 사라진다</b>. 그러면 유저가 충전 후
     * 같은 분기를 다시 골랐을 때 캐시 미스로 4E 카드가 1E가 된다(정상 유저 손해이자 착취면).
     * 그래서 소비 시점을 '턴 전체 성공 후'로 미뤘다.
     *
     * @return 서버가 제시했던 비용([1,4]로 clamp됨). 인덱스 미전송·음수·범위 밖·캐시 만료면
     *         {@link Optional#empty()}.
     *         <p><b>[F1 · 2026-09-09 계약 변경]</b> 빈 값은 더 이상 '비용만 1E로 폴백'이 아니라
     *         <b>'이 분기를 발급한 적이 없다'</b>는 뜻이다. 호출부는 1E를 청구하되
     *         <b>분기 특권(constraint 주입 · 나레이션 가시 저장 · 가격표 소비 · 보상 해제)을 전부 뺀
     *         일반 턴으로 강등</b>한다. 즉 이 메서드의 반환값이 돈과 효과를 동시에 가른다.
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
            // 읽을 때도 조인다 — 배포 시점에 이미 캐시돼 있던 항목(구 TTL 600초)은 clamp를 안 거쳤다.
            int raw = ((Number) costs.get(chosenIndex)).intValue();
            int cost = clampBranchCost(raw);
            if (cost != raw) {
                // 모델이 프롬프트의 2/3/4를 벗어난 값을 냈다는 뜻이다. 관측되지 않으면
                // '가끔 이상한 과금'으로만 보이므로 반드시 남긴다.
                log.warn("🎬 [DIRECTOR] energy_cost 범위 이탈 — clamp 적용 | roomId={} | raw={} → {}",
                    roomId, raw, cost);
            }
            return Optional.of(cost);
        } catch (Exception e) {
            log.warn("🎬 [DIRECTOR] Branch cost resolve failed | roomId={}", roomId, e);
            return Optional.empty();
        }
    }

    /**
     * 가격표를 소비한다. <b>턴 전체가 성공한 뒤</b>에만 호출할 것(docs/19 §F D-8).
     * 실패·롤백 경로에서는 호출하지 않아야 재시도가 같은 가격으로 이뤄진다.
     */
    public void consumeBranchPricing(Long roomId) {
        try {
            cacheService.evict(BRANCH_PRICE_KEY_PREFIX + roomId);
        } catch (Exception e) {
            log.warn("🎬 [DIRECTOR] Branch pricing evict failed (non-fatal) | roomId={}", roomId, e);
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