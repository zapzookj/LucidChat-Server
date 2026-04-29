package com.spring.aichat.domain.theater;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.spring.aichat.domain.chat.ChatRoom;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [Phase 5.5-Theater] 감독 노트
 *
 * Theater 모드의 메타 레이어. 유저는 감독이므로 메모를 남길 수 있고,
 * 시스템은 주요 모먼트(스탯 급변, 분기 이벤트 등)를 자동으로 캡처한다.
 *
 * [노트 타입]
 * - MANUAL:        유저가 직접 작성 (자유 텍스트)
 * - AUTO_MOMENT:   스탯 급변, 호감도 역전 등 자동 캡처
 * - BRANCH_TAKEN:  중요 분기 선택 시 자동 기록
 * - INTERMISSION:  인터미션 대성공 등 자동 기록
 * - CHAPTER_END:   Chapter 종료 리포트 스냅샷
 * - INTERVENTION:  난입 기록
 *
 * [엔딩 크레딧 활용]
 * 엔딩 크레딧에서 "당신의 작품" 섹션에 이 노트들을 시간순으로 나열.
 * 자동 생성된 일러스트(UserIllustration)와 교차 배치하여 "내가 만든 이야기" 감각 극대화.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "theater_director_notes",
    indexes = {
        @Index(name = "idx_theater_note_room", columnList = "room_id"),
        @Index(name = "idx_theater_note_type", columnList = "note_type"),
        @Index(name = "idx_theater_note_created", columnList = "room_id, created_at")
    })
