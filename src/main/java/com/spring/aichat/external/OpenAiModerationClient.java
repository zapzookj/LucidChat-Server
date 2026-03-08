package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * [Phase 5] OpenAI Moderation API 클라이언트
 *
 * [엔드포인트]
 * POST https://api.openai.com/v1/moderations
 *
 * [비용] 무료 (OpenAI 공식: "free to use for most developers")
 * [지연] 평균 100~300ms
 *
 * [응답 카테고리]
 * - sexual          : 성적 콘텐츠
 * - sexual/minors   : 아동 대상 성적 콘텐츠 (즉시 차단)
 * - hate            : 혐오/차별
 * - hate/threatening: 혐오 + 위협
 * - violence        : 폭력
 * - violence/graphic: 노골적 폭력 묘사
 * - self-harm       : 자해/자살
 * - self-harm/intent: 자해 의도
 * - harassment      : 괴롭힘
 * - harassment/threatening: 위협적 괴롭힘
 *
 * [우리 서비스의 임계값 전략]
 *
 * "법적 문제만 차단, 표현의 자유 최대 보장"
 *
 * OpenAI Moderation은 category_scores(0.0~1.0)를 반환하는데,
 * 기본 flagged는 매우 민감하게 설정되어 있다 (sexual 0.3 정도면 flagged).
 * 우리는 기본 flagged를 쓰지 않고, category_scores에 자체 임계값을 적용한다.
 *
 * ┌──────────────────────┬───────────┬─────────────────────────────┐
 * │ Category             │ Threshold │ 근거                         │
 * ├──────────────────────┼───────────┼─────────────────────────────┤
 * │ sexual               │ 0.85      │ 매우 높게 — 로맨스 서비스 특성  │
 * │ sexual/minors        │ 0.40      │ 낮게 — 아동 보호 무관용         │
 * │ hate                 │ 0.80      │ 심각한 혐오만 차단              │
 * │ hate/threatening     │ 0.65      │ 위협적 혐오는 좀 더 엄격        │
 * │ violence             │ 0.85      │ 높게 — 게임/스토리 표현 허용    │
 * │ violence/graphic     │ 0.75      │ 노골적 묘사는 좀 더 엄격        │
 * │ self-harm            │ 0.70      │ 자해 관련은 중간 수준           │
 * │ self-harm/intent     │ 0.50      │ 의도적 자해는 좀 더 엄격        │
 * │ harassment           │ 0.85      │ 높게 — 캐릭터와의 자유 대화     │
 * │ harassment/threatening│ 0.65     │ 위협적 괴롭힘은 좀 더 엄격      │
 * └──────────────────────┴───────────┴─────────────────────────────┘
 *
 * sexual이 0.85로 매우 높은 이유:
 * - 미연시 서비스 특성상 "키스해줘", "안아줘" 같은 로맨틱 표현이 빈번
 * - OpenAI Moderation은 이런 표현에도 sexual score 0.3~0.6을 매기는 경우가 있음
 * - 0.85 이상이면 진짜 노골적인 성적 묘사만 걸림
 * - 가벼운 로맨스는 Step 1 키워드 필터에서도 통과 + 여기서도 통과 → 정상 대화
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OpenAiModerationClient {

    private final RestTemplate externalApiRestTemplate;
    private final OpenAiProperties openAiProperties;
    private final ObjectMapper objectMapper;

    private static final String MODERATION_URL = "https://api.openai.com/v1/moderations";

    // ── 자체 임계값 (기본 flagged 대신 사용) ──
    private static final double THRESHOLD_SEXUAL         = 0.85;
    private static final double THRESHOLD_SEXUAL_MINORS  = 0.40;  // 아동 보호 무관용
    private static final double THRESHOLD_HATE           = 0.80;
    private static final double THRESHOLD_HATE_THREAT    = 0.65;
    private static final double THRESHOLD_VIOLENCE       = 0.85;
    private static final double THRESHOLD_VIOLENCE_GRAPH = 0.75;
    private static final double THRESHOLD_SELF_HARM      = 0.70;
    private static final double THRESHOLD_SELF_HARM_INT  = 0.50;
    private static final double THRESHOLD_HARASSMENT     = 0.85;
    private static final double THRESHOLD_HARASS_THREAT  = 0.65;

    /**
     * 메시지 유해성 검사
     *
     * @return ModerationResult (통과 or 차단 사유)
     */
    public ModerationResult moderate(String message) {
        try {
            long start = System.currentTimeMillis();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAiProperties.apiKey());

            Map<String, Object> body = Map.of(
                "model", "text-moderation-latest",
                "input", message
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = externalApiRestTemplate.exchange(
                MODERATION_URL, HttpMethod.POST, entity, String.class
            );

            long elapsed = System.currentTimeMillis() - start;
            log.debug("[MODERATION] API call: {}ms", elapsed);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[MODERATION] Non-200 response: status={}", response.getStatusCode());
                return ModerationResult.PASS; // API 오류 시 통과 (가용성 우선)
            }

            return parseResponse(response.getBody(), elapsed);

        } catch (Exception e) {
            log.error("[MODERATION] API call failed — allowing message (failsafe): {}", e.getMessage());
            return ModerationResult.PASS; // 장애 시 통과 (서비스 가용성 > 필터링)
        }
    }

    private ModerationResult parseResponse(String responseBody, long elapsed) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                return ModerationResult.PASS;
            }

            JsonNode first = results.get(0);
            JsonNode scores = first.path("category_scores");

            // ── 자체 임계값 기반 판정 ──

            // 아동 성적 콘텐츠 (최우선 — 무관용)
            double sexualMinors = scores.path("sexual/minors").asDouble(0);
            if (sexualMinors >= THRESHOLD_SEXUAL_MINORS) {
                log.warn("[MODERATION] BLOCKED: sexual/minors={} (threshold={})", sexualMinors, THRESHOLD_SEXUAL_MINORS);
                return new ModerationResult(false, "sexual/minors", sexualMinors,
                    "부적절한 내용이 포함되어 있습니다.", elapsed);
            }

            // 자해 의도
            double selfHarmIntent = scores.path("self-harm/intent").asDouble(0);
            if (selfHarmIntent >= THRESHOLD_SELF_HARM_INT) {
                log.warn("[MODERATION] BLOCKED: self-harm/intent={}", selfHarmIntent);
                return new ModerationResult(false, "self-harm/intent", selfHarmIntent,
                    "해당 주제에 대한 대화는 제한됩니다. 어려운 일이 있다면 전문 상담을 권합니다.", elapsed);
            }

            // 자해
            double selfHarm = scores.path("self-harm").asDouble(0);
            if (selfHarm >= THRESHOLD_SELF_HARM) {
                log.warn("[MODERATION] BLOCKED: self-harm={}", selfHarm);
                return new ModerationResult(false, "self-harm", selfHarm,
                    "해당 주제에 대한 대화는 제한됩니다.", elapsed);
            }

            // 혐오 + 위협
            double hateThreat = scores.path("hate/threatening").asDouble(0);
            if (hateThreat >= THRESHOLD_HATE_THREAT) {
                log.warn("[MODERATION] BLOCKED: hate/threatening={}", hateThreat);
                return new ModerationResult(false, "hate/threatening", hateThreat,
                    "부적절한 표현이 포함되어 있습니다.", elapsed);
            }

            // 혐오
            double hate = scores.path("hate").asDouble(0);
            if (hate >= THRESHOLD_HATE) {
                log.warn("[MODERATION] BLOCKED: hate={}", hate);
                return new ModerationResult(false, "hate", hate,
                    "부적절한 표현이 포함되어 있습니다.", elapsed);
            }

            // 위협적 괴롭힘
            double harassThreat = scores.path("harassment/threatening").asDouble(0);
            if (harassThreat >= THRESHOLD_HARASS_THREAT) {
                log.warn("[MODERATION] BLOCKED: harassment/threatening={}", harassThreat);
                return new ModerationResult(false, "harassment/threatening", harassThreat,
                    "부적절한 표현이 포함되어 있습니다.", elapsed);
            }

            // 노골적 폭력
            double violenceGraphic = scores.path("violence/graphic").asDouble(0);
            if (violenceGraphic >= THRESHOLD_VIOLENCE_GRAPH) {
                log.warn("[MODERATION] BLOCKED: violence/graphic={}", violenceGraphic);
                return new ModerationResult(false, "violence/graphic", violenceGraphic,
                    "과도하게 폭력적인 내용이 포함되어 있습니다.", elapsed);
            }

            // 성적 콘텐츠 (로맨스 서비스 특성상 매우 높은 임계값)
            double sexual = scores.path("sexual").asDouble(0);
            if (sexual >= THRESHOLD_SEXUAL) {
                log.warn("[MODERATION] BLOCKED: sexual={}", sexual);
                return new ModerationResult(false, "sexual", sexual,
                    "해당 표현은 시크릿 모드에서만 사용할 수 있습니다.", elapsed);
            }

            // 폭력
            double violence = scores.path("violence").asDouble(0);
            if (violence >= THRESHOLD_VIOLENCE) {
                log.warn("[MODERATION] BLOCKED: violence={}", violence);
                return new ModerationResult(false, "violence", violence,
                    "과도하게 폭력적인 내용이 포함되어 있습니다.", elapsed);
            }

            // 괴롭힘
            double harassment = scores.path("harassment").asDouble(0);
            if (harassment >= THRESHOLD_HARASSMENT) {
                log.warn("[MODERATION] BLOCKED: harassment={}", harassment);
                return new ModerationResult(false, "harassment", harassment,
                    "부적절한 표현이 포함되어 있습니다.", elapsed);
            }

            // 전체 통과
            return ModerationResult.PASS;

        } catch (Exception e) {
            log.error("[MODERATION] Response parsing failed: {}", e.getMessage());
            return ModerationResult.PASS; // 파싱 실패 시 통과
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Result DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record ModerationResult(
        boolean passed,
        String flaggedCategory,  // 차단 사유 카테고리
        double score,            // 해당 카테고리 점수
        String userMessage,      // 유저에게 보여줄 메시지
        long latencyMs           // API 응답 시간
    ) {
        public static final ModerationResult PASS =
            new ModerationResult(true, null, 0, null, 0);
    }
}