package com.spring.aichat.service.illustration.scene;

import com.spring.aichat.domain.illustration.SceneIllustration;
import com.spring.aichat.domain.illustration.SceneIllustrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [2026-07-30 B-2/A-1 재피벗] 씬 렌더 짧은 tx 전용 쓰기 빈 — @Async 폴링 스레드에서의
 * 상태 전이를 격리(자기호출 프록시 회피 패턴, 디오라마 WriteService 관례).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneRenderWriteService {

    private final SceneIllustrationRepository repository;

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
        repository.findById(illustrationId).ifPresent(s -> s.fail(error));
        log.warn("[SCENE-RENDER] 실패 처리 illustrationId={}: {}", illustrationId, error);
    }
}
