package com.spring.aichat.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.spring.aichat.domain.enums.OrderStatus;
import com.spring.aichat.domain.enums.ProductType;
import com.spring.aichat.domain.payment.Order;
import com.spring.aichat.domain.payment.OrderRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.config.PortOneProperties;
import com.spring.aichat.dto.payment.*;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.external.PortOneClient;
import com.spring.aichat.service.cache.RedisCacheService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 결제 서비스
 *
 * [Phase 5 BM 패키지 연결 완료]
 *
 * [Phase 5 Fix] deliverProduct 변경
 * - SECRET_PASS_24H: activate24hPass 시그니처 변경
 *   기존: activate24hPass(userId, characterId)         ← Redis만 저장
 *   수정: activate24hPass(user, characterId, merchantUid) ← RDB + Redis 저장
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PortOneClient portOneClient;
    /** [C-2.l] PortOne 서버 자격증명 미주입 가드용 — 실호출 진입부에서 assertConfigured 호출. */
    private final PortOneProperties portOneProperties;
    private final RedisCacheService cacheService;
    private final SecretModeService secretModeService;
    private final SubscriptionService subscriptionService;

    // ─────────────────────────────────────────────
    // Step 1: 사전 주문 생성
    // ─────────────────────────────────────────────

    @Transactional
    public PrepareOrderResponse prepareOrder(String username, PrepareOrderRequest request) {
        User user = findUser(username);
        ProductType product = request.productType();

        // [docs/19_assets/decisions_confirmed.md §A #7 = (b) · 종원 확정] 시크릿 상품 노출 토글.
        //   ★ 서버측 게이트다(CLAUDE.md §2-4). 프론트에서 상품 카드만 숨기면 이 엔드포인트는
        //     성인 인증만 통과하면 열린 채 남아 'PG 심사 중 완전 게이팅'이라는 주장이 깨진다
        //     — beta-activate가 정확히 그 실수였다.
        //   묶이는 3종: SECRET_PASS_24H · SECRET_UNLOCK_PERMANENT · LUCID_MIDNIGHT_PASS.
        //     미드나잇 패스까지 포함하는 것이 안건 7의 확정((a) 아님) — docs/18 §1-D D2.
        //   해제는 '승인 PG의 성인 콘텐츠 정책 확인 이후'에만 (docs/14 §C-#3).
        if (product.isSecretGated() && !secretModeService.isSecretProductsEnabled()) {
            log.warn("[PAYMENT] Secret product blocked by rollout toggle: user={}, product={}",
                username, product.name());
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "현재 판매하지 않는 상품입니다.");
        }

        // [C-2.l · docs/17_assets/defect_register.md §C-2.l] PortOne 자격증명 미주입 가드.
        //   여기서 끊는 이유: 주문 행만 만들어 놓고 PG 창을 띄운 뒤 검증 단계에서 401이 나면
        //   유저는 결제창까지 갔다가 실패하고 PENDING 주문 쓰레기가 남는다. 착수 전에 끊는다.
        //   (실호출 진입부 차단은 verifyAndDeliver에도 별도로 있다 — 웹훅 경로는 prepareOrder를
        //    거치지 않으므로 두 곳 모두 필요하다.)
        //   ⚠ 위 노출 토글 *뒤*에 둔다. 앞에 두면 PortOne 키가 없는 로컬에서 시크릿 상품이
        //     400이 아니라 502로 떨어져, FE가 의존하는 게이트 응답 계약이 환경마다 달라진다.
        portOneProperties.assertConfigured("prepareOrder");

        // 성인 전용 상품 검증
        if (product.isAdultOnly() && !Boolean.TRUE.equals(user.getIsAdult())) {
            throw new BusinessException(ErrorCode.VERIFICATION_UNDERAGE,
                "Adult verification required for this product");
        }

        // 시크릿 영구해금: targetCharacterId 필수 + 이중 구매 방지
        if (product == ProductType.SECRET_UNLOCK_PERMANENT) {
            if (request.targetCharacterId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "target character ID required for secret unlock");
            }
            // [적대적 리뷰 P1 · 안건 8 확정] 중복 구매 가드를 **계정 단위**로 맞춘다.
            //   접근 판정(canAccessSecretMode → hasAnyPermanentUnlock)은 이미 user-global인데
            //   이 가드만 캐릭터 단위라, 캐릭터 A로 영구해금을 산 유저가 캐릭터 B 방에서
            //   같은 상품을 14,900원에 **다시 결제**할 수 있었다 — 새로 얻는 권한은 0이다.
            //   대상 선택 UI까지 없앤 지금(안건 8) 유저는 '어느 캐릭터에 붙는지'조차 모르므로
            //   막지 않으면 그대로 CS·환불·PG 분쟁 사유가 된다.
            if (secretModeService.hasAnyPermanentUnlock(user.getId())) {
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    "이미 시크릿 영구 해금을 보유하고 있습니다. 모든 캐릭터에 적용됩니다.");
            }
        }

        // 24시간 패스: targetCharacterId 필수
        if (product == ProductType.SECRET_PASS_24H) {
            if (request.targetCharacterId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "target character ID required for secret pass");
            }
            // [적대적 리뷰 P1 · 안건 8] 영구해금 동형 — 24h 패스도 접근 판정이 계정 단위다
            //   (hasAnyActive24hPass). 이미 접근이 열려 있는데 또 파는 것은 무가치 결제다.
            //   영구해금 보유자에게 24h 패스를 파는 것도 마찬가지로 막는다.
            if (secretModeService.hasAnyPermanentUnlock(user.getId())
                || secretModeService.hasAnyActive24hPass(user.getId())) {
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    "이미 시크릿 모드를 이용할 수 있습니다.");
            }
        }

        String merchantUid = "lucid_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Order order = Order.create(merchantUid, user, product, request.targetCharacterId());
        orderRepository.save(order);

        log.info("[PAYMENT] Order created: uid={}, product={}, amount={}",
            merchantUid, product.name(), product.getPriceKrw());

        return new PrepareOrderResponse(merchantUid, product.getDisplayName(), product.getPriceKrw());
    }

    // ─────────────────────────────────────────────
    // Step 3a: 클라이언트 사후 검증 (/confirm)
    // ─────────────────────────────────────────────

    @Transactional
    public PaymentResultResponse confirmPayment(String username, ConfirmPaymentRequest request) {
        Order order = orderRepository.findByMerchantUidForUpdate(request.merchantUid())
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                "Order not found: " + request.merchantUid()));

        if (!order.getUser().getUsername().equals(username)) {
            log.warn("[PAYMENT] Ownership mismatch: uid={}, user={}", request.merchantUid(), username);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Order ownership mismatch");
        }

        return verifyAndDeliver(order, request.impUid(), "CLIENT");
    }

    // ─────────────────────────────────────────────
    // Step 3b: 웹훅 수신 (/webhook)
    // ─────────────────────────────────────────────

    /**
     * [적대적 리뷰 P1] 웹훅 처리 결과. 컨트롤러가 <b>HTTP 상태 코드를 고르는 근거</b>다.
     *
     * <p>이 enum이 표현하는 것은 전부 <b>"재시도해도 결과가 같은"</b> 종결 상태다 → 컨트롤러는 200.
     * 복구 가능한 실패는 값으로 돌려주지 않고 <b>예외를 그대로 던진다</b>
     * ({@link #processWebhook} 참조) — 트랜잭션 롤백이 함께 필요하기 때문이다.
     */
    public enum WebhookOutcome {
        /** 검증 통과 + 재화 지급 완료. */
        DELIVERED("ok"),
        /** 이미 PAID거나 종료 상태(EXPIRED/FAILED/REFUNDED) — 멱등 성공. */
        ALREADY_PROCESSED("already_processed"),
        /** 우리가 발급한 적 없는 merchant_uid — 재시도해도 영원히 없다. */
        ORDER_NOT_FOUND("ignored"),
        /** merchant_uid 불일치·금액 불일치·PortOne status != paid 등 확정 거절. */
        NOT_DELIVERED("not_delivered");

        private final String responseStatus;

        WebhookOutcome(String responseStatus) { this.responseStatus = responseStatus; }

        /** 웹훅 응답 본문의 {@code status} 값. */
        public String responseStatus() { return responseStatus; }
    }

    /**
     * [적대적 리뷰 P1] PortOne <b>실호출</b> 실패 마커.
     *
     * <p>왜 필요한가: {@link PortOneClient}는 타임아웃만 {@code EXTERNAL_API_ERROR}로 구분하고
     * 나머지 전부(토큰 발급 실패·아임포트 5xx·결제 미존재)를 {@code PAYMENT_VERIFICATION_FAILED}로
     * 뭉갠다. 그런데 {@link #verifyAndDeliver}의 <b>merchant_uid 불일치</b>(위변조 확정)도 같은
     * {@code PAYMENT_VERIFICATION_FAILED}다. 에러 코드만으로는 '나중에 다시 하면 성공할 실패'와
     * '재시도해도 같은 실패'가 구분되지 않는다 — 메시지 문자열 비교는 깨지기 쉬우므로
     * <b>호출 지점</b>에서 타입으로 표식한다.
     *
     * <p>⚠ {@link BusinessException}을 상속하고 원본 {@code errorCode}를 그대로 물려주므로
     * {@code GlobalExceptionHandler}의 HTTP 매핑은 변하지 않는다 — {@code /confirm} 응답 계약 무변경.
     */
    public static class PortOneLookupException extends BusinessException {
        public PortOneLookupException(ErrorCode errorCode, String message, Throwable cause) {
            super(errorCode, message, cause);
        }
    }

    /**
     * PortOne 웹훅 처리.
     *
     * <p>[적대적 리뷰 P1 · 재시도 계약] 이전 구현은 모든 예외를 삼키고 항상 정상 반환했고,
     * 컨트롤러도 무조건 200을 냈다. PortOne V1은 <b>비200일 때만</b> 재시도하므로
     * 재시도가 원리적으로 걸리지 않았다. 모바일 결제는 리다이렉트 복귀가 깨지면 웹훅이
     * <b>유일한 지급 경로</b>이고, 이번 세션이 넣은 {@code assertConfigured}·prod fail-closed
     * 시크릿 검증 때문에 <b>설정 실수 하나로 전 웹훅이 조용히 유실</b>될 수 있다.
     *
     * <p>따라서 두 갈래로 나눈다:
     * <ul>
     *   <li><b>값 반환({@link WebhookOutcome}) → 컨트롤러 200</b>: 재시도해도 결과가 같은 것.
     *       주문 미존재, 멱등 성공, merchant_uid/금액 불일치, PortOne status != paid.
     *       이 경로는 {@code markFailed()} 기록을 <b>커밋해야</b> 하므로 예외를 밖으로 내보내지 않는다.</li>
     *   <li><b>예외 전파 → 컨트롤러 5xx</b>: PG 장애·타임아웃·자격증명 미주입·DB/Redis 일시 오류 등
     *       나중에 다시 하면 성공할 수 있는 것. 예외로 던지는 이유는 <b>롤백이 함께 필요</b>하기 때문이다 —
     *       {@code markPaid + saveAndFlush} 이후 {@code deliverProduct}가 터진 경우 커밋해 버리면
     *       주문은 PAID인데 재화는 없고, 재시도는 PAID 멱등 분기에 막혀 영구 미지급이 된다.</li>
     * </ul>
     */
    @Transactional
    public WebhookOutcome processWebhook(String impUid, String merchantUid) {
        Order order = orderRepository.findByMerchantUidForUpdate(merchantUid)
            .orElse(null);

        if (order == null) {
            // 재시도 무의미: 우리가 발급한 적 없는 merchant_uid는 다음 시도에도 없다.
            log.warn("[WEBHOOK] Order not found: merchantUid={}, impUid={}", merchantUid, impUid);
            return WebhookOutcome.ORDER_NOT_FOUND;
        }

        boolean alreadyPaidBefore = order.getStatus() == OrderStatus.PAID;

        try {
            verifyAndDeliver(order, impUid, "WEBHOOK");
        } catch (RuntimeException e) {
            if (isWebhookRetryable(e)) {
                log.error("[WEBHOOK] RETRYABLE FAILURE — 5xx로 응답해 PortOne 재시도를 유도한다 | "
                    + "uid={}, impUid={}, code={}", merchantUid, impUid, errorCodeOf(e), e);
                throw e; // ★ 롤백까지 함께 필요하므로 값이 아니라 예외로 올린다.
            }
            if (e instanceof BusinessException be
                && be.getErrorCode() == ErrorCode.PAYMENT_ALREADY_PROCESSED) {
                log.info("[WEBHOOK] Already processed (idempotent): uid={}", merchantUid);
                return WebhookOutcome.ALREADY_PROCESSED;
            }
            // 확정 실패. markFailed() 기록을 커밋하기 위해 예외를 삼키고 값으로 종결한다.
            log.error("[WEBHOOK] TERMINAL FAILURE — 재시도해도 결과가 같으므로 200으로 종결 | "
                    + "uid={}, impUid={}, code={}, error={}",
                merchantUid, impUid, errorCodeOf(e), e.getMessage());
            return WebhookOutcome.NOT_DELIVERED;
        }

        if (alreadyPaidBefore) {
            return WebhookOutcome.ALREADY_PROCESSED;
        }
        if (order.getStatus() != OrderStatus.PAID) {
            // PortOne status != "paid" 경로 — verifyAndDeliver가 markFailed 후 *정상 반환*한다.
            //   예외가 없으므로 위 catch에 걸리지 않는다. 여기서 별도로 걸러야 200 "ok"로
            //   오보고되지 않는다.
            log.warn("[WEBHOOK] Not delivered (PortOne status not paid): uid={}, orderStatus={}",
                merchantUid, order.getStatus());
            return WebhookOutcome.NOT_DELIVERED;
        }
        return WebhookOutcome.DELIVERED;
    }

    /**
     * [적대적 리뷰 P1] 웹훅 실패 분류표. <b>ErrorCode enum과 verifyAndDeliver가 실제로 던지는
     * 예외를 전수 확인해 만든 표다.</b>
     *
     * <table>
     *   <caption>분류 근거</caption>
     *   <tr><th>발생 지점</th><th>예외</th><th>판정</th></tr>
     *   <tr><td>{@code PortOneProperties.assertConfigured}</td><td>EXTERNAL_API_ERROR</td>
     *       <td>재시도 — 환경변수 주입 후 성공</td></tr>
     *   <tr><td>{@code PortOneClient} 전 경로</td><td>{@link PortOneLookupException}</td>
     *       <td>재시도 — 타임아웃·아임포트 5xx·토큰 실패</td></tr>
     *   <tr><td>EXPIRED 분기 / 비 PENDING 상태</td><td>PAYMENT_ALREADY_PROCESSED</td>
     *       <td>200 — 상태는 되돌아오지 않는다</td></tr>
     *   <tr><td>merchant_uid 불일치</td><td>PAYMENT_VERIFICATION_FAILED</td>
     *       <td>200 — 위변조 확정, markFailed 커밋 필요</td></tr>
     *   <tr><td>금액 불일치</td><td>PAYMENT_AMOUNT_MISMATCH</td>
     *       <td>200 — 자동 환불 완료, markFailed 커밋 필요</td></tr>
     *   <tr><td>imp_uid unique 위반</td><td>PAYMENT_ALREADY_PROCESSED</td>
     *       <td>200 — 다른 주문이 이미 소비</td></tr>
     *   <tr><td>DB/Redis 일시 오류, 기타 미분류</td><td>임의 RuntimeException</td>
     *       <td>재시도 — <b>기본값을 재시도로 둔다</b></td></tr>
     * </table>
     *
     * <p>미분류 예외의 기본값이 '재시도'인 이유: 오분류의 비용이 비대칭이다. 재시도를
     * 잘못 걸면 멱등 분기가 흡수하지만(최대 5회), 재시도를 잘못 포기하면 <b>결제된 돈이
     * 지급 없이 사라진다</b>.
     */
    private static boolean isWebhookRetryable(RuntimeException e) {
        // ★ 순서 주의: PortOneLookupException은 BusinessException의 하위 타입이라 먼저 봐야 한다.
        if (e instanceof PortOneLookupException) return true;
        if (e instanceof BusinessException be) {
            return switch (be.getErrorCode()) {
                case EXTERNAL_API_ERROR, INTERNAL_ERROR -> true;
                default -> false;
            };
        }
        return true;
    }

    private static String errorCodeOf(RuntimeException e) {
        return (e instanceof BusinessException be)
            ? be.getErrorCode().name()
            : e.getClass().getSimpleName();
    }

    // ─────────────────────────────────────────────
    // 공통 검증 + 지급 로직
    // ─────────────────────────────────────────────

    private PaymentResultResponse verifyAndDeliver(Order order, String impUid, String caller) {

        if (order.getStatus() == OrderStatus.PAID) {
            log.info("[PAYMENT:{}] Already PAID (idempotent): uid={}", caller, order.getMerchantUid());
            return buildResult(order, "Already processed");
        }

        // [C-2.l · docs/17_assets/defect_register.md §C-2.l · 선례 NiceApiClient.getAccessToken]
        //   이 아래 모든 분기가 PortOne 실호출(getPaymentInfo/cancelPayment)에 의존한다.
        //   자격증명이 안 꽂혀 있으면 아임포트가 401을 주고, 그 401은 로그에서 'PG 장애'와
        //   구분되지 않은 채 "정상 결제인데 지급 안 됨"으로 나타난다 — 명시적으로 먼저 끊는다.
        //   PAID 멱등 반환보다 *뒤*에 두는 이유: 이미 지급된 주문의 조회까지 설정 누락으로
        //   막을 이유가 없다(외부 호출을 하지 않는 경로다).
        portOneProperties.assertConfigured("verifyAndDeliver:" + caller);

        // [Phase6/Tier4 / H-23] EXPIRED + paid 자동 환불.
        //   시나리오: 30분 후 스케줄러가 EXPIRED 마킹 → 31분 후 PortOne webhook(paid) 도착.
        //   기존 흐름은 PAYMENT_ALREADY_PROCESSED throw로 종료 → PortOne 결제 + DB EXPIRED +
        //   환불 미실행 → 사용자 돈만 잃고 재화 미지급. 자동 환불로 차단.
        if (order.getStatus() == OrderStatus.EXPIRED) {
            try {
                JsonNode paymentInfo = portOneClient.getPaymentInfo(impUid);
                // [버그픽스 B-1.1 확장 · docs/19 D-17]
                //   ★ B-1.1 수정안이 지목한 :175 자리보다 *앞*에 있는 자동 환불 분기라 별도 가드가 필요하다.
                //   가드가 없으면 '타인 결제 강제 환불' 착취가 성립한다:
                //     공격자가 최소가 주문을 만들고 30분 방치해 EXPIRED로 만든 뒤,
                //     피해자의 imp_uid로 /confirm 호출 → 소유권 검사는 *공격자 본인 주문*이라 통과 →
                //     여기서 피해자의 정상 결제가 전액 취소된다(금액도 응답값 그대로 쓰므로 상한도 없다).
                //   이 주문의 결제가 아니면 환불하지 않고 조용히 종료한다.
                String expiredMerchantUid = paymentInfo.path("merchant_uid").asText(null);
                boolean sameOrder = order.getMerchantUid().equals(expiredMerchantUid);
                if (!sameOrder) {
                    log.error("[PAYMENT:{}] EXPIRED branch MERCHANT_UID MISMATCH — refund refused | "
                            + "order={}, portone={}, impUid={}",
                        caller, order.getMerchantUid(), expiredMerchantUid, impUid);
                }
                if (sameOrder && "paid".equals(paymentInfo.path("status").asText())) {
                    log.error("[PAYMENT:{}] Late payment on EXPIRED order — auto refund | uid={}",
                        caller, order.getMerchantUid());
                    try {
                        portOneClient.cancelPayment(impUid, paymentInfo.path("amount").asInt(),
                            "Order expired, auto refund");
                        log.info("[PAYMENT:{}] EXPIRED auto-refund success: impUid={}", caller, impUid);
                    } catch (Exception e) {
                        // alert: 운영자 수동 처리 필요. throw하지 않고 fall-through.
                        log.error("[PAYMENT:{}] EXPIRED auto-refund FAILED — manual intervention required! impUid={}",
                            caller, impUid, e);
                    }
                }
            } catch (Exception e) {
                log.error("[PAYMENT:{}] PortOne lookup failed on EXPIRED order: impUid={}", caller, impUid, e);
            }
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                "Order expired");
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("[PAYMENT:{}] Non-PENDING state: uid={}, status={}",
                caller, order.getMerchantUid(), order.getStatus());
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                "Order in terminal state: " + order.getStatus());
        }

        // [적대적 리뷰 P1 · 웹훅 재시도 분류] PortOne 실호출 실패에 '호출 지점' 표식을 단다.
        //   아래 merchant_uid 불일치와 PortOneClient 내부 실패가 둘 다 PAYMENT_VERIFICATION_FAILED라
        //   에러 코드만으로는 '복구 가능'과 '확정 실패'가 갈리지 않는다 — PortOneLookupException 참조.
        //   errorCode를 그대로 물려주므로 /confirm의 HTTP 응답은 바뀌지 않는다.
        JsonNode paymentInfo;
        try {
            paymentInfo = portOneClient.getPaymentInfo(impUid);
        } catch (BusinessException e) {
            throw new PortOneLookupException(e.getErrorCode(), e.getMessage(), e);
        }
        int paidAmount = paymentInfo.path("amount").asInt();
        String portOneStatus = paymentInfo.path("status").asText();

        // [버그픽스 B-1.1 · docs/17_assets/defect_register.md §B-1.1 · docs/19 D-17]
        //   PortOne 응답의 merchant_uid를 주문과 대조한다. 기존 검증은 status와 금액 둘뿐이라
        //   (1) 같은 imp_uid를 서로 다른 PENDING 주문 N건에 붙여 N회 지급받거나
        //   (2) 동일가 교차 상품(14,900원 LUCID_PASS ↔ SECRET_UNLOCK_PERMANENT)을 통과시켰다.
        //     — 금액은 order.getAmount() 기준으로 맞지만 지급은 order.getProductType() 기준이라
        //       구독 결제 1건의 imp_uid로 시크릿 영구해금을 무상 취득할 수 있었다.
        //   status 체크보다 앞에 둔다: 애초에 이 주문의 결제가 아니면 상태·금액을 볼 이유가 없다.
        //   ⚠ 자동 환불(cancelPayment)을 호출하지 않는다 — 이 경로의 결제는 *타인/타주문의 정상
        //     결제*라 환불하면 정상 유저가 피해를 본다. 금액 불일치 경로와 대칭으로 만들면 안 된다.
        String paidMerchantUid = paymentInfo.path("merchant_uid").asText(null);
        if (paidMerchantUid == null || !paidMerchantUid.equals(order.getMerchantUid())) {
            log.error("[PAYMENT:{}] MERCHANT_UID MISMATCH! order={}, portone={}, impUid={}",
                caller, order.getMerchantUid(), paidMerchantUid, impUid);
            order.markFailed("merchant_uid mismatch: " + paidMerchantUid);
            orderRepository.save(order);
            throw new BusinessException(ErrorCode.PAYMENT_VERIFICATION_FAILED,
                "Payment/order mismatch");
        }

        if (!"paid".equals(portOneStatus)) {
            order.markFailed("PortOne status: " + portOneStatus);
            orderRepository.save(order);
            log.warn("[PAYMENT:{}] Not paid: uid={}, portOneStatus={}",
                caller, order.getMerchantUid(), portOneStatus);
            return buildResult(order, "Payment not completed");
        }

        if (paidAmount != order.getAmount()) {
            log.error("[PAYMENT:{}] AMOUNT MISMATCH! uid={}, expected={}, actual={}",
                caller, order.getMerchantUid(), order.getAmount(), paidAmount);
            try {
                portOneClient.cancelPayment(impUid, paidAmount, "Amount mismatch - auto refund");
                log.info("[PAYMENT:{}] Auto-refund success: impUid={}", caller, impUid);
            } catch (Exception e) {
                log.error("[PAYMENT:{}] AUTO-REFUND FAILED! impUid={}", caller, impUid, e);
            }
            order.markFailed("Amount mismatch: expected=" + order.getAmount() + " actual=" + paidAmount);
            orderRepository.save(order);
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                "Amount mismatch detected. Auto-refund initiated.");
        }

        // [버그픽스 B-1.2 · docs/17_assets/defect_register.md §B-1.2 · docs/19 D-17]
        //   imp_uid unique 인덱스(V28)의 최후 방어선. merchantUid 단위 비관적 락은 *서로 다른*
        //   주문 행을 잠그므로 "같은 imp_uid를 병렬로 두 주문에" 붙이는 레이스를 못 막는다.
        //   지급(deliverProduct) *전에* saveAndFlush 해서 제약 위반을 먼저 터뜨린다 — 커밋 시점
        //   flush였다면 이미 재화가 나간 뒤에 롤백이 걸린다(에너지·구독은 같은 TX라 함께 되돌아가지만
        //   Redis 캐시·외부 호출은 되돌릴 수단이 없다).
        order.markPaid(impUid);
        try {
            orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            log.error("[PAYMENT:{}] IMP_UID REUSE blocked by unique index | uid={}, impUid={}",
                caller, order.getMerchantUid(), impUid, e);
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                "impUid already consumed by another order: " + impUid);
        }
        deliverProduct(order);
        orderRepository.save(order);
        cacheService.evictUserProfile(order.getUser().getUsername());

        log.info("[PAYMENT:{}] Confirmed: uid={}, product={}, amount={}",
            caller, order.getMerchantUid(), order.getProductType().name(), order.getAmount());

        return buildResult(order, "Payment confirmed");
    }

    // ─────────────────────────────────────────────
    // 재화 지급 (BM 패키지 연동)
    // ─────────────────────────────────────────────

    /**
     * 상품 지급 로직
     *
     * [Phase 5 Fix] SECRET_PASS_24H: RDB 영속화 + Redis 캐싱
     */
    private void deliverProduct(Order order) {
        User user = order.getUser();
        ProductType product = order.getProductType();

        switch (product) {
            case ENERGY_T1, ENERGY_T2, ENERGY_T3 -> {
                user.chargePaidEnergy(product.getEnergyAmount());
                userRepository.save(user);
                log.info("[DELIVER] Energy: user={}, +{}", user.getUsername(), product.getEnergyAmount());
            }

            case SECRET_PASS_24H -> {
                // [Phase 5 Fix] User 엔티티 + merchantUid 전달 → RDB 영속화
                secretModeService.activate24hPass(user, order.getTargetCharacterId(), order.getMerchantUid());
                log.info("[DELIVER] Secret 24h pass (RDB persisted): user={}, charId={}",
                    user.getUsername(), order.getTargetCharacterId());
            }

            case SECRET_UNLOCK_PERMANENT -> {
                secretModeService.createPermanentUnlock(
                    user, order.getTargetCharacterId(), order.getMerchantUid());
                log.info("[DELIVER] Secret permanent unlock: user={}, charId={}",
                    user.getUsername(), order.getTargetCharacterId());
            }

            case LUCID_PASS, LUCID_MIDNIGHT_PASS -> {
                subscriptionService.activateSubscription(
                    user, product.toSubscriptionType(), order.getMerchantUid());
                log.info("[DELIVER] Subscription: user={}, tier={}",
                    user.getUsername(), product.toSubscriptionType());
            }
        }
    }

    // ─────────────────────────────────────────────

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
    }

    private PaymentResultResponse buildResult(Order order, String message) {
        return new PaymentResultResponse(
            order.getMerchantUid(), order.getImpUid(),
            order.getProductType(), order.getAmount(),
            order.getStatus(), message);
    }
}