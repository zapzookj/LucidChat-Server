package com.spring.aichat.controller;

import com.spring.aichat.dto.lobby.CharacterResponse;
import com.spring.aichat.dto.lobby.CreateRoomRequest;
import com.spring.aichat.dto.lobby.RoomSummaryResponse;
import com.spring.aichat.service.LobbyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [Phase 4.5] 로비 API 컨트롤러
 *
 * 로그인 직후 마주하는 로비 화면에서 사용하는 엔드포인트들
 *
 * GET  /api/v1/lobby/characters         — 전체 캐릭터 목록 (새로운 만남)
 * GET  /api/v1/lobby/rooms              — 내 채팅방 목록 (기억의 끈)
 * POST /api/v1/lobby/rooms              — 새 채팅방 생성 (캐릭터 + 모드 선택 후 입장)
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/lobby")
public class LobbyController {

    private final LobbyService lobbyService;

    /**
     * 전체 캐릭터 목록 — 캐릭터 카루셀용
     */
    @GetMapping("/characters")
    public List<CharacterResponse> getCharacters() {
        return lobbyService.getAllCharacters();
    }

    /**
     * 내 채팅방 목록 — '기억의 끈' (Continue) 패널용
     * 최근 활동 순으로 정렬
     */
    @GetMapping("/rooms")
    public List<RoomSummaryResponse> getMyRooms(Authentication authentication) {
        return lobbyService.getMyRooms(authentication.getName());
    }

    /**
     * 새 채팅방 생성 — 캐릭터 + 모드 선택 후 입장
     *
     * 이미 동일 조합(유저 + 캐릭터 + 모드)의 방이 존재하면 기존 방의 roomId를 반환
     * (중복 생성 방지 — Idempotent)
     *
     * @return 생성(또는 기존)된 방의 roomId
     */
    @PostMapping("/rooms")
    public RoomSummaryResponse createRoom(
        @RequestBody @Valid CreateRoomRequest request,
        Authentication authentication
    ) {
        return lobbyService.createOrGetRoom(authentication.getName(), request);
    }
}