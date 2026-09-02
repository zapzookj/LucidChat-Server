package com.spring.aichat.service.payment;

import com.spring.aichat.config.PortOneProperties;
import com.spring.aichat.domain.enums.OrderStatus;
import com.spring.aichat.domain.enums.ProductType;
import com.spring.aichat.domain.payment.Order;
import com.spring.aichat.domain.payment.OrderRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.external.PortOneClient;
import com.spring.aichat.service.audit.AuditLogService;
import com.spring.aichat.service.cache.RedisCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * [D-4.2 · D-4.3 · 안건 6 (c)+(나)] 환불 오케스트레이션 계약 —
 * 과거 회차 구독은 PortOne 취소 <b>전</b>에 거부, 회수 대상 미발견은 환불·전이·감사로그 유지 + 예외 승격.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private PortOneClient portOneClient;
    @Mock private PortOneProperties portOneProperties;
    @Mock private SecretModeService secretModeService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private RedisCacheService cacheService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private RefundService service;

    private Order paidOrder(ProductType product) {
        User user = User.local("buyer", "pw", "nick", "b@test.com");
        Order order = Order.create("uid-1", user, product, null);
        order.markPaid("imp-1");      // → PAID_UNDELIVERED (안건 4)
        order.markDelivered();        // → PAID
        when(orderRepository.findByMerchantUidForUpdate("uid-1")).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("[안건 4] PAID_UNDELIVERED 주문은 회수·회차 검증 없이 취소만 — REFUNDED + REFUND_EXECUTE")
    void undeliveredOrderRefundsWithoutClawback() {
        User user = User.local("buyer", "pw", "nick", "b@test.com");
        Order order = Order.create("uid-1", user, ProductType.LUCID_PASS, null);
        order.markPaid("imp-1");      // 지급 실패로 PAID_UNDELIVERED에 머문 주문
        when(orderRepository.findByMerchantUidForUpdate("uid-1")).thenReturn(Optional.of(order));

        service.refund("admin", "uid-1", "미지급 취소");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(portOneClient).cancelPayment("imp-1", order.getAmount(), "미지급 취소");
        verify(subscriptionService, never()).assertRefundableRound(anyString());
        verify(subscriptionService, never()).clawbackRound(anyString());
        verify(auditLogService).record(eq("admin"), eq("REFUND_EXECUTE"), eq("ORDER"), eq("uid-1"), contains("미지급 주문 취소"));
    }

    @Test
    @DisplayName("[안건 6 (c)] 과거 회차 구독 주문은 PortOne 취소 전에 거부된다 — 돈이 나가지 않는다")
    void pastRoundSubscriptionRejectedBeforeCancel() {
        Order order = paidOrder(ProductType.LUCID_PASS);
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "과거 회차 구독 결제는 환불할 수 없습니다"))
            .when(subscriptionService).assertRefundableRound("uid-1");

        assertThatThrownBy(() -> service.refund("admin", "uid-1", "cs"))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST))
            .hasMessageContaining("과거 회차");

        verify(portOneClient, never()).cancelPayment(anyString(), anyInt(), anyString());
        verify(subscriptionService, never()).clawbackRound(anyString());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("[안건 6 (나)] 회수 대상 없음: 취소·REFUNDED·REFUND_CLAWBACK_FAILED 감사로그는 남기고 예외로 승격 (REFUND_EXECUTE 아님)")
    void clawbackMissingIsEscalatedNotSwallowed() {
        Order order = paidOrder(ProductType.SECRET_UNLOCK_PERMANENT);
        when(secretModeService.revokePermanentUnlockByMerchantUid("uid-1")).thenReturn(false);

        assertThatThrownBy(() -> service.refund("admin", "uid-1", "cs"))
            .isInstanceOf(RefundService.ClawbackFailedException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.REFUND_CLAWBACK_FAILED));

        verify(portOneClient).cancelPayment("imp-1", order.getAmount(), "cs");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);   // 롤백 없음(noRollbackFor) — 유저 유리 원칙 유지
        verify(auditLogService).record(eq("admin"), eq("REFUND_CLAWBACK_FAILED"), eq("ORDER"), eq("uid-1"), contains("회수 대상 없음"));
        verify(auditLogService, never()).record(anyString(), eq("REFUND_EXECUTE"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("정상 경로: 최신 회차 구독은 회수 성공 + REFUND_EXECUTE")
    void currentRoundSubscriptionRefundHappyPath() {
        Order order = paidOrder(ProductType.LUCID_MIDNIGHT_PASS);
        when(subscriptionService.clawbackRound("uid-1")).thenReturn(true);

        service.refund("admin", "uid-1", "cs");

        verify(subscriptionService).assertRefundableRound("uid-1");
        verify(portOneClient).cancelPayment("imp-1", order.getAmount(), "cs");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(auditLogService).record(eq("admin"), eq("REFUND_EXECUTE"), eq("ORDER"), eq("uid-1"), anyString());
    }

    @Test
    @DisplayName("REFUNDED 주문 재환불은 400 — PortOne 취소 없음")
    void refundedOrderCannotBeRefundedAgain() {
        Order order = paidOrder(ProductType.ENERGY_T1);
        order.markRefunded();

        assertThatThrownBy(() -> service.refund("admin", "uid-1", "cs"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("현재 상태: REFUNDED");
        verify(portOneClient, never()).cancelPayment(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("PortOne 취소 실패면 아무것도 바꾸지 않는다 — PAID 유지·회수 없음·감사로그 없음")
    void portOneCancelFailureLeavesOrderPaid() {
        Order order = paidOrder(ProductType.SECRET_PASS_24H);
        doThrow(new RuntimeException("pg down")).when(portOneClient).cancelPayment(anyString(), anyInt(), anyString());

        assertThatThrownBy(() -> service.refund("admin", "uid-1", "cs"))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_API_ERROR));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(secretModeService, never()).revoke24hPassByMerchantUid(anyString());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("24h 패스 회수 대상 없음도 동일하게 승격된다")
    void secretPassClawbackMissingEscalates() {
        Order order = paidOrder(ProductType.SECRET_PASS_24H);
        when(secretModeService.revoke24hPassByMerchantUid("uid-1")).thenReturn(false);

        assertThatThrownBy(() -> service.refund("admin", "uid-1", "만료 후 환불"))
            .isInstanceOf(RefundService.ClawbackFailedException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(auditLogService).record(eq("admin"), eq("REFUND_CLAWBACK_FAILED"), eq("ORDER"), eq("uid-1"), anyString());
    }

    @Test
    @DisplayName("에너지 상품은 회차 개념 없이 클램핑 회수 — 항상 성공 처리")
    void energyClawbackAlwaysSucceeds() {
        Order order = paidOrder(ProductType.ENERGY_T1);
        User user = order.getUser();
        user.chargePaidEnergy(ProductType.ENERGY_T1.getEnergyAmount());

        service.refund("admin", "uid-1", null);

        assertThat(user.getPaidEnergy()).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(portOneClient).cancelPayment("imp-1", order.getAmount(), "관리자 환불");
        verify(auditLogService).record(eq("admin"), eq("REFUND_EXECUTE"), eq("ORDER"), eq("uid-1"), anyString());
    }
}
