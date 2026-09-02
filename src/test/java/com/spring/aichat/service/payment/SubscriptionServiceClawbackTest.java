package com.spring.aichat.service.payment;

import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.domain.payment.UserSubscription;
import com.spring.aichat.domain.payment.UserSubscriptionRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.exception.BusinessException;
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

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * [배치 3 적대적 리뷰 P1/P2] 구독 활성화·환불 회수의 회차 의미론 — 스냅샷 되돌리기·업그레이드 복원·다운그레이드 거부·이월 원천 거부.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionServiceClawbackTest {

    @Mock private UserSubscriptionRepository subscriptionRepository;
    @Mock private UserRepository userRepository;
    @Mock private RedisCacheService cacheService;

    @InjectMocks private SubscriptionService service;

    private User user;

    @BeforeEach
    void setUp() throws Exception {
        user = User.local("u", "pw", "n", "u@t.com");
        setId(user, 7L);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(subscriptionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static void setId(Object entity, Long id) throws Exception {
        Field f = entity.getClass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }

    private UserSubscription active(SubscriptionType type, String uid, LocalDateTime expiry, long id) throws Exception {
        UserSubscription s = UserSubscription.create(user, type, uid, expiry);
        setId(s, id);
        when(subscriptionRepository.findByUser_IdAndActiveTrueOrderByExpiresAtDesc(7L)).thenReturn(List.of(s));
        when(subscriptionRepository.findByMerchantUid(uid)).thenReturn(Optional.of(s));
        when(subscriptionRepository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    @DisplayName("더블 결제(M1→M2) 후 M2 환불: M1의 30일이 남고 구독은 활성 유지")
    void doublePaymentRefundKeepsPreviousRound() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        UserSubscription s = active(SubscriptionType.LUCID_PASS, "M1", now.plusDays(30), 1L);
        user.activateSubscription(SubscriptionType.LUCID_PASS);

        service.activateSubscription(user, SubscriptionType.LUCID_PASS, "M2");   // renew → 60일, merchantUid=M2
        when(subscriptionRepository.findByMerchantUid("M2")).thenReturn(Optional.of(s));

        service.assertRefundableRound("M2");
        assertThat(service.clawbackRound("M2")).isTrue();

        assertThat(s.isActive()).isTrue();
        assertThat(ChronoUnit.MINUTES.between(now.plusDays(30), s.getExpiresAt())).isBetween(-1L, 1L);
        assertThat(s.getMerchantUid()).isEqualTo("M1");
        assertThat(user.getSubscriptionTier()).isEqualTo(SubscriptionType.LUCID_PASS);
    }

    @Test
    @DisplayName("업그레이드(PASS→MIDNIGHT) 주문 환불: MIDNIGHT 행을 끄고 PASS 행을 복원, 티어도 PASS로")
    void upgradeRefundRestoresPreviousTier() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        UserSubscription pass = active(SubscriptionType.LUCID_PASS, "M1", now.plusDays(10), 1L);
        user.activateSubscription(SubscriptionType.LUCID_PASS);

        UserSubscription midnight = service.activateSubscription(user, SubscriptionType.LUCID_MIDNIGHT_PASS, "M2");
        setId(midnight, 2L);
        assertThat(pass.isActive()).isFalse();
        assertThat(midnight.getCarriedFromId()).isEqualTo(1L);
        assertThat(midnight.getCarriedSeconds()).isGreaterThan(0);
        when(subscriptionRepository.findByMerchantUid("M2")).thenReturn(Optional.of(midnight));
        when(subscriptionRepository.findByUser_IdAndActiveTrueOrderByExpiresAtDesc(7L)).thenReturn(List.of(midnight));

        assertThat(service.clawbackRound("M2")).isTrue();

        assertThat(midnight.isActive()).isFalse();
        assertThat(pass.isActive()).isTrue();
        assertThat(user.getSubscriptionTier()).isEqualTo(SubscriptionType.LUCID_PASS);
    }

    @Test
    @DisplayName("이월 원천(PASS M1) 주문 환불은 사전 거부 — 이월분이 회수 없이 유출되는 차익 경로")
    void carriedFromRoundIsRejectedBeforeCancel() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        UserSubscription pass = active(SubscriptionType.LUCID_PASS, "M1", now.plusDays(10), 1L);
        UserSubscription midnight = service.activateSubscription(user, SubscriptionType.LUCID_MIDNIGHT_PASS, "M2");
        setId(midnight, 2L);
        when(subscriptionRepository.findByUser_IdAndActiveTrueOrderByExpiresAtDesc(7L)).thenReturn(List.of(midnight));

        assertThatThrownBy(() -> service.assertRefundableRound("M1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("상위 티어");
        assertThat(pass.isActive()).isFalse();
    }

    @Test
    @DisplayName("[리뷰 P2] 상위 구독 활성 중 하위 구매(다운그레이드)는 거부 — 관리자 지급(null)은 허용")
    void downgradeRejectedWhileHigherTierActive() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        UserSubscription midnight = active(SubscriptionType.LUCID_MIDNIGHT_PASS, "M1", now.plusDays(20), 1L);

        assertThatThrownBy(() -> service.activateSubscription(user, SubscriptionType.LUCID_PASS, "M2"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("남아 있어요");
        assertThat(midnight.isActive()).isTrue();

        UserSubscription granted = service.activateSubscription(user, SubscriptionType.LUCID_PASS, null);
        assertThat(granted.getType()).isEqualTo(SubscriptionType.LUCID_PASS);
        assertThat(midnight.isActive()).isFalse();
    }

    @Test
    @DisplayName("단일 회차 행의 환불은 종전대로 행 전체 비활성화 + 티어 해제")
    void singleRoundRefundDeactivates() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        UserSubscription s = active(SubscriptionType.LUCID_PASS, "M1", now.plusDays(30), 1L);
        user.activateSubscription(SubscriptionType.LUCID_PASS);
        when(subscriptionRepository.findByUser_IdAndActiveTrueOrderByExpiresAtDesc(7L)).thenReturn(List.of());

        assertThat(service.clawbackRound("M1")).isTrue();

        assertThat(s.isActive()).isFalse();
        assertThat(user.getSubscriptionTier()).isNull();
    }

    @Test
    @DisplayName("주문번호로 못 찾으면 false — RefundService가 REFUND_CLAWBACK_FAILED로 승격")
    void missingRoundReturnsFalse() {
        when(subscriptionRepository.findByMerchantUid("nope")).thenReturn(Optional.empty());
        assertThat(service.clawbackRound("nope")).isFalse();
        verify(userRepository, never()).save(any());
        verify(subscriptionRepository, never()).findById(anyLong());
    }
}
