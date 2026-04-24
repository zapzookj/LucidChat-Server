package com.spring.aichat.domain.theater;

import com.spring.aichat.domain.enums.EmotionTag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * [Phase 5.5-Theater-Polish] Theater Scene MongoDB 로그 문서
 *
 * [저장 이유]
 * - Dialogue의 ChatLogDocument와 달리, Theater는 Scene 구조가 매우 다름
 *   (narration, inner_narration, dialogue, speaker, stat_reflection_hint 등)
 * - 유저가 "이전 씬 보기" / "대화 기록 조회" 기능을 요구
 * - Chapter 전환 시 장기 기억 연속성 유지 필요
 *
 * [인덱스]
 * - {roomId, createdAt}: 시계열 페이징
 * - {roomId, actNumber, chapterNumber}: Chapter별 조회
 * - {roomId, batchId}: 특정 배치 씬 조회
 *
 * [수명]
 * - TTL 없음 (유저가 명시적으로 삭제하지 않는 한 영구 보관)
 * - 엔딩 도달 후에도 유지 — 엔딩 크레딧의 Memory Highlights 소스
 */
@Document(collection = "theater_scene_logs")
@CompoundIndexes({
    @CompoundIndex(name = "idx_theater_room_created",
        def = "{'roomId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "idx_theater_room_act_chapter",
        def = "{'roomId': 1, 'actNumber': 1, 'chapterNumber': 1, 'sceneSeqInChapter': 1}"),
    @CompoundIndex(name = "idx_theater_room_batch",
        def = "{'roomId': 1, 'batchId': 1, 'sceneIndexInBatch': 1}")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TheaterSceneLog {

    @Id
    private String id;

    @Field("roomId")
    private Long roomId;

    /** Act 번호 (1~4) */
    @Field("actNumber")
    private int actNumber;

    /** Chapter 번호 (1부터) */
    @Field("chapterNumber")
    private int chapterNumber;

    /** 배치 ID (Chapter 내부에서 0부터 증가) */
    @Field("batchId")
    private int batchId;

    /** 배치 내 씬 인덱스 (0부터) */
    @Field("sceneIndexInBatch")
    private int sceneIndexInBatch;

    /** Chapter 내 누적 씬 순번 (이전 씬 네비게이션용) */
    @Field("sceneSeqInChapter")
    private int sceneSeqInChapter;

    /** 전체 세션 누적 씬 순번 */
    @Field("globalSceneSeq")
    private long globalSceneSeq;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  씬 본문
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 3인칭 나레이션 */
    @Field("narration")
    private String narration;

    /** 아바타의 속마음 (Theater 고유, optional) */
    @Field("innerNarration")
    private String innerNarration;

    /** 대사 (히로인 또는 아바타 또는 없음) */
    @Field("dialogue")
    private String dialogue;

    /**
     * 화자 유형
     * - "HEROINE": 히로인 대사
     * - "AVATAR": 주인공 대사
     * - null or "": 나레이션만 있는 씬
     */
    @Field("speakerType")
    private String speakerType;

    /** 화자의 표시 이름 (히로인 이름 or 아바타 이름) */
    @Field("speakerName")
    private String speakerName;

    /** 히로인 ID (speakerType=HEROINE일 때만) */
    @Field("heroineId")
    private Long heroineId;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  시각/음향 맥락
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Field("emotion")
    private EmotionTag emotion;

    @Field("location")
    private String location;

    @Field("timeOfDay")
    private String timeOfDay;

    @Field("outfit")
    private String outfit;

    @Field("bgmMode")
    private String bgmMode;

    /** 일러스트 URL (생성된 경우) */
    @Field("illustrationUrl")
    private String illustrationUrl;

    /** LLM이 제공한 stat 반영 힌트 (디버깅 용) */
    @Field("statReflectionHint")
    private String statReflectionHint;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  타임스탬프
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @CreatedDate
    @Field("createdAt")
    private LocalDateTime createdAt;
}