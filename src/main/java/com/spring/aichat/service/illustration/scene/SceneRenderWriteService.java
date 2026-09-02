package com.spring.aichat.service.illustration.scene;

import com.spring.aichat.domain.illustration.SceneIllustration;
import com.spring.aichat.domain.illustration.SceneIllustrationRepository;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.service.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [2026-07-30 B-2/A-1 재피벗] 씬 렌더 짧은 tx 전용 쓰기 빈 — @Async 폴링 스레드에서의
 * 상태 전이를 격리(자기호출 프록시 회피 패턴, 디오라마 WriteService 관례).
 *
 * <p>[2026-07-31 에픽 B] 수동 요청(MANUAL) 실패 시 에너지 환불을 이 빈에서 정산 —
 * 모든 실패 경로(제출 거부·렌더 예외·타임아웃·RunPod 실패)가 failRender로 수렴하므로
 * 여기 한 곳이 환불의 단일 지점이다(energyRefunded 플래그로 멱등).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneRenderWriteService {

    private final SceneIllustrationRepository repository;
    private final UserRepository userRepository;
    private final RedisCacheService cacheService;

    @Transactional
    public void markSubmitted(Long illustrationId, String providerRequestId) {
        repository.findById(illustrationId).ifPresent(s -> s.markSubmitted(providerRequestId));
    }

    @Transactional
    public void completeRender(Long illustrationId, String publicUrl) {
        repository.findById(illustrationId).ifPresent(s -> s.complete(publicUrl));
    }

    @Transactional
    public void failRender(Long illustrationId, String error) {
        repository.findById(illustrationId).ifPresent(s -> {
            boolean refundable = s.refundableOnFail();
            s.fail(error);
            if (refundable) {
                refundManualCharge(s);
            }
        });
        log.warn("[SCENE-RENDER] 실패 처리 illustrationId={}: {}", illustrationId, error);
    }

    /**
     * 수동 요청 실패 환불 — 유저를 id로 직접 조회한다(V2 보상 경로의 room 경유 조회 버그 재발 방지).
     * 환불 실패는 렌더 실패 마킹을 막지 않는다(로그만 — 정산 이슈는 energy_refunded=false 행으로 추적).
     */
    private void refundManualCharge(SceneIllustration s) {
        try {
            userRepository.findById(s.getRequestedBy()).ifPresentOrElse(user -> {
                // [D-1.2] 행에 영속된 free/paid 분할로 복원 — 총액 환불은 콜백 시점 free가 낮으면
                //   유료분을 free로 흡수해 소각했다(씬 일러는 이 절 최대 단가).
                user.refundEnergy(s.chargedSplit());
                s.markRefunded();
                cacheService.evictUserProfile(user.getUsername());
                log.info("[SCENE-RENDER] 수동 요청 실패 환불: illustrationId={} userId={} split={}",
                    s.getId(), s.getRequestedBy(), s.chargedSplit());
            }, () -> log.error("[SCENE-RENDER] 환불 대상 유저 없음: illustrationId={} userId={}",
                s.getId(), s.getRequestedBy()));
        } catch (Exception e) {
            log.error("[SCENE-RENDER] 환불 실패 illustrationId={}: {}", s.getId(), e.getMessage());
        }
    }
}
