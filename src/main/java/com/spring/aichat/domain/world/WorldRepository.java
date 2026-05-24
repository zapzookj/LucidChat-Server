package com.spring.aichat.domain.world;

import com.spring.aichat.domain.enums.WorldId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * World 마스터 Repository.
 *
 * <p>[패키지 이동 — V2 Story]
 * 기존: {@code com.spring.aichat.domain.theater}
 * V2:   {@code com.spring.aichat.domain.world}
 * Theater 코드의 import 경로 업데이트 필요 (Layer 2에서 일괄 처리).
 */
public interface WorldRepository extends JpaRepository<World, WorldId> {

    /**
     * 활성화된 World만 정렬 순서대로 반환.
     * 로비의 World 카드 섹션 (V2 Story) + Theater Doorway 양쪽에서 사용.
     */
    List<World> findByActiveTrueOrderByDisplayOrderAsc();
}