package com.spring.aichat.domain.ugc;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UgcWorldCreationJobRepository extends JpaRepository<UgcWorldCreationJob, Long> {

    /**
     * 잡 단위 직렬화용 비관적 락 — 장소 배경 병렬 완료(fal 콜백) 경합의 lost update 방지.
     * ({@link CharacterCreationJobRepository#findByIdForUpdate}와 동일 패턴)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from UgcWorldCreationJob j where j.id = :id")
    Optional<UgcWorldCreationJob> findByIdForUpdate(@Param("id") Long id);

    Optional<UgcWorldCreationJob> findByIdAndUserId(Long id, Long userId);

    /**
     * 유저의 진행 중 월드 잡 (동시 1잡 정책 + 스튜디오 진행 카드).
     * 캐릭터 잡과는 <b>독립 카운트</b> — 감정 파생 대기 중 월드 빌더 병행이 설계 전제.
     */
    List<UgcWorldCreationJob> findByUserIdAndStatusInOrderByIdDesc(Long userId, Collection<WorldCreationJobStatus> statuses);

    boolean existsByUserIdAndStatusIn(Long userId, Collection<WorldCreationJobStatus> statuses);

    /** *_WAIT TTL 만료 대상. */
    List<UgcWorldCreationJob> findByStatusInAndExpiresAtBefore(Collection<WorldCreationJobStatus> statuses, LocalDateTime cutoff);

    /**
     * 스테일 스윕 대상 — fal 전용 파이프라인이라 웹훅/폴링 폴백이 없어, 서버 재시작으로
     * in-flight 콜백이 유실된 PROCESSING 잡을 "N분 무진행"으로 감지한다.
     */
    List<UgcWorldCreationJob> findByStatusInAndUpdatedAtBefore(Collection<WorldCreationJobStatus> statuses, LocalDateTime cutoff);

    /** [어드민 월드 섹션] 확정 월드 → 원본 잡 역조회. */
    Optional<UgcWorldCreationJob> findByUgcWorldId(Long ugcWorldId);
}
