package com.spring.aichat.domain.payment;

import com.spring.aichat.domain.enums.OrderStatus;
import com.spring.aichat.domain.enums.ProductType;
import com.spring.aichat.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [안건 4 (b) · decisions_confirmed §A #4] 주문 상태 머신 — 결제 확정(PAID_UNDELIVERED)과 지급(PAID)의 분리.
 */
class OrderTransitionTest {

    private static Order pending() {
        return Order.create("uid", User.local("u", "pw", "n", "u@t.com"), ProductType.ENERGY_T1, null);
    }

    @Test
    @DisplayName("markPaid는 돈 확정만 — PAID_UNDELIVERED. markDelivered가 PAID를 찍는다")
    void paidThenDelivered() {
        Order o = pending();
        o.markPaid("imp");
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID_UNDELIVERED);
        assertThat(o.getImpUid()).isEqualTo("imp");
        assertThat(o.getPaidAt()).isNotNull();
        assertThat(o.getStatus().isPaidMoney()).isTrue();

        o.markDelivered();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(o.getFailedReason()).isNull();
    }

    @Test
    @DisplayName("지급 실패 사유는 상태를 바꾸지 않고 기록만 — 재지급 후 사유는 지워진다")
    void deliveryFailureRecordedThenCleared() {
        Order o = pending();
        o.markPaid("imp");
        o.recordDeliveryFailure("Character not found: 42");
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID_UNDELIVERED);
        assertThat(o.getFailedReason()).isEqualTo("Character not found: 42");

        o.markDelivered();
        assertThat(o.getFailedReason()).isNull();
    }

    @Test
    @DisplayName("PAID·PAID_UNDELIVERED 둘 다 환불 가능, PENDING은 불가")
    void refundableStates() {
        Order undelivered = pending();
        undelivered.markPaid("imp");
        undelivered.markRefunded();
        assertThat(undelivered.getStatus()).isEqualTo(OrderStatus.REFUNDED);

        Order delivered = pending();
        delivered.markPaid("imp");
        delivered.markDelivered();
        delivered.markRefunded();
        assertThat(delivered.getStatus()).isEqualTo(OrderStatus.REFUNDED);

        assertThatThrownBy(() -> pending().markRefunded()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("불법 전이는 거부 — PENDING에서 markDelivered, PAID에서 markPaid/recordDeliveryFailure")
    void illegalTransitions() {
        assertThatThrownBy(() -> pending().markDelivered()).isInstanceOf(IllegalStateException.class);
        Order o = pending();
        o.markPaid("imp");
        o.markDelivered();
        assertThatThrownBy(() -> o.markPaid("imp2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> o.recordDeliveryFailure("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[리뷰 P1] markFailed는 PENDING에서만 — REFUNDED·PAID·PAID_UNDELIVERED를 덮어쓰지 못한다")
    void markFailedOnlyFromPending() {
        Order refunded = pending();
        refunded.markPaid("imp");
        refunded.markRefunded();
        assertThatThrownBy(() -> refunded.markFailed("PortOne status: cancelled")).isInstanceOf(IllegalStateException.class);
        assertThat(refunded.getStatus()).isEqualTo(OrderStatus.REFUNDED);

        Order undelivered = pending();
        undelivered.markPaid("imp");
        assertThatThrownBy(() -> undelivered.markFailed("x")).isInstanceOf(IllegalStateException.class);

        Order p = pending();
        p.markFailed("merchant_uid mismatch");
        assertThat(p.getStatus()).isEqualTo(OrderStatus.FAILED);
    }
}
