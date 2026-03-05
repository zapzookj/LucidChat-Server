package com.spring.aichat.service.scheduler;

import com.spring.aichat.domain.user.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 5 BM: 에너지 회복 스케줄러
 *
 * [비구독자]
 * - 10분마다 freeEnergy +1 (최대 30)
 *
 * [구독자 (루시드 패스 / 미드나잇 패스)]
 * - 5분마다 freeEnergy +1 (최대 100)
 * - 구독 핵심 혜택: 회복 속도 2배 + 최대 보유량 3.3배
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EnergyRegenScheduler {

    private final UserRepository userRepository;

    /** 비구독자: 10분마다 +1, max 30 */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    @Transactional
    public void regenFreeUsers() {
        int count = userRepository.regenFreeUserEnergy();
        if (count > 0) {
            log.debug("[REGEN] Free users: {} users recharged", count);
        }
    }

    /** 구독자: 5분마다 +1, max 100 */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    @Transactional
    public void regenSubscribers() {
        int count = userRepository.regenSubscriberEnergy();
        if (count > 0) {
            log.debug("[REGEN] Subscribers: {} users recharged", count);
        }
    }
}