package com.spring.aichat.controller;

import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.lobby.CharacterResponse;
import com.spring.aichat.dto.lobby.CreateRoomRequest;
import com.spring.aichat.dto.lobby.RoomSummaryResponse;
import com.spring.aichat.dto.story.StoryV2Responses.WorldCardResponse;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.service.LobbyService;
import com.spring.aichat.service.story.StoryV2Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [Phase 4.5] 로비 API 컨트롤러
 * [Story V2]  V2 World 카드 엔드포인트 추가
 *
 * 로그인 직후 마주하는 로비 화면에서 사용하는 엔드포인트들
 *
 * GET  /api/v1/lobby/characters         — 전체 캐릭터 목록 (새로운 만남 - Sandbox)
 * GET  /api/v1/lobby/rooms              — 내 채팅방 목록 (기억의 끈 - 모든 모드)
 * POST /api/v1/lobby/rooms              — 새 채팅방 생성 (Sandbox 1:1)
 * GET  /api/v1/lobby/worlds             — [V2] 전체 World 목록 (V2 Story 진입)
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/lobby")
public class LobbyController {

    private final LobbyService lobbyService;
    private final StoryV2Service storyV2Service;
    private final UserRepository userRepository;

    /**
     * 전체 캐릭터 목록 — Sandbox 캐릭터 카루셀용.
     * <p>V2 STORY는 World 단위 입장이므로 본 엔드포인트는 *Sandbox 진입용*으로 의미 재정의.
     */
    @GetMapping("/characters")
    public List<CharacterResponse> getCharacters() {
        return lobbyService.getAllCharacters();
    }

    /**
     * 내 채팅방 목록 — '기억의 끈' (Continue) 패널용.
     * Sandbox/V2 Story/Theater 모든 모드 방 통합 노출. 최근 활동 순으로 정렬.
     */
    @GetMapping("/rooms")
    public List<RoomSummaryResponse> getMyRooms(Authentication authentication) {
        return lobbyService.getMyRooms(authentication.getName());
    }

    /**
     * 새 채팅방 생성 — Sandbox 1:1 캐릭터 입장.
     * <p>V2 Story 방 생성은 별도 엔드포인트 ({@code POST /api/v2/story/rooms}) 사용.
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

    /**
     * [V2] 전체 World 목록 — Story V2 카드 그리드용.
     * <p>각 World 카드는 *유저의 기존 V2 방 존재 여부* + *히로인 수*를 함께 포함.
     */
    @GetMapping("/worlds")
    public List<WorldCardResponse> getWorlds(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
        return storyV2Service.listWorlds(user);
    }
}