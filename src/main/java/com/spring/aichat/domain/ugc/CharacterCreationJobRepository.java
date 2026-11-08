package com.spring.aichat.domain.ugc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CharacterCreationJobRepository extends JpaRepository<CharacterCreationJob, Long>, CharacterCreationJobLockRepository {

    // findByIdForUpdate — CharacterCreationJobLockRepository(항상 DB 최신 상태로 재적재하는 락 조회, 2026-11-09 핫픽스)

    Optional<CharacterCreationJob> findByIdAndUserId(Long id, Long userId);

    List<CharacterCreationJob> findByUserIdOrderByIdDesc(Long userId);

    /** 유저의 진행 중 잡 (동시 1잡 정책 + 스튜디오 진행 카드). */
    List<CharacterCreationJob> findByUserIdAndStatusInOrderByIdDesc(Long userId, Collection<CreationJobStatus> statuses);

    boolean existsByUserIdAndStatusIn(Long userId, Collection<CreationJobStatus> statuses);

    /** webhook 유실 대비 폴링 폴백 스케줄러 대상. */
    List<CharacterCreationJob> findByStatusIn(Collection<CreationJobStatus> statuses);

    /** *_WAIT TTL 만료 대상. */
    List<CharacterCreationJob> findByStatusInAndExpiresAtBefore(Collection<CreationJobStatus> statuses, LocalDateTime cutoff);

    /** [어드민 프롬프트 인스펙션] 바인딩된 캐릭터 → 원본 잡 역조회. */
    Optional<CharacterCreationJob> findByCharacterId(Long characterId);

    /**
     * [2026-07-21] LLM 구간(Stage0·외형 재구조화) 스테일 스윕 대상 — 외부 잡 id가 없어
     * 폴링 폴백이 못 잡는 구간의 서버 재시작 유실 감지 (N분 무진행).
     */
    List<CharacterCreationJob> findByStatusAndUpdatedAtBefore(CreationJobStatus status, LocalDateTime cutoff);

    /**
     * [D-3.1a/b/d] 캐릭터 잡 통합 스테일 스윕 — 월드 트랙 {@code findByStatusInAndUpdatedAtBefore}와 동형.
     * 종전엔 단수 상태(CONCEPT_PROCESSING)만 조회해 BINDING·POSTPROCESSING·fal 구간 유실이 영구 좀비가 됐다.
     */
    List<CharacterCreationJob> findByStatusInAndUpdatedAtBefore(Collection<CreationJobStatus> statuses, LocalDateTime cutoff);
}
