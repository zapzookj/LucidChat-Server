package com.spring.aichat.service.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.PortOneProperties;
import com.spring.aichat.domain.enums.OrderStatus;
import com.spring.aichat.domain.enums.ProductType;
import com.spring.aichat.domain.payment.Order;
import com.spring.aichat.domain.payment.OrderRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.payment.ConfirmPaymentRequest;
import com.spring.aichat.dto.payment.PaymentResultResponse;
import com.spring.aichat.external.PortOneClient;
import com.spring.aichat.service.audit.AuditLogService;
import com.spring.aichat.service.cache.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * [안건 4 (b) · decisions_confirmed §A #4] 결제 확정(TX-A)과 지급(TX-B) 분리 계약.
 *
 * <p>txTemplate은 콜백 즉시 실행 스텁 — 여기서 검증하는 것은 트랜잭션 인프라가 아니라
 * "지급이 던져도 결제 확정(PAID_UNDELIVERED)은 남고, 재시도는 검증 없이 지급만 다시 한다"는 오케스트레이션이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceDeliveryTest {

    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private PortOneClient portOneClient;
    @Mock private PortOneProperties portOneProperties;
    @Mock private RedisCacheService cacheService;
    @Mock private SecretModeService secretModeService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private TransactionTemplate txTemplate;
    @Mock private AuditLogService auditLogService;
    @Mock private jakarta.persistence.EntityManager entityManager;

    @InjectMocks private PaymentService service;

    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(txTemplate.execute(any())).thenAnswer(inv ->
            ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
        user = User.local("buyer", "pw", "nick", "b@test.com");
    }

    private Order pendingOrder(ProductType product) {
        Order order = Order.create("uid-1", user, product, product.isSecretProduct() ? 42L : null);
        when(orderRepository.findByMerchantUidForUpdate("uid-1")).thenReturn(Optional.of(order));
        ObjectNode info = new ObjectMapper().createObjectNode();
        info.put("amount", order.getAmount());
        info.put("status", "paid");
        info.put("merchant_uid", "uid-1");
        when(portOneClient.getPaymentInfo("imp-1")).thenReturn(info);
        return order;
    }

    @Test
    @DisplayName("정상: 검증·확정(TX-A) 후 지급(TX-B) — 응답 status PAID, 재화 지급됨")
    void confirmHappyPath() {
        Order order = pendingOrder(ProductType.ENERGY_T1);

        PaymentResultResponse res = service.confirmPayment("buyer", new ConfirmPaymentRequest("imp-1", "uid-1"));

        assertThat(res.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getImpUid()).isEqualTo("imp-1");
        assertThat(user.getPaidEnergy()).isEqualTo(ProductType.ENERGY_T1.getEnergyAmount());
        verify(orderRepository).saveAndFlush(order);   // imp_uid unique 선방어는 그대로
        verifyNoInteractions(auditLogService);
        // [리뷰 P2] TX 경계 개수 — TX-A(확정)·TX-B(지급) 정확히 2개. 하나로 합치면(=원래 버그) 즉시 실패한다
        verify(txTemplate, times(2)).execute(any());
        verify(txTemplate, never()).executeWithoutResult(any());
        // [리뷰 P0 · OSIV] TX-B는 락 획득 뒤 DB 상태로 강제 갱신한다
        verify(entityManager).refresh(order);
    }

    @Test
    @DisplayName("[리뷰 P1] REFUNDED 주문에 재시도·취소 웹훅이 와도 PortOne을 보지 않고 ALREADY_PROCESSED — REFUNDED→FAILED 덮어쓰기 차단")
    void refundedOrderWebhookIsSettled() {
        Order order = pendingOrder(ProductType.ENERGY_T1);
        order.markPaid("imp-1");
        order.markRefunded();   // 미지급 주문 환불(안건 4) 후 재시도 웹훅 도착 시나리오

        assertThat(service.processWebhook("imp-1", "uid-1")).isEqualTo(PaymentService.WebhookOutcome.ALREADY_PROCESSED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(portOneClient, never()).getPaymentInfo(anyString());

        PaymentResultResponse res = service.confirmPayment("buyer", new ConfirmPaymentRequest("imp-1", "uid-1"));
        assertThat(res.message()).isEqualTo("Already settled");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("[리뷰 P1] 지급 전 환불된 PAID_UNDELIVERED 주문의 재지급은 예외가 아니라 종결 — 503 재시도 루프 없음")
    void redeliverOnRefundedIsTerminal() {
        Order order = pendingOrder(ProductType.ENERGY_T1);
        order.markPaid("imp-1");
        order.markRefunded();

        assertThat(service.redeliver("uid-1", "SCHEDULER")).isTrue();
        assertThat(user.getPaidEnergy()).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("지급 실패: 결제 확정은 PAID_UNDELIVERED로 남고 사유·감사로그 기록 + DeliveryFailedException (종전엔 PENDING 롤백)")
    void deliveryFailureLeavesPaidUndelivered() {
        Order order = pendingOrder(ProductType.SECRET_UNLOCK_PERMANENT);
        doThrow(new RuntimeException("Character not found: 42"))
            .when(secretModeService).createPermanentUnlock(any(), eq(42L), eq("uid-1"));

        assertThatThrownBy(() -> service.confirmPayment("buyer", new ConfirmPaymentRequest("imp-1", "uid-1")))
            .isInstanceOf(PaymentService.DeliveryFailedException.class)
            .satisfies(e -> assertThat(((com.spring.aichat.exception.BusinessException) e).getErrorCode())
                .isEqualTo(com.spring.aichat.exception.ErrorCode.PAYMENT_DELIVERY_PENDING))
            .hasMessageContaining("지급이 지연");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID_UNDELIVERED);
        assertThat(order.getImpUid()).isEqualTo("imp-1");
        assertThat(order.getFailedReason()).contains("Character not found");
        verify(auditLogService).record(eq("SYSTEM"), eq("PAYMENT_UNDELIVERED"), eq("ORDER"), eq("uid-1"), contains("지급 실패"));
        // [리뷰 P2] TX-A·TX-B 2개 + TX-C(사유 기록) 1개 — 경계가 합쳐지면 실패
        verify(txTemplate, times(2)).execute(any());
        verify(txTemplate, times(1)).executeWithoutResult(any());
    }

    @Test
    @DisplayName("[리뷰 P2] 재지급이 정확히 1회만 지급한다 — 실패 시도는 지급 부수효과를 남기지 않고, 성공 시도 1회 뒤 PAID")
    void redeliveryDeliversExactlyOnce() {
        Order order = pendingOrder(ProductType.SECRET_UNLOCK_PERMANENT);
        doThrow(new RuntimeException("db blip")).doNothing()
            .when(secretModeService).createPermanentUnlock(any(), eq(42L), eq("uid-1"));

        assertThatThrownBy(() -> service.processWebhook("imp-1", "uid-1"))
            .isInstanceOf(PaymentService.DeliveryFailedException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID_UNDELIVERED);

        assertThat(service.redeliver("uid-1", "SCHEDULER")).isTrue();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(secretModeService, times(2)).createPermanentUnlock(any(), eq(42L), eq("uid-1"));   // 실패 1 + 성공 1
        verify(portOneClient, times(1)).getPaymentInfo("imp-1");                                  // 재검증 없음
    }

    @Test
    @DisplayName("재시도: PAID_UNDELIVERED 주문의 /confirm은 PortOne 재검증 없이 지급만 다시 한다 → PAID")
    void retryDeliversWithoutReVerification() {
        Order order = pendingOrder(ProductType.ENERGY_T2);
        order.markPaid("imp-1");   // 이전 시도에서 확정만 되고 지급 실패한 상태
        order.recordDeliveryFailure("db blip");

        PaymentResultResponse res = service.confirmPayment("buyer", new ConfirmPaymentRequest("imp-1", "uid-1"));

        assertThat(res.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getFailedReason()).isNull();
        assertThat(user.getPaidEnergy()).isEqualTo(ProductType.ENERGY_T2.getEnergyAmount());
        verify(portOneClient, never()).getPaymentInfo(anyString());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("캐시 무효화 실패는 커밋된 지급을 되돌리지 않는다 — 삼키고 PAID 유지")
    void cacheEvictionFailureDoesNotUndoDelivery() {
        Order order = pendingOrder(ProductType.ENERGY_T1);
        doThrow(new RuntimeException("redis down")).when(cacheService).evictUserProfile(anyString());

        PaymentResultResponse res = service.confirmPayment("buyer", new ConfirmPaymentRequest("imp-1", "uid-1"));

        assertThat(res.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("이미 PAID(지급 완료) 주문은 멱등 반환 — 지급도 검증도 다시 하지 않는다")
    void alreadyPaidIsIdempotent() {
        Order order = pendingOrder(ProductType.ENERGY_T1);
        order.markPaid("imp-1");
        order.markDelivered();

        PaymentResultResponse res = service.confirmPayment("buyer", new ConfirmPaymentRequest("imp-1", "uid-1"));

        assertThat(res.message()).isEqualTo("Already processed");
        assertThat(user.getPaidEnergy()).isZero();
        verify(portOneClient, never()).getPaymentInfo(anyString());
    }

    @Test
    @DisplayName("웹훅: 지급 실패는 예외로 전파(503 재시도 유도), 다음 웹훅은 PAID_UNDELIVERED를 보고 지급만 → DELIVERED")
    void webhookRetryContract() {
        Order order = pendingOrder(ProductType.ENERGY_T1);
        // 지급 도중(userRepository.save) DB 순단 — deliverProduct가 던지고 TX-B가 롤백된다
        doThrow(new RuntimeException("db blip")).when(userRepository).save(any());

        assertThatThrownBy(() -> service.processWebhook("imp-1", "uid-1"))
            .isInstanceOf(PaymentService.DeliveryFailedException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID_UNDELIVERED);
        assertThat(order.getFailedReason()).contains("db blip");

        doReturn(user).when(userRepository).save(any());
        PaymentService.WebhookOutcome outcome = service.processWebhook("imp-1", "uid-1");

        assertThat(outcome).isEqualTo(PaymentService.WebhookOutcome.DELIVERED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(portOneClient, times(1)).getPaymentInfo("imp-1");   // 재시도 웹훅은 재검증하지 않는다
    }

    @Test
    @DisplayName("웹훅: 지급까지 끝난 주문에 재도착하면 ALREADY_PROCESSED")
    void webhookAlreadyProcessed() {
        Order order = pendingOrder(ProductType.ENERGY_T1);
        order.markPaid("imp-1");
        order.markDelivered();

        assertThat(service.processWebhook("imp-1", "uid-1")).isEqualTo(PaymentService.WebhookOutcome.ALREADY_PROCESSED);
    }
}
