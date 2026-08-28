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
 *
 * ⚠ [D-21 · docs/19_assets/decision_agenda.md D-21 (A)안 · 종원 확정]
 *   아래 두 메서드는 **벌크 UPDATE라 갱신된 유저 목록을 돌려받지 못한다**(반환값은 건수뿐).
 *   따라서 여기서 개별 프로필 캐시를 evict하는 것은 원리적으로 불가능하다.
 *   이 비대칭을 해결한 곳은 스케줄러가 아니라 소비 측이다 —
 *   UserService.getMyInfo가 캐시 히트여도 에너지 잔량만 PK 단건 조회로 덮어쓴다
 *   (UserService.overlayFreshEnergy 참조). 여기에 캐시 무효화를 다시 넣지 말 것:
 *   전체 evict는 로그인 유저 전원의 프로필 캐시를 10분마다 날려 정반대 비용을 만든다.
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