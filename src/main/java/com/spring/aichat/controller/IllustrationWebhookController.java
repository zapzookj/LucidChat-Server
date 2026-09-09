package com.spring.aichat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.spring.aichat.config.ModelsLabProperties;
import com.spring.aichat.service.illustration.BackgroundGenerationService;
import com.spring.aichat.service.illustration.IllustrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * [Phase 6-Illust v3] ModelsLab webhook 컨트롤러.
 *
 * <p>Phase 5.5 시기 RunPod payload를 처리하던 {@code FalWebhookController}를 대체.
 *
 * <p><b>Fal.ai webhook 엔드포인트 제거됨</b>: 배경 트랙은 공식 Java SDK
 * ({@code fal-client-async})의 {@code subscribe()}가 큐 제출·폴링·결과 조회·재시도를
 * 모두 내부 처리하므로 webhook 콜백 인프라가 불필요. BackgroundGenerationService가
 * {@code generateBlocking()}으로 결과를 직접 수신한다.
 *
 * <p>남은 트랙 — ModelsLab은 자체 REST API(SDK 없음)이므로 폴링 + webhook 보조 유지:
 * <ul>
 *   <li><b>{@code POST /api/v1/webhook/modelslab}</b> — 캐릭터 일러스트 (ModelsLab SDXL)
 *       + Secret Mode 분위기 배경 ({@code BG_} prefix trackId로 분기)
 *       <br>Payload: {@code { "id", "status", "output": ["url"], "track_id" } }</li>
 * </ul>
 *
 * <p>인증: SecurityConfig의 {@code /api/v1/webhook/**} permitAll.
 * 검증: 옵션 webhook secret 단순 매칭 (미설정 시 skip).
 */
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
@Slf4j
public class IllustrationWebhookController {

    private final IllustrationService illustrationService;
    private final BackgroundGenerationService backgroundGenerationService;
    private final ModelsLabProperties modelsLabProps;
    // [D-18] prod 판정용 — 미설정 시크릿을 prod에서만 거부한다(결제 웹훅과 같은 정책).
    private final org.springframework.core.env.Environment environment;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  ModelsLab webhook — 캐릭터 일러스트 트랙
    //  + Secret Mode NSFW 분위기 배경 라우팅 트랙 (trackId "BG_" prefix)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping("/modelslab")
    public ResponseEntity<Void> handleModelsLabWebhook(
        @RequestBody JsonNode payload,
        @RequestHeader(value = "X-Modelslab-Secret", required = false) String headerSecret,
        @RequestParam(value = "secret", required = false) String querySecret
    ) {
        String generationId = payload.path("id").asText(null);
        String status = payload.path("status").asText("UNKNOWN");
        String trackId = payload.path("track_id").asText(null);

        log.info("[MODELSLAB-WEBHOOK] Received: id={}, status={}, trackId={}",
            generationId, status, trackId);

        if (!verifySecret(modelsLabProps.webhookSecret(), headerSecret, querySecret)) {
            log.warn("[MODELSLAB-WEBHOOK] Invalid secret — rejecting");
            return ResponseEntity.status(401).build();
        }

        if (generationId == null || generationId.isBlank()) {
            log.warn("[MODELSLAB-WEBHOOK] Missing id");
            return ResponseEntity.badRequest().build();
        }

        if (!"success".equalsIgnoreCase(status)) {
            log.info("[MODELSLAB-WEBHOOK] Non-success status ignored: {}", status);
            return ResponseEntity.ok().build();
        }

        // trackId가 "BG_..." prefix면 Secret Mode 배경, 아니면 캐릭터 일러스트
        try {
            if (trackId != null && trackId.startsWith("BG_")) {
                // [E-4.15] 배경 트랙 웹훅은 **의도적으로 무시**한다.
                //   완결은 pollModelsLabUntilComplete가 책임지고, 캐시 행은 성공 후에만 만들어지므로
                //   웹훅이 조회할 앵커가 애초에 없었다(핸들러는 제거됨 — 그쪽 주석 참조).
                //   분기 자체는 남긴다 — 없애면 배경 웹훅이 캐릭터 일러 핸들러로 잘못 흘러간다.
                log.info("[MODELSLAB-WEBHOOK] 배경 트랙 콜백 무시(폴링이 완결 담당): trackId={}", trackId);
            } else {
                illustrationService.handleModelsLabWebhookCallback(generationId, payload);
            }
        } catch (Exception e) {
            log.warn("[MODELSLAB-WEBHOOK] Handler error: trackId={}, err={}", trackId, e.getMessage());
        }

        return ResponseEntity.ok().build();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  secret 검증 (단순 매칭, 운영 단계엔 HMAC 강화 가능)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [D-18] 웹훅 시크릿 검증. <b>prod에서는 미설정 시 fail-closed</b>.
     *
     * <p>종전에는 {@code expected}가 비어 있으면 무조건 true였다. 그런데 이 엔드포인트는
     * <b>무인증 개방</b>이다 — {@code SecurityConfig}가 {@code "/api/v1/webhook/**"}를 permitAll로
     * 열어 둔다(웹훅은 외부 서버가 부르므로 JWT를 가질 수 없다). 즉 시크릿 미설정은
     * '검증 생략'이 아니라 <b>아무나 잡 완료를 위조해 밀어 넣을 수 있는 표면</b>이었다.
     *
     * <p><b>실측(2026-09-09)</b>: 프로드 컨테이너에 {@code MODELSLAB_WEBHOOK_SECRET=}이
     * <b>빈 값으로</b> 주입돼 있다(같은 컨테이너의 {@code UGC_RUNPOD_WEBHOOK_SECRET}은 실값).
     * 즉 이 축은 지금 이 순간 fail-open이다.
     *
     * <p><b>fail-closed로 돌려도 잃는 기능이 없다</b> — 선행 조건이었던
     * '배경이 폴링으로 완결되는가'는 이미 해소됐다. {@code BackgroundGenerationService}의
     * [E-4.15] 주석 원문: <i>"ModelsLab 배경 웹훅 핸들러를 <b>제거했다</b> — 구조적으로 사문이었다.
     * … 성공 케이스는 폴링(pollModelsLabUntilComplete)이 이미 완결해 멱등 가드가 즉시 return했다."</i>
     * 캐릭터 CG 축은 G-2(영구 동결)로 노브가 닫혀 있다.
     *
     * <p>정책은 결제 웹훅({@code PaymentController.verifyWebhookSecret})과 같게 맞춘다 —
     * prod는 거부, 비prod는 경고 후 통과(로컬 개발이 막히지 않도록).
     *
     * <p>⚠ 값을 주입할 때 {@code ?secret=}로 URL에 붙이지 말 것 — 쿼리스트링은 접근 로그에 남는다.
     * 헤더({@code X-Modelslab-Secret})를 쓴다. 쿼리 파라미터 수신은 기존 호환을 위해 남겨 둔다.
     */
    private boolean verifySecret(String expected, String header, String query) {
        if (expected == null || expected.isBlank()) {
            if (isProdProfile()) {
                log.error("[WEBHOOK] modelslab.webhook-secret NOT CONFIGURED in prod — fail-closed. "
                    + "MODELSLAB_WEBHOOK_SECRET 환경변수를 주입할 것(현재 빈 값). "
                    + "이 엔드포인트는 permitAll이라 미설정은 곧 무인증 개방이다.");
                return false;
            }
            log.warn("[WEBHOOK] modelslab.webhook-secret not configured — verification skipped (non-prod)");
            return true;
        }
        String provided = (header != null && !header.isBlank()) ? header : query;
        if (provided == null || provided.isBlank()) return false;
        return expected.equals(provided);
    }

    private boolean isProdProfile() {
        return java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }
}