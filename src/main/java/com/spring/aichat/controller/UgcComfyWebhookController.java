package com.spring.aichat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.external.UgcComfyClient;
import com.spring.aichat.service.ugc.UgcPipelineWorker;
import com.spring.aichat.service.ugc.UgcStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [UGC v1] RunPod Serverless 완료 webhook 수신.
 *
 * <p>URL은 제출 시 워커가 문맥을 실어 발급: {@code /api/v1/webhook/ugc-comfy?job={id}&stage=..&tag=..&secret=..}
 * — RunPod job id → 잡 역조회가 필요 없다(문맥이 URL에 있음). 페이로드는 /status 응답과 동일
 * 스키마이므로 {@link UgcComfyClient#parseStatusPayload} 재사용.
 *
 * <p>설계 (IllustrationWebhookController 패턴):
 * <ul>
 *   <li>인증: SecurityConfig {@code /api/v1/webhook/**} permitAll + 쿼리 secret 단순 매칭(미설정 시 skip)</li>
 *   <li>항상 200 — 내부 예외는 로깅만 (RunPod 재시도 유도 안 함, 폴링 폴백이 유실 커버)</li>
 *   <li>멱등: 워커의 잡 락 TX + 상태 가드가 중복 수신을 무해화</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class UgcComfyWebhookController {

    private final UgcPipelineProperties props;
    private final UgcPipelineWorker worker;
    private final UgcComfyClient comfyClient;
    private final ObjectMapper objectMapper;

    @PostMapping("/ugc-comfy")
    public ResponseEntity<Void> onComfyWebhook(
        @RequestParam("job") Long jobId,
        @RequestParam("stage") String stageRaw,
        @RequestParam(value = "tag", required = false) String tagRaw,
        @RequestParam(value = "secret", required = false) String secret,
        @RequestBody String rawBody
    ) {
        String expected = props.runpod().webhookSecret();
        if (expected != null && !expected.isBlank() && !expected.equals(secret)) {
            log.warn("[UGC-WEBHOOK] secret 불일치 — 무시: jobId={}", jobId);
            return ResponseEntity.ok().build(); // 정보 노출 없이 무시
        }

        try {
            UgcStage stage = UgcStage.valueOf(stageRaw);
            String token = (tagRaw == null || tagRaw.isBlank()) ? null : tagRaw;

            JsonNode payload = objectMapper.readTree(rawBody);
            UgcComfyClient.JobStatus status = comfyClient.parseStatusPayload(null, payload);
            log.info("[UGC-WEBHOOK] 수신: jobId={}, stage={}, token={}, status={}",
                jobId, stage, token, status.status());

            worker.onComfyEvent(jobId, stage, token, status);
        } catch (Exception e) {
            // 항상 200 — 실패는 폴링 폴백이 재수습
            log.error("[UGC-WEBHOOK] 처리 실패: jobId={}, stage={}, tag={} — {}",
                jobId, stageRaw, tagRaw, e.getMessage(), e);
        }
        return ResponseEntity.ok().build();
    }
}
