package com.spring.aichat.controller;

import com.spring.aichat.dto.theater.TheaterRequests.*;
import com.spring.aichat.dto.theater.TheaterResponses.*;
import com.spring.aichat.service.theater.TheaterLobbyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [Phase 5.5-Theater] Theater 로비 컨트롤러
 *
 * 기존 /api/v1/lobby (Dialogue 그룹)와 별도로 /api/v1/theater/lobby 네임스페이스 사용.
 *
 * [Endpoints]
 * GET   /api/v1/theater/lobby/worlds              — 세계관 목록
 * GET   /api/v1/theater/lobby/worlds/{worldId}    — 특정 세계관 상세
 * GET   /api/v1/theater/lobby/sessions            — 내 Theater 세션 목록 (Continue)
 * POST  /api/v1/theater/lobby/sessions            — 새 Theater 세션 생성
 * GET   /api/v1/theater/rooms/{roomId}            — Theater 방 정보 조회 (재진입)
 * PATCH /api/v1/theater/rooms/{roomId}/avatar     — 아바타 업데이트
 * POST  /api/v1/theater/rooms/{roomId}/reroll     — 스탯 리롤 (유료 아이템)
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/theater")
public class TheaterLobbyController {

    private final TheaterLobbyService theaterLobbyService;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  세계관 목록
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/lobby/worlds")
    public List<WorldCard> getWorlds() {
        return theaterLobbyService.getWorldCards();
    }

    @GetMapping("/lobby/worlds/{worldId}")
    public WorldCard getWorld(@PathVariable String worldId) {
        return theaterLobbyService.getWorldCard(worldId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Theater 세션 목록 (Continue)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/lobby/sessions")
    public List<TheaterSessionCard> getMySessions(Authentication authentication) {
        return theaterLobbyService.getMyTheaterSessions(authentication.getName());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Theater 세션 생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping("/lobby/sessions")
    public TheaterRoomInfo createSession(
        @RequestBody @Valid CreateTheaterSessionRequest request,
        Authentication authentication
    ) {
        return theaterLobbyService.createSession(authentication.getName(), request);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Theater 방 재진입
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/rooms/{roomId}")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public TheaterRoomInfo getRoom(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        return theaterLobbyService.getRoomInfo(roomId, authentication.getName());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  아바타 업데이트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PatchMapping("/rooms/{roomId}/avatar")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Void> updateAvatar(
        @PathVariable Long roomId,
        @RequestBody @Valid UpdateAvatarRequest request,
        Authentication authentication
    ) {
        theaterLobbyService.updateAvatar(
            roomId,
            authentication.getName(),
            request.avatarName(),
            request.profile(),
            request.personaText()
        );
        return ResponseEntity.ok().build();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스탯 리롤 (유료 아이템 사용 전제)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping("/rooms/{roomId}/reroll")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Void> rerollStats(
        @PathVariable Long roomId,
        @RequestBody @Valid RerollStatsRequest request,
        Authentication authentication
    ) {
        theaterLobbyService.rerollStats(
            roomId,
            authentication.getName(),
            request.newDistribution()
        );
        return ResponseEntity.ok().build();
    }
}