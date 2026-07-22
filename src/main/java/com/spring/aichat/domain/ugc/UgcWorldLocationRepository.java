package com.spring.aichat.domain.ugc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UgcWorldLocationRepository extends JpaRepository<UgcWorldLocation, Long> {

    /** 채팅 장소 풀·프롬프트 주입용 (WorldLocationRepository 대응 시그니처). */
    List<UgcWorldLocation> findByUgcWorldIdAndActiveTrueOrderByDisplayOrderAsc(Long ugcWorldId);

    List<UgcWorldLocation> findByUgcWorldIdOrderByDisplayOrderAsc(Long ugcWorldId);

    /** [사후 장소 추가] 재시도·삭제 대상 조회. */
    java.util.Optional<UgcWorldLocation> findByUgcWorldIdAndLocationKey(Long ugcWorldId, String locationKey);
}
