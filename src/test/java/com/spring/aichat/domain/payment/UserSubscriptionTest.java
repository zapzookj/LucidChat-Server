package com.spring.aichat.domain.payment;

import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [D-4.1 · D-4.2 · docs/17_assets/defect_register.md §D-4] 구독 갱신 계약 — 잔여 기간 보존, 최신 회차 술어.
 */
class UserSubscriptionTest {

    private static User user() {
        return User.local("u", "pw", "nick", "u@test.com");
    }

    @Test
    @DisplayName("[D-4.1] 잔여 10일에 재결제하면 40일 — 종전 now+30은 10일을 소각했다")
    void renewPreservesRemaining() {
        LocalDateTime now = LocalDateTime.now();
        UserSubscription sub = UserSubscription.create(user(), SubscriptionType.LUCID_PASS, "round-1", now.plusDays(10));

        LocalDateTime before = sub.renew("round-2");

        assertThat(before).isEqualTo(now.plusDays(10));
        assertThat(ChronoUnit.MINUTES.between(now.plusDays(40), sub.getExpiresAt())).isBetween(-1L, 1L);
        assertThat(sub.isActive()).isTrue();
        assertThat(sub.getMerchantUid()).isEqualTo("round-2");
    }

    @Test
    @DisplayName("[D-4.1] 만료 후 재가입은 now 기준 30일 — 지난 기간이 소급되지 않는다")
    void renewAfterExpiryStartsFromNow() {
        LocalDateTime now = LocalDateTime.now();
        UserSubscription sub = UserSubscription.create(user(), SubscriptionType.LUCID_PASS, "round-1", now.minusDays(5));
        sub.deactivate();

        sub.renew("round-2");

        assertThat(ChronoUnit.MINUTES.between(now.plusDays(30), sub.getExpiresAt())).isBetween(-1L, 1L);
        assertThat(sub.isActive()).isTrue();
    }

    @Test
    @DisplayName("[D-4.2 · 안건 6 (c)] 갱신 후에는 최신 회차 주문번호만 이 행을 가리킨다 — 과거 회차는 회수 불가")
    void currentRoundPredicate() {
        UserSubscription sub = UserSubscription.create(user(), SubscriptionType.LUCID_PASS, "round-1",
            LocalDateTime.now().plusDays(30));
        assertThat(sub.isCurrentRound("round-1")).isTrue();

        sub.renew("round-2");

        assertThat(sub.isCurrentRound("round-2")).isTrue();
        assertThat(sub.isCurrentRound("round-1")).isFalse();
        assertThat(sub.isCurrentRound(null)).isFalse();
    }

    @Test
    @DisplayName("[리뷰 P1] 재결제는 직전 (만료, 주문번호)를 스냅샷하고, revertLatestRound가 그 회차분만 되돌린다 — 더블 결제 환불 시 이전 회차 보존")
    void renewSnapshotsAndRevertsLatestRound() {
        LocalDateTime now = LocalDateTime.now();
        UserSubscription sub = UserSubscription.create(user(), SubscriptionType.LUCID_PASS, "M1", now.plusDays(30));
        assertThat(sub.getPrevExpiresAt()).isNull();

        sub.renew("M2");   // 실수 더블 결제 → 60일, merchantUid=M2
        assertThat(ChronoUnit.MINUTES.between(now.plusDays(60), sub.getExpiresAt())).isBetween(-1L, 1L);
        assertThat(sub.getPrevMerchantUid()).isEqualTo("M1");
        assertThat(sub.getPrevExpiresAt()).isEqualTo(now.plusDays(30));

        assertThat(sub.revertLatestRound()).isTrue();      // M2 환불
        assertThat(sub.getExpiresAt()).isEqualTo(now.plusDays(30));   // M1의 30일은 남는다
        assertThat(sub.getMerchantUid()).isEqualTo("M1");
        assertThat(sub.getPrevExpiresAt()).isNull();
        assertThat(sub.revertLatestRound()).isFalse();     // 스냅샷 소진 — 단일 회차: 호출측이 행 전체 비활성화
    }

    @Test
    @DisplayName("[리뷰 P2] 관리자 지급(merchantUid=null)은 연장만 — 유료 회차 키·스냅샷을 덮지 않는다")
    void adminGrantDoesNotOverwritePaidRound() {
        LocalDateTime now = LocalDateTime.now();
        UserSubscription sub = UserSubscription.create(user(), SubscriptionType.LUCID_PASS, "M1", now.plusDays(10));

        sub.renew(null);

        assertThat(sub.getMerchantUid()).isEqualTo("M1");
        assertThat(sub.getPrevExpiresAt()).isNull();
        assertThat(ChronoUnit.MINUTES.between(now.plusDays(40), sub.getExpiresAt())).isBetween(-1L, 1L);
        assertThat(sub.isCurrentRound("M1")).isTrue();     // M1 환불이 '과거 회차'로 거부되지 않는다
    }

    @Test
    @DisplayName("create는 호출측이 확정한 만료 시각을 그대로 쓴다 (업그레이드 이월분 반영 경로)")
    void createUsesExplicitExpiry() {
        LocalDateTime expiry = LocalDateTime.now().plusDays(36);
        UserSubscription sub = UserSubscription.create(user(), SubscriptionType.LUCID_MIDNIGHT_PASS, "m-1", expiry);
        assertThat(sub.getExpiresAt()).isEqualTo(expiry);
        assertThat(sub.getType()).isEqualTo(SubscriptionType.LUCID_MIDNIGHT_PASS);
        assertThat(sub.isExpired()).isFalse();
    }
}
