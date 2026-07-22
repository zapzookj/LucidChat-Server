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

public interface CharacterCreationJobRepository extends JpaRepository<CharacterCreationJob, Long> {

    /**
     * 잡 단위 직렬화용 비관적 락 — 감정 14종 webhook/폴링 경합의 lost update 방지.
     * (결제 verifyAndDeliver의 merchantUid 락과 동일 패턴)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from CharacterCreationJob j where j.id = :id")
    Optional<CharacterCreationJob> findByIdForUpdate(@Param("id") Long id);

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
}
