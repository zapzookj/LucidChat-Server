package com.spring.aichat.service.scheduler;

import com.spring.aichat.service.payment.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 구독 만료 스케줄러
 *
 * [실행 주기] 1시간마다
 * - 만료된 활성 구독을 비활성화
 * - 해당 유저의 subscriptionTier를 null로 초기화
 * - freeEnergy가 30 초과 시 30으로 클램핑
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionExpirationScheduler {

    private final SubscriptionService subscriptionService;

    @Scheduled(fixedRate = 60 * 60 * 1000)
    public void deactivateExpiredSubscriptions() {
        try {
            subscriptionService.deactivateExpired();
        } catch (Exception e) {
            log.error("[SUB_SCHEDULER] Failed to deactivate expired subscriptions", e);
        }
    }
}