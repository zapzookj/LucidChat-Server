package com.spring.aichat.domain.theater;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.domain.enums.TheaterAct;
import com.spring.aichat.domain.enums.TheaterEndingType;
import com.spring.aichat.domain.enums.WorldId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * [Phase 5.5-Theater] Theater 세션 상태 엔티티
 *
 * ChatRoom과 1:1로 연결. Theater 전용 상태(Act/Chapter, 아바타, 스탯,
 * 현재 히로인, 엔딩, 난입 스냅샷 등) 집약.
 *
 * [설계 원칙]
 * - 1 ChatRoom(THEATER) = 1 TheaterState
 * - 멀티 히로인 상태는 별도 엔티티(TheaterHeroineAffection)에서 관리
 * - 분기/세이브/감독노트는 각각 독립 테이블
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "theater_states",
    indexes = {
        @Index(name = "idx_theater_room", columnList = "room_id", unique = true)
    })
public class TheaterState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, unique = true)
    private ChatRoom room;

    @Enumerated(EnumType.STRING)
    @Column(name = "world_id", nullable = false, length = 50)
    private WorldId worldId;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  서사 진행 상태
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Enumerated(EnumType.STRING)
    @Column(name = "current_act", nullable = false, length = 30)
    private TheaterAct currentAct = TheaterAct.ACT_1_MEETING;

    @Column(name = "current_chapter", nullable = false)
    private int currentChapter = 1;

    @Column(name = "scenes_in_current_chapter", nullable = false)
    private int scenesInCurrentChapter = 0;

    @Column(name = "chapter_target_scenes", nullable = false)
    private int chapterTargetScenes = 30;

    @Column(name = "total_scene_count", nullable = false)
    private long totalSceneCount = 0;

    @Column(name = "current_heroine_id")
    private Long currentHeroineId;

    @Column(name = "current_batch_id", nullable = false)
    private int currentBatchId = 0;

    /**
     * [Phase 5.5 UX Polish · R2] Chapter당 MAJOR 분기를 1회만 발생시키기 위한 플래그.
     *  - false: 이번 Chapter에서 MAJOR가 아직 발생하지 않음
     *  - true:  이번 Chapter의 MAJOR가 이미 사용됨 (그 후 배치들은 MINOR로 처리)
     *  - completeChapter() 시 false로 리셋
     *
     * Boolean(객체)로 둬서 기존 데이터(NULL)에 안전 — 처음엔 null = 미사용 의미.
     */
    @Column(name = "major_branch_done_in_chapter")
    private Boolean majorBranchDoneInChapter = Boolean.FALSE;

    /**
     * [Phase 5.5 UX Polish · R4] 세션 상태 (모델 C-2: 활성 1 + 아카이브 N).
     *
     *  - "ACTIVE":   진행 중인 극 (유저당 1개만)
     *  - "ARCHIVED": 새 극 시작으로 중단되어 아카이브 보존 (resume 가능)
     *  - "ENDED":   엔딩 도달로 완결 (영구 보존, resume 불가)
     *
     *  String으로 둠 — enum class 추가 부담 없이 마이그레이션 친화적.
     *  null/누락은 ACTIVE로 간주 (기존 데이터 호환).
     */
    @Column(name = "session_status", length = 20)
    private String sessionStatus = "ACTIVE";

    /**
     * [Phase 5.5 UX Polish · R4] 마지막 활성/중단 시각 — 아카이브 정렬용.
     *  - ACTIVE → ARCHIVED 전환 시: 그 시각으로 갱신
     *  - ENDED 전환 시: 엔딩 도달 시각으로 갱신
     */
    @Column(name = "session_status_changed_at")
    private LocalDateTime sessionStatusChangedAt;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  아바타 프로필
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "avatar_name", length = 50)
    private String avatarName;

    @Column(name = "avatar_profile_json", columnDefinition = "TEXT")
    private String avatarProfileJson;

    @Column(name = "avatar_persona_text", columnDefinition = "TEXT")
    private String avatarPersonaText;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  아바타 5축 스탯
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "stat_charm", nullable = false)
    private int statCharm = 0;

    @Column(name = "stat_wit", nullable = false)
    private int statWit = 0;

    @Column(name = "stat_boldness", nullable = false)
    private int statBoldness = 0;

    @Column(name = "stat_intellect", nullable = false)
    private int statIntellect = 0;

    @Column(name = "stat_empathy", nullable = false)
    private int statEmpathy = 0;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  인터미션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "intermission_stamina", nullable = false)
    private int intermissionStamina = 5;

    @Column(name = "in_intermission", nullable = false)
    private boolean inIntermission = false;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔딩
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "ending_reached", nullable = false)
    private boolean endingReached = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "ending_type", length = 30)
    private TheaterEndingType endingType;

    @Column(name = "ending_title", length = 200)
    private String endingTitle;

    @Column(name = "ending_main_heroine_id")
    private Long endingMainHeroineId;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  난입 (Intervention)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "intervention_active", nullable = false)
    private boolean interventionActive = false;

    @Column(name = "intervention_checkpoint_json", columnDefinition = "TEXT")
    private String interventionCheckpointJson;

    @Column(name = "intervention_last_log_id", length = 50)
    private String interventionLastLogId;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  재생 설정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "auto_play_enabled", nullable = false)
    private boolean autoPlayEnabled = true;

    @Column(name = "play_speed", length = 20, nullable = false)
    private String playSpeed = "NORMAL";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  메타
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static TheaterState create(ChatRoom room, WorldId worldId, String avatarName,
                                      String avatarProfileJson, String avatarPersonaText,
                                      AvatarStatDistribution initialStats) {
        TheaterState s = new TheaterState();
        s.room = room;
        s.worldId = worldId;
        s.avatarName = avatarName;
        s.avatarProfileJson = avatarProfileJson;
        s.avatarPersonaText = avatarPersonaText;
        if (initialStats != null) {
            s.statCharm = AvatarStat.clamp(initialStats.charm());
            s.statWit = AvatarStat.clamp(initialStats.wit());
            s.statBoldness = AvatarStat.clamp(initialStats.boldness());
            s.statIntellect = AvatarStat.clamp(initialStats.intellect());
            s.statEmpathy = AvatarStat.clamp(initialStats.empathy());
        }
        return s;
    }

    public record AvatarStatDistribution(int charm, int wit, int boldness, int intellect, int empathy) {
        public int total() { return charm + wit + boldness + intellect + empathy; }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  서사 진행 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void assignChapterTargetScenes(int target) {
        this.chapterTargetScenes = Math.max(1, target);
    }

    public void addScenes(int count) {
        this.scenesInCurrentChapter += count;
        this.totalSceneCount += count;
    }

    public boolean isChapterComplete() {
        return this.scenesInCurrentChapter >= this.chapterTargetScenes;
    }

    public void advanceBatch() {
        this.currentBatchId += 1;
    }

    public void setCurrentHeroine(Long heroineId) {
        this.currentHeroineId = heroineId;
    }

    public void completeChapter() {
        this.scenesInCurrentChapter = 0;
        this.currentBatchId = 0;
        this.currentChapter += 1;
        // [R2] 새 Chapter 시작 시 MAJOR 분기 가능 상태로 reset
        this.majorBranchDoneInChapter = Boolean.FALSE;
        // [Phase 6 도그푸딩 #2 결함 B / Patch B-4] currentHeroineId는 의도적으로 보존.
        //   Chapter 전환 직후 첫 batch는 directorEngine이 hint(BatchCache.consumeHeroineHint)
        //   또는 currentHeroineId 우선 정책으로 같은 히로인을 이어가도록 결정한다.
    }

    public void advanceToNextAct() {
        TheaterAct next = this.currentAct.next();
        if (next == null) return;
        this.currentAct = next;
        this.currentChapter = 1;
        this.scenesInCurrentChapter = 0;
        this.currentBatchId = 0;
        this.intermissionStamina = 5;
        // [R2] Act 전환 시도 동일 reset
        this.majorBranchDoneInChapter = Boolean.FALSE;
        // [Phase 6 도그푸딩 #2 결함 B / Patch B-4] currentHeroineId 보존.
        //   ACT_4_RESOLUTION 진입 시점에 confirmMainHeroineIfApplicable이 메인 히로인을
        //   확정하면 decideNextSpeakerHeroine.pickMainHeroine이 그 히로인을 화자로 선택하게 된다.
        //   currentHeroineId를 reset해 버리면 Patch B-5 (c)의 자연 전환 컨텍스트도 활용 못 함.
    }

    /**
     * [R2] MAJOR 분기 발동 마킹 — DirectorEngine.decideBranchAfterBatch가
     *      MAJOR를 결정한 직후 호출되어, 같은 Chapter에서 두 번 발동 차단.
     */
    public void markMajorBranchDoneInChapter() {
        this.majorBranchDoneInChapter = Boolean.TRUE;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5 UX Polish · R4] 세션 상태 전이
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public boolean isActive() {
        return sessionStatus == null || "ACTIVE".equals(sessionStatus);
    }

    public boolean isArchived() {
        return "ARCHIVED".equals(sessionStatus);
    }

    public boolean isEnded() {
        return "ENDED".equals(sessionStatus);
    }

    /**
     * 새 극 시작으로 인한 중단 — 아카이브로 보존 (resume 가능).
     */
    public void archiveAsInterrupted() {
        this.sessionStatus = "ARCHIVED";
        this.sessionStatusChangedAt = LocalDateTime.now();
    }

    /**
     * 엔딩 도달 — 영구 완결 상태 (resume 불가).
     */
    public void markEnded() {
        this.sessionStatus = "ENDED";
        this.sessionStatusChangedAt = LocalDateTime.now();
    }

    /**
     * 아카이브된 극을 다시 활성화 (resume).
     */
    public void resumeFromArchive() {
        this.sessionStatus = "ACTIVE";
        this.sessionStatusChangedAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스탯 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public Map<AvatarStat, Integer> snapshotStats() {
        Map<AvatarStat, Integer> map = new EnumMap<>(AvatarStat.class);
        map.put(AvatarStat.CHARM, statCharm);
        map.put(AvatarStat.WIT, statWit);
        map.put(AvatarStat.BOLDNESS, statBoldness);
        map.put(AvatarStat.INTELLECT, statIntellect);
        map.put(AvatarStat.EMPATHY, statEmpathy);
        return map;
    }

    public int getStat(AvatarStat stat) {
        return switch (stat) {
            case CHARM -> statCharm;
            case WIT -> statWit;
            case BOLDNESS -> statBoldness;
            case INTELLECT -> statIntellect;
            case EMPATHY -> statEmpathy;
        };
    }

    public void applyStatChange(AvatarStat stat, int delta) {
        int updated = getStat(stat) + delta;
        int clamped = AvatarStat.clamp(updated);
        switch (stat) {
            case CHARM -> this.statCharm = clamped;
            case WIT -> this.statWit = clamped;
            case BOLDNESS -> this.statBoldness = clamped;
            case INTELLECT -> this.statIntellect = clamped;
            case EMPATHY -> this.statEmpathy = clamped;
        }
    }

    public AvatarStat dominantStat() {
        AvatarStat best = AvatarStat.CHARM;
        int bestValue = statCharm;
        if (statWit > bestValue) { best = AvatarStat.WIT; bestValue = statWit; }
        if (statBoldness > bestValue) { best = AvatarStat.BOLDNESS; bestValue = statBoldness; }
        if (statIntellect > bestValue) { best = AvatarStat.INTELLECT; bestValue = statIntellect; }
        if (statEmpathy > bestValue) { best = AvatarStat.EMPATHY; }
        return best;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  인터미션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void startIntermission() {
        this.inIntermission = true;
        this.intermissionStamina = 5;
    }

    public void consumeIntermissionStamina() {
        if (this.intermissionStamina > 0) this.intermissionStamina -= 1;
    }

    public void endIntermission() {
        this.inIntermission = false;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  난입
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void enterIntervention(String checkpointJson) {
        this.interventionActive = true;
        this.interventionCheckpointJson = checkpointJson;
    }

    public void recordInterventionLog(String logId) {
        this.interventionLastLogId = logId;
    }

    public void exitIntervention() {
        this.interventionActive = false;
        this.interventionCheckpointJson = null;
        this.interventionLastLogId = null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔딩
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void markEndingReached(TheaterEndingType type, String title, Long mainHeroineId) {
        this.endingReached = true;
        this.endingType = type;
        this.endingTitle = title;
        this.endingMainHeroineId = mainHeroineId;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  설정 / 프로필 업데이트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void updatePlaySettings(boolean autoPlayEnabled, String playSpeed) {
        this.autoPlayEnabled = autoPlayEnabled;
        if (playSpeed != null && !playSpeed.isBlank()) {
            this.playSpeed = playSpeed.toUpperCase().trim();
        }
    }

    public void updateAvatarProfile(String avatarName, String avatarProfileJson, String avatarPersonaText) {
        if (avatarName != null && !avatarName.isBlank()) this.avatarName = avatarName.trim();
        if (avatarProfileJson != null) this.avatarProfileJson = avatarProfileJson;
        if (avatarPersonaText != null) this.avatarPersonaText = avatarPersonaText;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Theater] 세이브 슬롯으로부터 상태 복원
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 로드 시 TheaterSaveLoadService에서 호출.
     * 엔딩/인터미션/난입 플래그는 모두 리셋된다.
     */
    public void restoreFromSnapshot(TheaterAct act, int chapter, int scenesInChapter,
                                    int chapterTarget, long totalScenes,
                                    Long currentHeroineId, int batchId,
                                    int charm, int wit, int boldness, int intellect, int empathy,
                                    int intermissionStamina) {
        this.currentAct = act;
        this.currentChapter = chapter;
        this.scenesInCurrentChapter = scenesInChapter;
        this.chapterTargetScenes = chapterTarget;
        this.totalSceneCount = totalScenes;
        this.currentHeroineId = currentHeroineId;
        this.currentBatchId = batchId;
        this.statCharm = AvatarStat.clamp(charm);
        this.statWit = AvatarStat.clamp(wit);
        this.statBoldness = AvatarStat.clamp(boldness);
        this.statIntellect = AvatarStat.clamp(intellect);
        this.statEmpathy = AvatarStat.clamp(empathy);
        this.intermissionStamina = Math.max(0, Math.min(5, intermissionStamina));
        this.inIntermission = false;
        this.interventionActive = false;
        this.interventionCheckpointJson = null;
        this.interventionLastLogId = null;
        this.endingReached = false;
        this.endingType = null;
        this.endingTitle = null;
        this.endingMainHeroineId = null;
    }
}