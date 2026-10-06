package com.spring.aichat.domain.tts;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TtsResponseAudioRepository extends JpaRepository<TtsResponseAudio, Long> {
    @Query("select t.userId from TtsResponseAudio t where t.id = :id")
    Optional<Long> ownerId(Long id);
    interface CancelTarget { Long getId(); String getAttemptId(); }
    @Query("select t.id as id, t.attemptId as attemptId from TtsResponseAudio t where t.roomId = :roomId and (:logId is null or t.logId = :logId)")
    List<CancelTarget> cancelTargets(Long roomId, String logId);
    List<TtsResponseAudio> findTop100ByStatusAndIdGreaterThanOrderByIdAsc(TtsResponseAudio.Status status, Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TtsResponseAudio t where t.userId = :userId and t.roomId = :roomId and t.logId = :logId")
    Optional<TtsResponseAudio> lockResponse(Long userId, Long roomId, String logId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TtsResponseAudio t where t.id = :id")
    Optional<TtsResponseAudio> lockById(Long id);
    Optional<TtsResponseAudio> findByUserIdAndRoomIdAndLogId(Long userId, Long roomId, String logId);
    List<TtsResponseAudio> findTop20ByStatusOrderByCreatedAtAsc(TtsResponseAudio.Status status);
    List<TtsResponseAudio> findByStatusAndUpdatedAtBefore(TtsResponseAudio.Status status, LocalDateTime before);
    List<TtsResponseAudio> findByRoomId(Long roomId);
    List<TtsResponseAudio> findTop20ByCleanupJsonNotOrderByUpdatedAtAsc(String empty);
    List<TtsResponseAudio> findTop100ByCleanupJsonNotAndCleanupAfterBeforeOrderByCleanupAfterAsc(String empty, LocalDateTime now);
}
