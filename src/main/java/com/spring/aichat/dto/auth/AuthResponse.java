package com.spring.aichat.dto.auth;

import java.util.Map;

/**
 * 인증 응답(로그인/회원가입 공통)
 *
 * [Phase 4.5] roomId 제거 → 로비 라우팅으로 전환
 * - 로그인 성공 시 로비로 이동, 유저가 직접 채팅방 선택
 * - hasExistingRooms: 기존 채팅방 존재 여부 (Continue 메뉴 활성화 판단용)
 */
public record AuthResponse(
    String accessToken,
    long expiresIn,
    boolean hasExistingRooms,   // [Phase 4.5] 기존 방 존재 여부
    Map<String, Object> user
) {}