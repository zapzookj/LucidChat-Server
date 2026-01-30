package com.spring.aichat.service.scheduler;

import com.spring.aichat.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 에너지 회복 스케줄러
 * - 5분마다 전체 유저 energy를 +1 (최대 100)
 */
@Component
@RequiredArgsConstructor
public class EnergyRegenScheduler {

    private final UserRepository userRepository;

    @Scheduled(fixedRate = 5 * 60 * 1000)
    @Transactional
    public void regen() {
        userRepository.regenAllMembersEnergy();
    }
}
