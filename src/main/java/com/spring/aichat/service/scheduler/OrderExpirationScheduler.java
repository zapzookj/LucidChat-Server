package com.spring.aichat.service.scheduler;

import com.spring.aichat.domain.payment.OrderRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Phase 5: PENDING 주문 만료 스케줄러
 * - 30분마다 실행
 * - 생성 후 30분 경과한 PENDING 주문을 EXPIRED로 전환
 * - 결제를 시작했지만 완료하지 않은 주문 정리
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OrderExpirationScheduler {

    private final OrderRepository orderRepository;

    @Scheduled(fixedRate = 30 * 60 * 1000)
    @Transactional
    public void expireStaleOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        int expired = orderRepository.expireOldPendingOrders(cutoff);
        if (expired > 0) {
            log.info("[ORDER] Expired {} stale PENDING orders", expired);
        }
    }
}