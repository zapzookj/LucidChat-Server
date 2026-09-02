package com.spring.aichat.controller;

import com.spring.aichat.dto.payment.ConfirmPaymentRequest;
import com.spring.aichat.dto.payment.PaymentResultResponse;
import com.spring.aichat.dto.payment.PrepareOrderRequest;
import com.spring.aichat.dto.payment.PrepareOrderResponse;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.payment.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

/**
 * 결제 API
 *
 * [Phase 5 개선]
 *
 * 1. POST /api/v1/payments/ready    -> 사전 주문 생성
 * 2. POST /api/v1/payments/confirm  -> 클라이언트 사후 검증
 * 3. POST /api/v1/payments/webhook  -> PortOne 웹훅 수신 (NEW)
 *
 * [웹훅 설계]
 * - PortOne 서버가 결제 완료 시 직접 호출
 * - JWT 인증 불필요 (SecurityConfig에서 permitAll)
 * - [적대적 리뷰 P1] 응답 코드로 재시도를 제어한다: 확정 실패는 200, 복구 가능한 실패는 503.
 *   (PortOne V1은 비200일 때만 최대 5회 재시도한다 — 무조건 200은 재시도 포기와 같다.)
 * - 멱등성: /confirm과 동시에 도착해도 중복 지급 없음
 *
 * [결제 누락 방지 시나리오]
 * 유저가 결제 완료 직후 브라우저를 닫은 경우:
 *   Client /confirm -> 호출 안 됨 (브라우저 종료)
 *   PortOne /webhook -> 서버로 직접 통보 -> 검증 + 에너지 지급
 *   -> 유저는 다음 접속 시 에너지가 정상 충전되어 있음
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final ApiRateLimiter rateLimiter;
    /** [버그픽스 B-1.3] prod 프로필 판정용 — 시크릿 미설정 시 fail-closed 여부를 가른다. */
    private final Environment environment;

    /**
     * [버그픽스 B-1.3 · docs/17_assets/defect_register.md §B-1.3 · docs/19 D-17]
     * PortOne 웹훅 공유 시크릿. 미설정(빈 값)이면 로컬·개발에서는 검증을 건너뛰지만
     * **prod 프로필에서는 fail-closed**(모든 웹훅 거부)다.
     *
     * <p>PortOne 콘솔 등록 URL에 쿼리로 붙이는 것을 기준 운용으로 한다(V1은 서명을 제공하지 않는다):
     * {@code https://api.lucid-chat.com/api/v1/payments/webhook?secret=<PORTONE_WEBHOOK_SECRET>}
     * 헤더 {@code X-PortOne-Secret}도 함께 받는다(프록시 교체 시 이관 여지).
     */
    @Value("${portone.webhook-secret:}")
    private String webhookSecret;

    /**
     * 사전 주문 생성
     */
    @PostMapping("/ready")
    public ResponseEntity<PrepareOrderResponse> prepareOrder(
        @RequestBody @Valid PrepareOrderRequest request,
        Authentication authentication
    ) {
        if (rateLimiter.checkPayment(authentication.getName())) {
            throw new RateLimitException("결제 요청이 너무 빠릅니다.", 5);
        }
        PrepareOrderResponse response = paymentService.prepareOrder(
            authentication.getName(), request);
        return ResponseEntity.ok(response);
    }

    /**
     * 클라이언트 사후 검증
     * - 소유권 검증 수행
     * - 비관적 락으로 동시성 방어
     */
    @PostMapping("/confirm")
    public ResponseEntity<PaymentResultResponse> confirmPayment(
        @RequestBody @Valid ConfirmPaymentRequest request,
        Authentication authentication
    ) {
        if (rateLimiter.checkPayment(authentication.getName())) {
            throw new RateLimitException("결제 검증 요청이 너무 빠릅니다.", 5);
        }
        PaymentResultResponse response = paymentService.confirmPayment(
            authentication.getName(), request);
        return ResponseEntity.ok(response);
    }

    /**
     * PortOne 웹훅 수신
     *
     * [PortOne 웹훅 페이로드]
     * {
     *   "imp_uid": "imp_xxxx",
     *   "merchant_uid": "lucid_xxxx",
     *   "status": "paid"
     * }
     *
     * [중요]
     * - JWT 인증 없음 (SecurityConfig에서 permitAll 설정 필요)
     * - [버그픽스 B-1.3] 대신 공유 시크릿을 검증한다. 검증 실패도 200이되 status:"rejected".
     * - [적대적 리뷰 P1] 응답 코드가 곧 **재시도 계약**이다 — 아래 handleWebhook 본문 주석 참조.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, String>> handleWebhook(
        @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-PortOne-Secret", required = false) String headerSecret,
        @RequestParam(value = "secret", required = false) String querySecret
    ) {
        String impUid = (String) payload.get("imp_uid");
        String merchantUid = (String) payload.get("merchant_uid");

        log.info("[WEBHOOK] Received: impUid={}, merchantUid={}", impUid, merchantUid);

        // [버그픽스 B-1.3 · docs/17_assets/defect_register.md §B-1.3 · docs/19 D-17]
        //   이 엔드포인트는 SecurityConfig에서 permitAll이고 processWebhook은 호출자 신원을
        //   전혀 보지 않는다(/confirm은 order.getUser().getUsername() 소유권 검증을 한다).
        //   즉 merchant_uid만 알면 로그인 없이 타인 주문을 확정할 수 있었다.
        //   선례: IllustrationWebhookController.verifySecret — 다만 그쪽의 '미설정 시 skip'을
        //   그대로 쓰면 prod에서 시크릿 주입을 빠뜨리는 순간 결함이 그대로 되살아나므로
        //   prod 프로필에서는 fail-closed로 갈린다.
        //   ★ 시크릿 검증 실패는 [적대적 리뷰 P1] 이후에도 200을 유지한다 — 공격자에게 5회 재시도를
        //     선물할 이유가 없고, 시크릿이 틀린 요청은 다시 와도 똑같이 틀리다(복구 가능한 실패가 아니다).
        //     prod 시크릿 미주입은 fail-closed라 여기서 200으로 흡수되지만, 그 경우는 위 verifyWebhookSecret이
        //     ERROR 로그를 남기고 아래 미지급 감시 스케줄러(UndeliveredPaymentScheduler)가 후행 관측한다.
        if (!verifyWebhookSecret(headerSecret, querySecret)) {
            log.warn("[WEBHOOK] REJECTED — secret verification failed | impUid={}, merchantUid={}",
                impUid, merchantUid);
            return ResponseEntity.ok(Map.of("status", "rejected", "reason", "unauthorized"));
        }

        if (impUid == null || merchantUid == null) {
            log.warn("[WEBHOOK] Missing required fields. payload={}", payload);
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "missing fields"));
        }
        // [적대적 리뷰 P1] paid 외 상태(cancelled·failed·ready) 웹훅은 지급 경로에 넣지 않는다 — 환불 뒤 오는
        //   cancelled 웹훅이 PortOne 재조회로 REFUNDED 주문을 흔드는 경로를 입구에서 끊는다(서비스에도 2차 가드 있음).
        Object payloadStatus = payload.get("status");
        if (payloadStatus != null && !"paid".equalsIgnoreCase(String.valueOf(payloadStatus))) {
            log.info("[WEBHOOK] Ignored non-paid status={} | impUid={}, merchantUid={}", payloadStatus, impUid, merchantUid);
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "status " + payloadStatus));
        }

        // [적대적 리뷰 P1 · 웹훅 재시도 계약]
        //   기존 구현은 catch(Exception) 후 무조건 200 {status:ok}였다. PortOne V1은 **비200일 때만**
        //   최대 5회 재시도하므로, 재시도가 원리적으로 걸리지 않았다("PortOne 재시도 방지"라는 주석은
        //   의도를 정확히 반대로 구현한 것이다 — 재시도는 방지 대상이 아니라 복구 수단이다).
        //   위험이 큰 이유 두 가지:
        //     ① 모바일 결제는 리다이렉트 복귀(/confirm)가 깨지면 웹훅이 **유일한 지급 경로**다.
        //     ② 이번 세션이 넣은 assertConfigured(자격증명 미주입 시 EXTERNAL_API_ERROR)와
        //        prod fail-closed 시크릿 검증 때문에, 설정 실수 하나로 전 웹훅이 조용히 유실된다.
        //   이제 분류는 PaymentService.processWebhook이 한다:
        //     · 값(WebhookOutcome) 반환 → 재시도 무의미 → 200으로 종결
        //     · 예외 전파        → 복구 가능 → 503으로 재시도 유도(트랜잭션도 함께 롤백된다)
        try {
            PaymentService.WebhookOutcome outcome = paymentService.processWebhook(impUid, merchantUid);
            log.info("[WEBHOOK] Settled(200): merchantUid={}, outcome={}", merchantUid, outcome);
            return ResponseEntity.ok(Map.of("status", outcome.responseStatus()));
        } catch (Exception e) {
            // 복구 가능한 실패만 여기로 온다(확정 실패는 값으로 반환된다).
            //   503을 고르는 이유: 'PortOne이 다시 보내면 성공할 수 있다'는 의미가 500보다 정확하고,
            //   ALB/CloudWatch에서 5xx 알람 축과 자연히 이어진다.
            log.error("[WEBHOOK] RETRY REQUESTED(503) — recoverable failure | merchantUid={}, impUid={}",
                merchantUid, impUid, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "retry", "reason", "temporary failure"));
        }
    }

    // ─────────────────────────────────────────────
    // [버그픽스 B-1.3] 웹훅 공유 시크릿 검증
    // ─────────────────────────────────────────────

    /**
     * 공유 시크릿 검증. 헤더 우선, 없으면 쿼리 파라미터.
     *
     * <p>미설정(빈 값) 시:
     * <ul>
     *   <li>prod 프로필 → <b>false</b>(fail-closed). 전역 부팅 실패로 만들지 않는 이유는
     *       docs/19 D-31의 '자격증명 미주입은 전역 fail-fast가 아니라 진입부 차단' 판단과 같다 —
     *       부팅 블로커는 결제 하나 때문에 서비스 전체를 내린다.</li>
     *   <li>그 외(local/dev) → true(skip). 로컬에서 PortOne 콘솔 없이 웹훅을 재현하기 위함.</li>
     * </ul>
     */
    private boolean verifyWebhookSecret(String headerSecret, String querySecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            if (isProdProfile()) {
                log.error("[WEBHOOK] portone.webhook-secret NOT CONFIGURED in prod — fail-closed. "
                    + "PORTONE_WEBHOOK_SECRET 환경변수를 주입하고 PortOne 콘솔 웹훅 URL에 "
                    + "?secret=... 을 붙일 것.");
                return false;
            }
            log.warn("[WEBHOOK] portone.webhook-secret not configured — verification skipped (non-prod)");
            return true;
        }
        String provided = (headerSecret != null && !headerSecret.isBlank()) ? headerSecret : querySecret;
        if (provided == null || provided.isBlank()) return false;
        // 타이밍 공격 방어 — 길이 노출 없는 상수 시간 비교.
        return MessageDigest.isEqual(
            webhookSecret.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isProdProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }
}