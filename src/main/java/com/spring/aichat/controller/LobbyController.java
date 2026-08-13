package com.spring.aichat.controller;

import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.lobby.CharacterProfileResponse;
import com.spring.aichat.dto.lobby.CharacterResponse;
import com.spring.aichat.dto.lobby.CreateRoomRequest;
import com.spring.aichat.dto.lobby.LobbyPublicDtos;
import com.spring.aichat.dto.lobby.RoomSummaryResponse;
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
    public List<CharacterResponse> getCharacters(
        @RequestParam(value = "worldId", required = false) String worldId) {
        // [Phase 7-V2 Pivot] worldId 쿼리 파라미터 — 통합 로비의 세계관별 캐릭터 필터.
        //   미전달 시 전체 반환 (V1 호환).
        return lobbyService.getCharactersByWorld(worldId);
    }

    /**
     * [블록 A] 홈 탭 캐릭터 피드 — 공식(큐레이션 순) + PUBLIC UGC(최신순), 배지 필드 포함.
     * 게스트 공개(permitAll) — 응답은 {@code LobbyPublicDtos.FeedItem} 스코프 계약을 따른다.
     */
    @GetMapping("/feed")
    public LobbyPublicDtos.FeedResponse getFeed() {
        return lobbyService.getHomeFeed();
    }

    /**
     * [2026-07-22 프로필 뷰] 몰입형 캐릭터 프로필 — 카드 클릭 → 프로필 → 대화 플로우의 2단계.
     * 공개 캐릭터 전체 + 비공개는 소유자만 (그 외 404 은닉).
     * <p>[블록 A 게스트] permitAll — 익명이면 Authentication이 null로 주입되므로
     * 게스트 경로(공개분만)로 분기한다. null 가드 없이는 NPE 500 (전 컨트롤러 공통 함정).
     */
    @GetMapping("/characters/{characterId:\\d+}/profile")
    public CharacterProfileResponse getCharacterProfile(
        @PathVariable Long characterId, Authentication authentication) {
        if (authentication == null) {
            return lobbyService.getCharacterProfileForGuest(characterId);
        }
        return lobbyService.getCharacterProfile(authentication.getName(), characterId);
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
     * <p>[블록 A 게스트] 익명이면 시크릿 메타(secretAllowed)·유저 종속 필드가 없는
     * 게스트 카드({@code LobbyPublicDtos.GuestWorldCard})를 반환한다.
     */
    @GetMapping("/worlds")
    public List<?> getWorlds(Authentication authentication) {
        if (authentication == null) {
            return storyV2Service.listWorldsForGuest();
        }
        User user = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
        return storyV2Service.listWorlds(user);
    }

    /**
     * [블록 A] 세계관 탭 — UGC 월드 카드.
     * <ul>
     *   <li>회원: 내 월드(전체) + 타인의 승인(APPROVED)·플레이 가능 월드 (docs/14 §B —
     *       '새로운 만남 UGC 합류'의 세계관 탭 편입)</li>
     *   <li>게스트: 승인·플레이 가능 월드만, 게스트 카드로</li>
     * </ul>
     * UGC 스토리 게이트(ugc.modes.story-enabled) off면 빈 목록.
     */
    @GetMapping("/worlds/ugc")
    public List<?> getUgcWorlds(Authentication authentication) {
        if (authentication == null) {
            return storyV2Service.listPublicUgcWorldsForGuest();
        }
        User user = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
        return storyV2Service.listUgcWorldsCombined(user);
    }
}