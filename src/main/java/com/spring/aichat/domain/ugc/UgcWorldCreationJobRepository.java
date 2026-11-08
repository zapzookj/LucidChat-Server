package com.spring.aichat.domain.ugc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UgcWorldCreationJobRepository extends JpaRepository<UgcWorldCreationJob, Long>, UgcWorldCreationJobLockRepository {

    // findByIdForUpdate — UgcWorldCreationJobLockRepository(항상 DB 최신 상태로 재적재하는 락 조회, 2026-11-09 핫픽스)

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
