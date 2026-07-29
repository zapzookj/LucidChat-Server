package com.spring.aichat.domain.illustration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SceneIllustrationRepository extends JpaRepository<SceneIllustration, Long> {

    /** 방의 마지막 완료 씬 — scene_hash 디덥 비교 + 스킵 시 재사용 원본. */
    Optional<SceneIllustration> findTopByChatRoomIdAndStatusOrderByIdDesc(Long chatRoomId, String status);

    /** 방의 최신 행(상태 무관) — 인플라이트 디덥(동일 해시 진행 중이면 중복 제출 방지). */
    Optional<SceneIllustration> findTopByChatRoomIdOrderByIdDesc(Long chatRoomId);

    /** 씬 네비게이션(A-2) — 방의 턴별 씬 목록. */
    List<SceneIllustration> findByChatRoomIdOrderByIdAsc(Long chatRoomId);
}
