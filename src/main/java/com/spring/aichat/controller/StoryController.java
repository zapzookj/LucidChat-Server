package com.spring.aichat.controller;

import com.spring.aichat.dto.director.DirectorDirective;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.director.DirectorService;
import com.spring.aichat.service.stream.ChatStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 스토리/이벤트/디렉터 컨트롤러
 *
 * [Phase 5.5-Director v3] 투명한 디렉터 패턴 리팩토링
 *
 * [현재 엔드포인트]
 * 디렉터:
 *   - GET  /director/peek               — Directive 존재 확인
 *   - POST /director/consume            — Directive 소비 + ChatRoom 적용
 *   - POST /director/request            — 수동 호출 → BRANCH_SCENARIO 3장 카드
 *   - POST /director/auto-respond (SSE) — INTERLUDE/TRANSITION/AWAY 자동 응답
 * 이벤트 SSE (유지):
 *   - POST /events/watch (SSE)          — AWAY 관찰 모드 자동 진행
 *   - POST /time-skip (SSE)             — 시간 넘기기
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/story/rooms/{roomId}")
public class StoryController {

    private final DirectorService directorService;
    private final ChatStreamService chatStreamService;
    private final ApiRateLimiter rateLimiter;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  디렉터 엔드포인트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/director/peek")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<DirectorDirective> peekDirective(@PathVariable Long roomId) {
        return directorService.peekDirective(roomId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Directive 소비 + ChatRoom에 적용
     *
     * 프론트가 자동 처리 전에 호출.
     * INTERLUDE/TRANSITION/AWAY의 constraint를 ChatRoom에 세팅.
     */
    @PostMapping("/director/consume")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<DirectorDirective> consumeDirective(@PathVariable Long roomId) {
        // [블록 D · §G-7] INTERLUDE/TRANSITION/AWAY 적용 분기 제거.
        //   자동 판정 생산자(DirectorService.evaluateAndCache)가 2026-05-09 도그푸딩 #1로 폐기되고
        //   수동 호출(requestManualIntervention)이 BRANCH/SCENARIO만 강제하므로, Redis에 들어갈 수 있는
        //   판정은 BRANCH뿐이다 → 이 분기는 도달 불가였다. BRANCH의 constraint 세팅은
        //   sendAutoDirectorResponse가 담당한다(§G-13 유지분).
        return directorService.consumeDirective(roomId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 유저 수동 디렉터 호출 → BRANCH_SCENARIO 3장 카드
     */
    @PostMapping("/director/request")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<DirectorDirective> requestDirector(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        if (rateLimiter.checkEventTrigger(authentication.getName())) {
            throw new RateLimitException("디렉터 요청이 너무 빠릅니다.", 3);
        }
        DirectorDirective directive = directorService.requestManualIntervention(roomId);
        return ResponseEntity.ok(directive);
    }

    /**
     * [v3] 투명 디렉터 자동 응답 (SSE)
     *
     * INTERLUDE/TRANSITION/AWAY Directive 소비 후, 프론트가 나레이션을 표시한 뒤 호출.
     * ChatRoom에 이미 constraint가 적용된 상태에서 캐릭터 자동 응답을 생성.
     *
     * AWAY의 경우 이벤트 ONGOING 모드로 진입.
     */
    @PostMapping(value = "/director/auto-respond", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SseEmitter autoRespond(
        @PathVariable Long roomId,
        @RequestBody AutoRespondRequest request,
        Authentication authentication
    ) {
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 3);
        }

        // [Phase6/Tier4 / H-18] SSE timeout 150s — LLM timeout 120s + 30s buffer
        SseEmitter emitter = new SseEmitter(150_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> {});

        chatStreamService.sendAutoDirectorResponse(roomId, request.directiveType(), request.eventContext(), request.chosenIndex(), emitter);
        return emitter;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  이벤트 SSE 엔드포인트 (유지)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━


    @PostMapping(value = "/events/watch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SseEmitter watchEvent(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 3);
        }

        // [Phase6/Tier4 / H-18] SSE timeout 150s — LLM timeout 120s + 30s buffer
        SseEmitter emitter = new SseEmitter(150_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> {});
        chatStreamService.sendDirectorWatchStream(roomId, emitter);
        return emitter;
    }

    @PostMapping(value = "/time-skip", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SseEmitter timeSkip(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 3);
        }

        // [Phase6/Tier4 / H-18] SSE timeout 150s — LLM timeout 120s + 30s buffer
        SseEmitter emitter = new SseEmitter(150_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> {});
        chatStreamService.sendTimeSkipStream(roomId, emitter);
        return emitter;
    }

    // ── DTOs ──
    /** chosenIndex: [블록 D · §G-13] BRANCH 카드 인덱스 — 서버가 가격표로 비용을 재판정한다. 미전송 시 레거시 폴백. */
    public record AutoRespondRequest(String directiveType, String eventContext, Integer chosenIndex) {}
}