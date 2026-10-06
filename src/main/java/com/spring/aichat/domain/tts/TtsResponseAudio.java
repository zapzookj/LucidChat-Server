package com.spring.aichat.domain.tts;

import com.spring.aichat.domain.user.EnergySplit;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/** A paid response unlock. Provider calls run outside the energy transaction. */
@Entity @Getter @NoArgsConstructor
@Table(name = "tts_response_audio", uniqueConstraints = @UniqueConstraint(name = "uk_tts_response", columnNames = {"user_id", "room_id", "log_id"}))
public class TtsResponseAudio {
    public enum Status { QUEUED, GENERATING, READY, FAILED, CANCELLED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "room_id", nullable = false) private Long roomId;
    @Column(name = "log_id", nullable = false, length = 100) private String logId;
    @Column(nullable = false, length = 64) private String sourceHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(nullable = false, length = 40) private String attemptId;
    @Column(nullable = false, length = 100) private String requestKey;
    @Column(nullable = false, length = 100) private String model;
    @Column(nullable = false, columnDefinition = "TEXT") private String clipsJson;
    @Column(nullable = false, columnDefinition = "TEXT") private String cleanupJson = "[]";
    private LocalDateTime cleanupAfter;
    @Column(nullable = false) private int fromFree;
    @Column(nullable = false) private int fromPaid;
    @Column(nullable = false) private boolean refunded;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;
    @Column(length = 30) private String failureCode;

    public static TtsResponseAudio create(Long userId, Long roomId, String logId, String hash) {
        var job = new TtsResponseAudio();
        job.userId = userId; job.roomId = roomId; job.logId = logId; job.sourceHash = hash;
        job.createdAt = LocalDateTime.now();
        return job;
    }
    public void start(String requestKey, String model, String clipsJson, EnergySplit charge) {
        this.attemptId = UUID.randomUUID().toString(); this.requestKey = requestKey;
        this.model = model; this.clipsJson = clipsJson;
        this.fromFree = charge.fromFree(); this.fromPaid = charge.fromPaid();
        this.refunded = false; this.status = Status.QUEUED; this.failureCode = null; touch();
    }
    public boolean claim(String attempt) {
        if (!matches(attempt, Status.QUEUED)) return false;
        status = Status.GENERATING; touch(); return true;
    }
    public boolean complete(String attempt, String clipsJson) {
        if (!matches(attempt, Status.GENERATING)) return false;
        this.clipsJson = clipsJson; status = Status.READY; touch(); return true;
    }
    public EnergySplit fail(String attempt, boolean cancel, String code) {
        if (!attemptId.equals(attempt) || status == Status.CANCELLED || (!cancel && status == Status.FAILED)) return new EnergySplit(0, 0);
        boolean pending = status == Status.QUEUED || status == Status.GENERATING;
        if (!cancel && !pending) return new EnergySplit(0, 0);
        status = cancel ? Status.CANCELLED : Status.FAILED; failureCode = code; touch();
        if (!pending || refunded) return new EnergySplit(0, 0);
        refunded = true; return new EnergySplit(fromFree, fromPaid);
    }
    public void clearClips() { if (!clipsJson.equals("[]")) { clipsJson = "[]"; touch(); } }
    public void setCleanupJson(String json) { if (!cleanupJson.equals(json)) { cleanupJson = json; cleanupAfter = LocalDateTime.now(); } }
    public void deferCleanup() { cleanupAfter = LocalDateTime.now().plusDays(1); }
    public boolean matches(String attempt, Status expected) { return attemptId.equals(attempt) && status == expected; }
    private void touch() { updatedAt = LocalDateTime.now(); }
}
