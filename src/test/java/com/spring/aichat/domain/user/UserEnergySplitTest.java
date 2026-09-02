package com.spring.aichat.domain.user;

import com.spring.aichat.exception.InsufficientEnergyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [D-1.1 · docs/17_assets/defect_register.md §D-1] 에너지 분할 환불 계약 테스트.
 *
 * <p>레지스터 수정안 5)의 2방향 검증: ① free=0/paid=N에서 환불 후 paid 정확 복원
 * ② free만 쓴 경우 paid 미증가(승급 파밍 차단). 여기에 상한 초과분 폐기(결정 (a))와
 * 영속 복원({@link EnergySplit#of})의 클램프를 더한다.
 */
class UserEnergySplitTest {

    /** 비구독 유저: free 30 / paid 0으로 태어난다. */
    private static User freshUser() {
        return User.local("u", "pw", "nick", "u@test.com");
    }

    /** free를 전부 소진한 뒤 paid만 남긴 유저. */
    private static User paidOnlyUser(int paid) {
        User u = freshUser();
        u.consumeEnergy(30);
        u.chargePaidEnergy(paid);
        assertThat(u.getFreeEnergy()).isZero();
        assertThat(u.getPaidEnergy()).isEqualTo(paid);
        return u;
    }

    // ━━━━━━━━━━ consumeEnergy 분할 산출 ━━━━━━━━━━

    @Test
    @DisplayName("free가 충분하면 전액 free에서 나간다")
    void consume_allFromFree() {
        User u = freshUser();
        EnergySplit s = u.consumeEnergy(10);
        assertThat(s).isEqualTo(new EnergySplit(10, 0));
        assertThat(u.getFreeEnergy()).isEqualTo(20);
        assertThat(u.getPaidEnergy()).isZero();
    }

    @Test
    @DisplayName("free가 모자라면 free를 비우고 나머지만 paid에서 나간다")
    void consume_mixed() {
        User u = freshUser();
        u.consumeEnergy(27);          // free 3 남김
        u.chargePaidEnergy(10);
        EnergySplit s = u.consumeEnergy(5);
        assertThat(s).isEqualTo(new EnergySplit(3, 2));
        assertThat(u.getFreeEnergy()).isZero();
        assertThat(u.getPaidEnergy()).isEqualTo(8);
    }

    @Test
    @DisplayName("free=0이면 전액 paid에서 나간다")
    void consume_allFromPaid() {
        User u = paidOnlyUser(50);
        EnergySplit s = u.consumeEnergy(10);
        assertThat(s).isEqualTo(new EnergySplit(0, 10));
        assertThat(u.getPaidEnergy()).isEqualTo(40);
    }

    @Test
    @DisplayName("[리뷰 P3] free가 음수인 손상 행에서도 IAE로 막히지 않는다 — 그 요청은 paid에서 전액, free는 그대로")
    void consume_negativeFreeRowDoesNotHardBlock() throws Exception {
        User u = freshUser();
        u.chargePaidEnergy(20);
        var f = User.class.getDeclaredField("freeEnergy");
        f.setAccessible(true);
        f.setInt(u, -3);                        // 과거 lost-update 잔재 시뮬레이션

        EnergySplit s = u.consumeEnergy(5);

        assertThat(s).isEqualTo(new EnergySplit(0, 5));
        assertThat(u.getFreeEnergy()).isEqualTo(-3);   // regen이 치유할 때까지 손대지 않는다
        assertThat(u.getPaidEnergy()).isEqualTo(15);
    }

    @Test
    @DisplayName("부족·음수는 기존 계약 그대로 예외 (차감 전)")
    void consume_guards() {
        User u = freshUser();
        assertThatThrownBy(() -> u.consumeEnergy(31)).isInstanceOf(InsufficientEnergyException.class);
        assertThatThrownBy(() -> u.consumeEnergy(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(u.getEnergy()).isEqualTo(30);
    }

    // ━━━━━━━━━━ refundEnergy — 정확한 역연산 ━━━━━━━━━━

    @Test
    @DisplayName("① free=0/paid=50에서 10 차감 후 환불하면 paid가 50으로 정확히 복원된다 (구 코드는 free 10/paid 40 → 유료 10 소각)")
    void refund_restoresPaidExactly() {
        User u = paidOnlyUser(50);
        EnergySplit s = u.consumeEnergy(10);

        u.refundEnergy(s);

        assertThat(u.getPaidEnergy()).isEqualTo(50);
        assertThat(u.getFreeEnergy()).isZero();
    }

    @Test
    @DisplayName("② free만 쓴 차감을 환불해도 paid는 늘지 않는다 (free→paid 승급 파밍 차단)")
    void refund_neverPromotesFreeToPaid() {
        User u = freshUser();
        EnergySplit s = u.consumeEnergy(10);

        u.refundEnergy(s);

        assertThat(u.getFreeEnergy()).isEqualTo(30);
        assertThat(u.getPaidEnergy()).isZero();
    }

    @Test
    @DisplayName("혼합 차감은 각 버킷으로 그대로 돌아간다")
    void refund_mixedRoundTrip() {
        User u = freshUser();
        u.consumeEnergy(27);
        u.chargePaidEnergy(10);
        EnergySplit s = u.consumeEnergy(5);     // (3, 2)

        u.refundEnergy(s);

        assertThat(u.getFreeEnergy()).isEqualTo(3);
        assertThat(u.getPaidEnergy()).isEqualTo(10);
    }

    @Test
    @DisplayName("지연 환불 시 free가 이미 상한이면 free 초과분은 버린다 — regen이 이미 채운 분량, paid로 승급 금지 (결정 (a))")
    void refund_discardsFreeOverflowAtCap() {
        User u = freshUser();
        EnergySplit s = u.consumeEnergy(10);    // free 20
        u.regenEnergy(10);                      // 스케줄러가 그 사이 상한(30)까지 회복

        u.refundEnergy(s);

        assertThat(u.getFreeEnergy()).isEqualTo(30);   // 상한 초과 순증 없음
        assertThat(u.getPaidEnergy()).isZero();        // free분이 paid로 세탁되지 않음
    }

    @Test
    @DisplayName("상한이어도 paid 분할분은 온전히 돌아온다")
    void refund_paidPortionSurvivesFreeCap() {
        User u = freshUser();
        u.consumeEnergy(28);                    // free 2
        u.chargePaidEnergy(10);
        EnergySplit s = u.consumeEnergy(6);     // (2, 4) → free 0 / paid 6
        u.regenEnergy(30);                      // free 상한 회복

        u.refundEnergy(s);

        assertThat(u.getFreeEnergy()).isEqualTo(30);
        assertThat(u.getPaidEnergy()).isEqualTo(10);
    }

    @Test
    @DisplayName("null·ZERO 환불은 no-op")
    void refund_zeroAndNullAreNoop() {
        User u = freshUser();
        u.consumeEnergy(5);
        u.refundEnergy(null);
        u.refundEnergy(EnergySplit.ZERO);
        assertThat(u.getEnergy()).isEqualTo(25);
    }

    // ━━━━━━━━━━ EnergySplit 값 객체 ━━━━━━━━━━

    @Test
    @DisplayName("of(total, paid): 구 행(paid=0)은 전액 free, paid>total 손상 행은 total까지만 paid")
    void of_clampsPersistedValues() {
        assertThat(EnergySplit.of(10, 0)).isEqualTo(new EnergySplit(10, 0));
        assertThat(EnergySplit.of(10, 4)).isEqualTo(new EnergySplit(6, 4));
        assertThat(EnergySplit.of(10, 15)).isEqualTo(new EnergySplit(0, 10));
        assertThat(EnergySplit.of(0, 3)).isEqualTo(EnergySplit.ZERO);
        assertThat(EnergySplit.of(-2, -1)).isEqualTo(EnergySplit.ZERO);
    }

    @Test
    @DisplayName("plus는 버킷별로 누산하고 음수 분할은 생성 자체를 거부한다")
    void plus_andNegativeGuard() {
        assertThat(new EnergySplit(4, 0).plus(new EnergySplit(3, 5)).plus(null))
            .isEqualTo(new EnergySplit(7, 5));
        assertThat(new EnergySplit(7, 5).total()).isEqualTo(12);
        assertThatThrownBy(() -> new EnergySplit(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnergySplit(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
