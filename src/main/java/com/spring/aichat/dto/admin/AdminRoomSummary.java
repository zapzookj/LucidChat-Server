package com.spring.aichat.dto.admin;

/** CS 로그 뷰어 — 유저의 방 목록 행. */
public record AdminRoomSummary(
    Long roomId,
    String characterName,
    String chatMode,
    long logCount
) {}
