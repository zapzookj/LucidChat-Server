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
                backgroundGenerationService.handleModelsLabWebhookCallback(generationId, payload);
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

    private boolean verifySecret(String expected, String header, String query) {
        if (expected == null || expected.isBlank()) return true; // 미설정 시 검증 skip
        String provided = header != null ? header : query;
        return expected.equals(provided);
    }
}