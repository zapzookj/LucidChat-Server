package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.EmotionTag;

import java.util.List;

/**
 * 엔딩 생성 응답 DTO
 *
 * [Phase 4] 분기별 엔딩 이벤트 시스템
 *
 * 구조:
 *   endingType        — HAPPY / BAD
 *   title             — LLM이 생성한 고유 엔딩 제목 (예: "달빛 아래의 고백")
 *   epilogueScenes    — 엔딩 전용 시네마틱 씬 (캐릭터 마지막 대사들)
 *   memories          — RAG에서 검색한 핵심 추억 목록
 *   characterQuote    — 캐릭터의 마지막 한 줄 (엔딩 크레딧용)
 *   stats             — 플레이 통계
 */
public record EndingResponse(
    String endingType,              // HAPPY | BAD
    String title,                   // 동적 엔딩 제목
    List<EndingScene> epilogueScenes, // 엔딩 연출 씬
    List<String> memories,          // RAG 추억 목록
    String characterQuote,          // 마지막 한 줄
    EndingStats stats               // 플레이 통계
) {
    public record EndingScene(
        String narration,           // 지문
        String dialogue,            // 대사
        EmotionTag emotion,         // 감정
        String location,            // 장소 (null 가능)
        String time,                // 시간대 (null 가능)
        String outfit,              // 복장 (null 가능)
        String bgmMode              // BGM (null 가능)
    ) {}

    public record EndingStats(
        long totalMessages,         // 총 메시지 수
        long totalDays,             // 함께한 일수
        int finalAffection,         // 최종 호감도
        String finalRelation,       // 최종 관계
        String firstMessageDate     // 첫 대화 날짜
    ) {}
}