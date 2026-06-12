package com.spring.aichat.domain.chat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [D-5 / E-2b] Story V2 서사 상태 — "서사 나침반(narrative compass)"의 영속체.
 *
 * <p>Story 모드의 핵심 가치는 *자유*다(Theater의 강제 아크/챕터 구조는 절대 넘어오지 않는다).
 * 따라서 이 상태는 강제 줄거리가 아니라, *디렉터가 이야기에서 자연히 연 떡밥(thread)을 기억*했다가
 * 다음 턴에 "아직 열려있다"고 *권유*만 해주는 용도다. 유저가 다른 길로 가면 그 길을 따라간다.
 * 떡밥은 "언젠가 건드릴 수 있는 씨앗"이지 *체크리스트가 아니다*.
 *
 * <p>[ChatRoom v2 오버로드에서 분리한 이유 — E-2 구조개편 정신]
 * ChatRoom은 이미 V1 스탯과 v2 오버로드 필드로 비대하다. 서사 thread 같은
 * v2 고유 상태는 별도 테이블로 떼어 결합도를 낮춘다.
 *
 * <pre>
 *   threads_json 형식:
 *     [{ "id": "t1", "label": "한 줄 설명", "status": "OPEN|ADVANCED|RESOLVED", "note": "디렉터 메모(선택)" }]
 *   - 디렉터 응답의 narrative_threads 필드에서 델타 추출 → 병합 영속.
 *     (메모리와 달리 LLM 요약 단계 불필요 — 디렉터가 상태를 *직접 보고*한다.)
 *   - 프롬프트 [나침반] 섹션엔 RESOLVED 제외한 *열린 실*만 권유로 주입한다.
 *   - 빈 시작("[]") — 백본을 미리 심지 않고, 이야기가 자연히 만들어가는 대로 누적.
 * </pre>
 *
 * <p>room_id는 v2 {@link ChatRoom}과 1:1 (unique). FK 미설정 — 다른 v2 엔티티 패턴과 일관.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "story_v2_state",
    indexes = {
        @Index(name = "idx_story_v2_state_room", columnList = "room_id", unique = true)
    })
public class StoryV2State {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** v2 ChatRoom과 1:1. (unique는 idx_story_v2_state_room 인덱스로 보장.) */
    @Column(name = "room_id", nullable = false)
    private Long roomId;

    /**
     * 서사 thread 목록 (JSON). 디렉터가 연 떡밥과 그 상태.
     * null/blank은 빈 배열로 정규화한다.
     */
    @Column(name = "threads_json", columnDefinition = "TEXT")
    private String threadsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.threadsJson == null || this.threadsJson.isBlank()) {
            this.threadsJson = "[]";
        }
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static StoryV2State create(Long roomId) {
        StoryV2State s = new StoryV2State();
        s.roomId = roomId;
        s.threadsJson = "[]";
        return s;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Mutators
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** [D-5b] narrative_threads 델타 병합 결과(JSON 배열 문자열)를 반영. */
    public void updateThreads(String json) {
        this.threadsJson = (json == null || json.isBlank()) ? "[]" : json;
    }
}