public class TheaterDirectorNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    /** 노트 유형 */
    @Column(name = "note_type", nullable = false, length = 30)
    private String noteType;

    /** 노트 본문 */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 관련 Scene/Chapter/Log 참조 ID (MongoDB log ID 또는 Scene 시퀀스) */
    @Column(name = "scene_reference_id", length = 100)
    private String sceneReferenceId;

    /** 관련 Act/Chapter (정렬 보조용) */
    @Column(name = "act_number")
    private Integer actNumber;

    @Column(name = "chapter_number")
    private Integer chapterNumber;

    /** 관련 히로인 ID (nullable) */
    @Column(name = "related_heroine_id")
    private Long relatedHeroineId;

    /** 관련 일러스트 URL (UserIllustration 생성 시 링크) */
    @Column(name = "related_illustration_url", length = 500)
    private String relatedIllustrationUrl;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5 UX Polish · R3] 감독 명령어 메타
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  noteType="MANUAL"인 노트 중 일부는 "감독 명령어"로 발동되었음.
    //  명령어 발동 = 다음 1배치에 환경적 영향. 일회성. 활성 큐는 Redis에서 관리.
    //  여기 필드들은 영구 기록(이전 명령어 기록 UI / 통계 / 안전 감사)을 위한 것.

    /**
     * 명령어 분류 (LLM 또는 룰 기반 분류기가 결정).
     *  - "ENVIRONMENT" : 비/햇빛/바람 등 환경 변화
     *  - "NPC"         : 지나가는 행인, 우연한 등장
     *  - "SOUND"       : 음악, 전화벨, 발소리
     *  - "PROP"        : 사물, 풍경 변화
     *  - "OTHER"       : 기타 환경적 변화 (분류기가 ALLOWED로 판단)
     *  - null          : MANUAL 노트지만 명령어로 발동되지 않은 일반 메모
     */
    @Column(name = "command_type", length = 30)
    private String commandType;

    /**
     * 검증 결과 — 명령어로 시도되었지만 거부됐을 수 있음.
     * 거부된 명령어도 기록 보관 → 유저 학습 자료.
     *  - "ALLOWED"          : 검증 통과 → 활성화됨
     *  - "REJECTED_HEROINE_DIRECT" : 캐릭터 직접 조작 시도
     *  - "REJECTED_AFFECTION"      : 호감도 조작 시도
     *  - "REJECTED_PERSONA"        : 페르소나 변경 시도
     *  - "REJECTED_AVATAR"         : 아바타 직접 조작 시도
     *  - "REJECTED_INJECTION"      : 프롬프트 인젝션 시도
     *  - "REJECTED_CONTENT"        : 콘텐츠 정책 위반 (시크릿 모드 OFF에서)
     *  - "REJECTED_UNCLEAR"        : 의도 불분명
     *  - null                      : MANUAL 일반 메모 (명령어 시도 없음)
     */
    @Column(name = "validation_verdict", length = 40)
    private String validationVerdict;

    /** 다음 배치에서 실제 사용되었는지 (ALLOWED 명령어가 LLM 응답에 반영되면 true) */
    @Column(name = "was_used")
    private Boolean wasUsed;

    /** 사용된 시각 */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** 사용된 배치 ID (디버그/추적용) */
    @Column(name = "used_in_batch_id")
    private Integer usedInBatchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public static TheaterDirectorNote manual(ChatRoom room, String content,
                                             Integer actNumber, Integer chapterNumber) {
        TheaterDirectorNote n = new TheaterDirectorNote();
        n.room = room;
        n.noteType = "MANUAL";
        n.content = content;
        n.actNumber = actNumber;
        n.chapterNumber = chapterNumber;
        return n;
    }

    /**
     * [Phase 5.5 UX Polish · R3] 감독 명령어 — 검증 통과한 환경 명령어로 생성.
     */
    public static TheaterDirectorNote command(ChatRoom room, String content,
                                              Integer actNumber, Integer chapterNumber,
                                              String commandType, String verdict) {
        TheaterDirectorNote n = new TheaterDirectorNote();
        n.room = room;
        n.noteType = "MANUAL";
        n.content = content;
        n.actNumber = actNumber;
        n.chapterNumber = chapterNumber;
        n.commandType = commandType;
        n.validationVerdict = verdict;
        n.wasUsed = Boolean.FALSE;
        return n;
    }

    /**
     * [Phase 5.5 UX Polish · R3] 거부된 명령어 — 기록만 보관. 활성화 안 됨.
     */
    public static TheaterDirectorNote rejectedCommand(ChatRoom room, String content,
                                                      Integer actNumber, Integer chapterNumber,
                                                      String verdict) {
        TheaterDirectorNote n = new TheaterDirectorNote();
        n.room = room;
        n.noteType = "MANUAL";
        n.content = content;
        n.actNumber = actNumber;
        n.chapterNumber = chapterNumber;
        n.validationVerdict = verdict;
        n.wasUsed = Boolean.FALSE;
        return n;
    }

    /**
     * [R3] 명령어 사용 마킹 — LLM이 다음 배치에서 반영했음을 기록.
     */
    public void markUsed(Integer batchId) {
        this.wasUsed = Boolean.TRUE;
        this.usedAt = LocalDateTime.now();
        this.usedInBatchId = batchId;
    }

    /**
     * [Phase 5.5 UX Polish · R6] 자동 일러스트 생성 완료 시 URL 첨부.
     * AUTO_MOMENT/BRANCH_TAKEN/CHAPTER_END 등의 노트가 폴링 완료 시 호출됨.
     */
    public void attachIllustration(String url) {
        this.relatedIllustrationUrl = url;
    }

    public static TheaterDirectorNote autoMoment(ChatRoom room, String content,
                                                 String sceneReferenceId,
                                                 Integer actNumber, Integer chapterNumber,
                                                 Long relatedHeroineId) {
        TheaterDirectorNote n = new TheaterDirectorNote();
        n.room = room;
        n.noteType = "AUTO_MOMENT";
        n.content = content;
        n.sceneReferenceId = sceneReferenceId;
        n.actNumber = actNumber;
        n.chapterNumber = chapterNumber;
        n.relatedHeroineId = relatedHeroineId;
        return n;
    }

    public static TheaterDirectorNote branchTaken(ChatRoom room, String content,
                                                  Integer actNumber, Integer chapterNumber) {
        TheaterDirectorNote n = new TheaterDirectorNote();
        n.room = room;
        n.noteType = "BRANCH_TAKEN";
        n.content = content;
        n.actNumber = actNumber;
        n.chapterNumber = chapterNumber;
        return n;
    }

    public static TheaterDirectorNote intermission(ChatRoom room, String content,
                                                   Integer actNumber) {
        TheaterDirectorNote n = new TheaterDirectorNote();
        n.room = room;
        n.noteType = "INTERMISSION";
        n.content = content;
        n.actNumber = actNumber;
        return n;
    }

    public static TheaterDirectorNote chapterEnd(ChatRoom room, String content,
                                                 Integer actNumber, Integer chapterNumber) {
        TheaterDirectorNote n = new TheaterDirectorNote();
        n.room = room;
        n.noteType = "CHAPTER_END";
        n.content = content;
        n.actNumber = actNumber;
        n.chapterNumber = chapterNumber;
        return n;
    }

    public static TheaterDirectorNote intervention(ChatRoom room, String content,
                                                   Integer actNumber, Integer chapterNumber) {
        TheaterDirectorNote n = new TheaterDirectorNote();
        n.room = room;
        n.noteType = "INTERVENTION";
        n.content = content;
        n.actNumber = actNumber;
        n.chapterNumber = chapterNumber;
        return n;
    }

    public void linkIllustration(String url) {
        this.relatedIllustrationUrl = url;
    }

    public void updateContent(String content) {
        this.content = content;
    }
}