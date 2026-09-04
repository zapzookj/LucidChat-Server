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

    /**
     * [블록 A 게스트] permitAll — 익명이면 시크릿 메타(secretAllowed) 없는
     * 게스트 카드로 응답한다 (게스트=비성인 취급, docs/14 부록 §3).
     */
    @GetMapping("/lobby/worlds")
    public List<?> getWorlds(Authentication authentication) {
        if (authentication == null) {
            return theaterLobbyService.getGuestWorldCards();
        }
        return theaterLobbyService.getWorldCards();
    }

    @GetMapping("/lobby/worlds/{worldId}")
    public Object getWorld(@PathVariable String worldId, Authentication authentication) {
        if (authentication == null) {
            return theaterLobbyService.getGuestWorldCard(worldId);
        }
        return theaterLobbyService.getWorldCard(worldId);
    }

    /**
     * [2026-07-31 에픽 A] 유저가 극장을 열 수 있는 UGC 월드 카드 (내 월드 v1).
     * 게이트(ugc.modes.theater-enabled) off면 빈 배열.
     */
    @GetMapping("/lobby/ugc-worlds")
    public List<WorldCard> getUgcWorlds(Authentication authentication) {
        return theaterLobbyService.getUgcWorldCards(authentication.getName());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Theater 세션 목록 (Continue)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/lobby/sessions")
    public List<TheaterSessionCard> getMySessions(Authentication authentication) {
        return theaterLobbyService.getMyTheaterSessions(authentication.getName());
    }

    /**
     * [Phase 5.5 UX Polish · R4] 활성 세션 1개만 — 로비 메인 카드 노출용.
     * 빈 리스트면 "새 극 시작" CTA를 메인에 표시.
     */
    @GetMapping("/lobby/sessions/active")
    public List<TheaterSessionCard> getActiveSessions(Authentication authentication) {
        return theaterLobbyService.getActiveTheaterSessions(authentication.getName());
    }

    /**
     * [Phase 5.5 UX Polish · R4] 아카이브 세션 (ARCHIVED + ENDED).
     * 최근 변경순.
     */
    @GetMapping("/lobby/sessions/archive")
    public List<TheaterSessionCard> getArchivedSessions(Authentication authentication) {
        return theaterLobbyService.getArchivedTheaterSessions(authentication.getName());
    }

    /**
     * [Phase 5.5 UX Polish · R4] 아카이브된 극을 다시 활성화 (resume).
     * ARCHIVED만 가능. ENDED는 BadRequest.
     * 다른 활성극이 있으면 자동 archive (활성 1개 정책).
     */
    @PostMapping("/lobby/sessions/{roomId}/resume")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public TheaterRoomInfo resumeSession(
        @PathVariable Long roomId, Authentication authentication
    ) {
        return theaterLobbyService.resumeArchivedSession(authentication.getName(), roomId);
    }

    /**
     * [Phase 5.5 UX Polish · R4] 활성 극을 명시적으로 아카이브 (잠시 멈추기).
     * 유저가 여러 극을 자유롭게 전환할 때 사용. 활성극이 없으면 no-op.
     */
    @PostMapping("/lobby/sessions/active/archive")
    public ResponseEntity<Void> archiveActive(Authentication authentication) {
        theaterLobbyService.archiveActiveSession(authentication.getName());
        return ResponseEntity.noContent().build();
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
    //  스탯 리롤 — **무과금 · BM 미정 · 화면 진입점 없음**
    //
    //  [B-6.1 · 안건 17-① 확정 (0)안 · 2026-09-04 종원]
    //  종전 주석은 "유료 아이템 사용 전제"라고 적혀 있었으나 구현에는 차감이 없다
    //  (TheaterLobbyService.rerollStats에 consumeEnergy·아이템 소모 모두 0건).
    //  주석과 구현이 갈려 있어 감사 때마다 'P1 무과금 착취'로 재보고됐다 — 실제로 이번
    //  레지스터가 그렇게 판정했다. 주석을 사실에 맞춘다.
    //
    //  ★ 착취 이득이 0이라 지금 새는 것이 없다 — 제약이 셋이다:
    //   ① TheaterLobbyService.rerollStats가 validateInitialStats로 **방 생성과 동일한 티어 캡**을
    //      적용한다. 리롤로 얻을 수 있는 것이 생성 시점에 이미 전부 가능하다.
    //   ② 총 씬 50 이상이면 거부 — 세션 초반 한정.
    //   ③ 프론트에 API 래퍼(TheaterLobbyApi.js rerollStats)만 있고 **호출하는 컴포넌트가 0건**이다.
    //      유저가 도달할 방법이 없다(프로드 theater_states 0행 — 극장 플레이 이력 자체가 없다).
    //
    //  과금을 붙이려면 리롤 UI 신설이 선행이다(없는 기능에 과금부터 다는 셈이 된다).
    //  그때 반드시 '현재 총합 하회 금지' 술어를 함께 넣을 것 — 없으면 FREE(캡 0/0)·LUCID_PASS
    //  유저에게 "돈 내고 내 스탯을 0으로 만드는 버튼"이 된다.
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