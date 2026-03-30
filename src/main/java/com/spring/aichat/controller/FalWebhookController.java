package com.spring.aichat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.spring.aichat.service.illustration.BackgroundGenerationService;
import com.spring.aichat.service.illustration.IllustrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * [Phase 5.5-Illust] Fal.ai 웹훅 수신 컨트롤러
 *
 * Fal.ai가 이미지 생성 완료 시 POST로 결과를 전송하는 엔드포인트.
 * 인증 불필요 (Fal.ai 서버→서버 호출)
 *
 * 요청 URL: POST /api/v1/webhook/fal
 * Fal.ai 큐 제출 시 쿼리 파라미터로 지정:
 *   ?fal_webhook=https://api.lucidchat.com/api/v1/webhook/fal
 *
 * Fal.ai 웹훅 페이로드 구조:
 * {
 *   "request_id": "...",
 *   "status": "COMPLETED",
 *   "output": { ... ComfyUI outputs ... }
 * }
 */
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
@Slf4j
public class FalWebhookController {

    private final IllustrationService illustrationService;
    private final BackgroundGenerationService backgroundGenerationService;

    @PostMapping("/fal")
    public ResponseEntity<Void> handleFalWebhook(@RequestBody JsonNode payload) {
        String requestId = payload.path("request_id").asText(null);
        String status = payload.path("status").asText("UNKNOWN");

        log.info("[FAL-WEBHOOK] Received: requestId={}, status={}", requestId, status);

        if (requestId == null || requestId.isBlank()) {
            log.warn("[FAL-WEBHOOK] Missing request_id in payload");
            return ResponseEntity.badRequest().build();
        }

        if (!"COMPLETED".equalsIgnoreCase(status)) {
            log.info("[FAL-WEBHOOK] Non-completed status ignored: {}", status);
            return ResponseEntity.ok().build();
        }

        // 캐릭터 일러스트 웹훅 처리 시도
        try {
            illustrationService.handleWebhookCallback(requestId, payload);
        } catch (Exception e) {
            log.debug("[FAL-WEBHOOK] Not an illustration request, trying background: {}", requestId);
        }

        // 배경 이미지 웹훅 처리 시도
        try {
            backgroundGenerationService.handleWebhookCallback(requestId, payload);
        } catch (Exception e) {
            log.debug("[FAL-WEBHOOK] Not a background request either: {}", requestId);
        }

        return ResponseEntity.ok().build();
    }
}