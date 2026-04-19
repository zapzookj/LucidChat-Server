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