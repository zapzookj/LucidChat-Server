package com.spring.aichat.controller;

import com.spring.aichat.dto.chat.NarratorResponse;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.NarratorService;
import com.spring.aichat.service.stream.ChatStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 스토리/이벤트 컨트롤러
 *
 * [Phase 5.5-EV] 이벤트 시스템 강화:
 *   - POST /events          : 이벤트 옵션 생성 (3가지 선택지)
 *   - POST /events/select   : 이벤트 선택 → 디렉터 모드 시작 (SSE)
 *   - POST /events/watch    : [👀 계속 지켜보기] — SYSTEM_DIRECTOR 주입 (SSE)
 *   - POST /time-skip       : [시간 넘기기] — 시간/장소 전환 (SSE)
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/story/rooms/{roomId}")
public class StoryController {

    private final NarratorService narratorService;
    private final ChatStreamService chatStreamService;
    private final ApiRateLimiter rateLimiter;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 이벤트 옵션 생성 (3가지 선택지 반환)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping("/events")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public NarratorResponse triggerEvent(@PathVariable Long roomId, Authentication authentication) {
        if (rateLimiter.checkEventTrigger(authentication.getName())) {
            throw new RateLimitException("이벤트 생성 요청이 너무 빠릅니다.", 3);
        }
        return narratorService.triggerEvent(roomId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 이벤트 선택 → 디렉터 모드 시작 (SSE)
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

        // 이벤트 선택 → 디렉터 모드로 LLM 호출 (SSE)
        chatStreamService.sendEventSelectStream(roomId, request.detail(), request.energyCost(), emitter);

        return emitter;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. [👀 계속 지켜보기] — SYSTEM_DIRECTOR 주입 (SSE)
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

        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> {});

        // SYSTEM_DIRECTOR 프롬프트 주입 → 이벤트 심화 (SSE)
        chatStreamService.sendDirectorWatchStream(roomId, emitter);

        return emitter;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. [시간 넘기기] — 시간/장소 전환 (SSE)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

        // 시간 넘기기 — 에너지 1, 시스템 나레이션으로 환경 전환 (SSE)
        chatStreamService.sendTimeSkipStream(roomId, emitter);

        return emitter;
    }

    // ━━━ DTOs ━━━

    public record SelectEventRequest(String detail, int energyCost) {}
}