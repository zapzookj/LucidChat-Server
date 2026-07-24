package com.spring.aichat.dto.lobby;

import java.util.List;

/**
 * [2026-07-22 프로필 뷰] 캐릭터 프로필 — "얘 무슨 캐릭터인데?"에 대답하는 몰입형 신상 카드.
 *
 * <p>원칙: 백엔드 설정 원문(성격/말투 프롬프트·서사 전문·첫인사 전문)은 비노출 —
 * 대화에서 발견하는 영역으로 남긴다. 티저·발췌는 첫 문장만 서버에서 절삭해 내려준다.
 * 신상 4종(height/likes/dislikes/hobby)은 미입력 시 null — 프런트가 "기록 없음" 처리.
 */
public record CharacterProfileResponse(
    Long characterId,
    String name,
    String slug,
    Integer age,
    /** 역할 한 줄 (effective role). */
    String role,
    String tagline,
    /** 무드 태그 칩 (UGC: persona 태그 · 공식: 시드 입력 — 없으면 빈 리스트). */
    List<String> moodTags,
    /** "OFFICIAL" | "UGC" | null(미연결). */
    String worldType,
    String worldName,
    /** 외형·복장 한국어 서술 (몰입용 — 프롬프트 원문 아님). */
    String appearance,
    String clothing,
    // ── 몰입형 신상 (전부 nullable) ──
    String height,
    String likes,
    String dislikes,
    String hobby,
    /** 프로필 카드 전용 한 줄 문장 — 없으면 greetingExcerpt(첫인사 첫 문장)로 폴백해 채워 내려준다. */
    String profileQuote,
    /** 첫 만남 나레이션 티저 — 첫 문장만 (A안 몰입 뷰). */
    String introTeaser,
    /** 첫인사 발췌 — 첫 문장만 (B안 인용 블록 — 말투 미리보기). */
    String greetingExcerpt,
    String defaultImageUrl,
    String thumbnailUrl,
    boolean ugc,
    /** UGC 캐릭터의 크리에이터 닉네임 (공식은 null). */
    String creatorNickname
) {}
