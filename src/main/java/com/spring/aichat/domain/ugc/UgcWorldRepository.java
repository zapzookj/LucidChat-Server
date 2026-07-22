package com.spring.aichat.domain.ugc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UgcWorldRepository extends JpaRepository<UgcWorld, Long> {

    List<UgcWorld> findByOwnerUserIdOrderByIdDesc(Long ownerUserId);

    /** 소유권 동시 검증 조회 — 타인 소유는 404 은닉(존재 비노출) 관례. */
    Optional<UgcWorld> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /** [사후 장소 추가] 상한 검사 직렬화용 비관적 락 — 동시 추가의 상한(20) 초과 TOCTOU 방지. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w from UgcWorld w where w.id = :id")
    Optional<UgcWorld> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
