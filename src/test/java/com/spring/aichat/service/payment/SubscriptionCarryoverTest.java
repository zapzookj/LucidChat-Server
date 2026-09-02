package com.spring.aichat.service.payment;

import com.spring.aichat.domain.enums.SubscriptionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [안건 5 (b) · decisions_confirmed §A #5] 티어 업그레이드 시 잔여 기간의 금액 비례 이월 산식.
 */
class SubscriptionCarryoverTest {

    @Test
    @DisplayName("잔여 10일 LUCID_PASS(14,900) → MIDNIGHT(24,900): 10 × 14,900/24,900 ≈ 5.98일 이월")
    void upgradeProrates() {
        Duration carried = SubscriptionService.carryover(
            Duration.ofDays(10), SubscriptionType.LUCID_PASS, SubscriptionType.LUCID_MIDNIGHT_PASS);

        long expectedSeconds = Duration.ofDays(10).getSeconds() * 14900 / 24900;
        assertThat(carried.getSeconds()).isEqualTo(expectedSeconds);
        assertThat(carried).isLessThan(Duration.ofDays(6)).isGreaterThan(Duration.ofDays(5));
    }

    @Test
    @DisplayName("다운그레이드·동일가·잔여 없음은 이월 0 (종원 확정: 다운그레이드는 범위 밖, 현행 유지)")
    void noCarryoverForDowngradeSamePriceOrNoRemaining() {
        assertThat(SubscriptionService.carryover(Duration.ofDays(10),
            SubscriptionType.LUCID_MIDNIGHT_PASS, SubscriptionType.LUCID_PASS)).isEqualTo(Duration.ZERO);
        assertThat(SubscriptionService.carryover(Duration.ofDays(10),
            SubscriptionType.LUCID_PASS, SubscriptionType.LUCID_PASS)).isEqualTo(Duration.ZERO);
        assertThat(SubscriptionService.carryover(Duration.ofDays(-3),
            SubscriptionType.LUCID_PASS, SubscriptionType.LUCID_MIDNIGHT_PASS)).isEqualTo(Duration.ZERO);
        assertThat(SubscriptionService.carryover(null,
            SubscriptionType.LUCID_PASS, SubscriptionType.LUCID_MIDNIGHT_PASS)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("(c) '잔여일 그대로' 차익 경로 차단 — 이월분은 항상 잔여보다 짧다")
    void carryoverNeverExceedsRemaining() {
        Duration remaining = Duration.ofDays(29);
        Duration carried = SubscriptionService.carryover(
            remaining, SubscriptionType.LUCID_PASS, SubscriptionType.LUCID_MIDNIGHT_PASS);
        assertThat(carried).isLessThan(remaining);
    }
}
