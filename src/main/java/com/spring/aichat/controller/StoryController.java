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
 * [Bug #5 Fix] NarratorService 레거시 제거:
 *   - POST /events (triggerEvent) 제거 — 디렉터 시스템으로 완전 대체
 *   - NarratorService 의존성 제거
 *
 * [현재 엔드포인트]
 * 디렉터:
 *   - GET  /director/peek
 *   - POST /director/consume
 *   - POST /director/request
 *   - POST /director/apply-branch (SSE)
 *   - POST /director/apply-transition (SSE)
 * 이벤트 SSE (유지):
 *   - POST /events/select (SSE)
 *   - POST /events/watch (SSE)
 *   - POST /time-skip (SSE)
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/story/rooms/{roomId}")
public class StoryController {

    private final DirectorService directorService;
    private final ChatStreamService chatStreamService;
    private final ApiRateLimiter rateLimiter;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Director] 디렉터 엔드포인트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 대기 중인 Directive 확인 (Peek — 소비하지 않음)
     *
     * 프론트엔드가 유저 메시지 전송 직전에 폴링.
     * Directive가 존재하면 인터루드 시퀀스를 발동.
     *
     * @return Directive JSON 또는 204 No Content
     */
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
     * 프론트가 인터루드 나레이션을 유저에게 보여준 뒤 호출.
     * INTERLUDE/TRANSITION의 constraint를 ChatRoom에 세팅하여
     * 다음 액터 호출에서 자동 주입되도록 한다.
     *
     * BRANCH의 경우 이 엔드포인트에서는 소비만 하고,
     * 실제 선택은 /director/apply-branch에서 처리.
     *
     * @return 소비된 Directive 또는 404 (이미 소비/만료됨)
     */
    @PostMapping("/director/consume")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<DirectorDirective> consumeDirective(@PathVariable Long roomId) {
        return directorService.consumeDirective(roomId)
            .map(directive -> {
                // INTERLUDE / TRANSITION → ChatRoom에 constraint 적용
                if (directive.checkInterlude() || directive.checkTransition()) {
                    chatStreamService.applyDirectiveToRoom(roomId, directive);
                }
                return ResponseEntity.ok(directive);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 유저 수동 디렉터 호출
     *
     * 기존 "이벤트 트리거" / "시간 넘기기" 버튼의 통합 대체.
     * 디렉터가 동기적으로 판단하여 Directive를 즉시 반환.
     *
     * PASS가 반환되면 → 프론트에서 "지금은 적절한 타이밍이 아닌 것 같아요" 표시
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
     * BRANCH 선택 → 이벤트 시작 (SSE)
     *
     * 디렉터가 BRANCH를 제시한 후, 유저가 선택지를 고르면 호출.
     * 기존 /events/select와 동일한 플로우지만, 디렉터가 생성한 선택지 기반.
     */
    @PostMapping(value = "/director/apply-branch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SseEmitter applyBranch(
        @PathVariable Long roomId,
        @RequestBody SelectEventRequest request,
        Authentication authentication
    ) {
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 3);
        }

        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> {});

        // 기존 이벤트 선택 플로우 재사용
        chatStreamService.sendEventSelectStream(roomId, request.detail(), request.energyCost(), emitter);

        return emitter;
    }

    /**
     * TRANSITION 적용 → 시간/장소 전환 (SSE)
     *
     * 디렉터가 TRANSITION을 제시하고, 유저가 나레이션을 확인한 후 호출.
     * constraint가 이미 ChatRoom에 적용된 상태에서 액터를 호출.
     *
     * 이 엔드포인트는 기존 /time-skip의 디렉터 버전.
     * constraint가 적용된 상태에서의 첫 메시지 생성.
     */
    @PostMapping(value = "/director/apply-transition", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SseEmitter applyTransition(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 3);
        }

        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> {});

        // 시간 넘기기 플로우 재사용 (constraint는 이미 ChatRoom에 적용됨)
        chatStreamService.sendTimeSkipStream(roomId, emitter);

        return emitter;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  이벤트 SSE 엔드포인트 (유지)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping(value = "/events/select", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SseEmitter selectEvent(
        @PathVariable Long roomId,
        @RequestBody SelectEventRequest request,
        Authentication authentication
    ) {
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 3);
        }

        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> {});
        chatStreamService.sendEventSelectStream(roomId, request.detail(), request.energyCost(), emitter);
        return emitter;
    }

    @PostMapping(value = "/events/watch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SseEmitter watchEvent(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 3);
        }

        SseEmitter emitter = new SseEmitter(120_000L);
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

        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> {});
        chatStreamService.sendTimeSkipStream(roomId, emitter);
        return emitter;
    }

    // ── DTO ──
    public record SelectEventRequest(String detail, int energyCost) {}
}