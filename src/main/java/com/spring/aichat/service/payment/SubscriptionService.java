package com.spring.aichat.service.payment;

import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.domain.payment.UserSubscription;
import com.spring.aichat.domain.payment.UserSubscriptionRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.service.cache.RedisCacheService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 구독 관리 서비스
 *
 * [구독 활성화 로직]
 * - 기존 활성 구독이 없으면: 새 구독 생성
 * - 기존 활성 구독이 있으면:
 *   - 같은 티어: 만료일 +30일 연장
 *   - 다른 티어: 기존 비활성화 + 새 구독 생성 (업/다운그레이드)
 *
 * [구독 혜택 적용]
 * - User.subscriptionTier: 현재 활성 구독 타입 (에너지 리젠/부스트 비용 계산용)
 * - 구독 활성화/비활성화 시 User.subscriptionTier 동기화
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionService {

    private final UserSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final RedisCacheService cacheService;

    /**
     * 구독 활성화
     */
    @Transactional
    public UserSubscription activateSubscription(User user, SubscriptionType type, String merchantUid) {

        Optional<UserSubscription> existing = subscriptionRepository.findByUser_IdAndActiveTrue(user.getId());

        UserSubscription subscription;

        if (existing.isPresent()) {
            UserSubscription current = existing.get();

            if (current.getType() == type) {
                // 같은 티어: 연장
                current.renew(merchantUid);
                subscription = current;
                log.info("[SUB] Renewed: user={}, type={}, newExpiry={}",
                    user.getUsername(), type, current.getExpiresAt());
            } else {
                // 다른 티어: 기존 비활성화 + 새로 생성
                current.deactivate();
                subscriptionRepository.save(current);

                subscription = UserSubscription.create(user, type, merchantUid);
                subscriptionRepository.save(subscription);
                log.info("[SUB] Upgraded: user={}, from={}, to={}",
                    user.getUsername(), current.getType(), type);
            }
        } else {
            // 신규 구독
            subscription = UserSubscription.create(user, type, merchantUid);
            subscriptionRepository.save(subscription);
            log.info("[SUB] Activated: user={}, type={}", user.getUsername(), type);
        }

        // User 엔티티에 구독 타입 동기화
        user.activateSubscription(type);
        userRepository.save(user);
        cacheService.evictUserProfile(user.getUsername());

        return subscription;
    }

    /**
     * 구독 만료 처리 (스케줄러에서 호출)
     */
    @Transactional
    public void deactivateExpired() {
        int count = subscriptionRepository.deactivateExpiredSubscriptions(java.time.LocalDateTime.now());
        if (count > 0) {
            log.info("[SUB] Deactivated {} expired subscriptions", count);
            // 만료된 유저들의 subscriptionTier를 null로 리셋
            // (벌크 업데이트로 처리)
            userRepository.clearExpiredSubscriptionTiers();
        }
    }

    /**
     * 현재 활성 구독 조회
     */
    public Optional<UserSubscription> getActiveSubscription(Long userId) {
        return subscriptionRepository.findByUser_IdAndActiveTrue(userId)
            .filter(sub -> !sub.isExpired());
    }

    /**
     * 구독자 여부 확인
     */
    public boolean isSubscriber(Long userId) {
        return getActiveSubscription(userId).isPresent();
    }

    /**
     * 특정 티어 이상 구독 여부
     */
    public boolean hasSubscriptionTier(Long userId, SubscriptionType minimumTier) {
        return getActiveSubscription(userId)
            .map(sub -> sub.getType().ordinal() >= minimumTier.ordinal())
            .orElse(false);
    }
}