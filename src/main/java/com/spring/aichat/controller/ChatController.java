package com.spring.aichat.controller;

import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.dto.chat.ChatLogResponse;
import com.spring.aichat.dto.chat.ChatRoomInfoResponse;
import com.spring.aichat.dto.chat.RateChatLogRequest;
import com.spring.aichat.dto.chat.SendChatRequest;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.ChatService;
import com.spring.aichat.service.stream.ChatStreamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatStreamService chatStreamService;  // [Phase 5.5-Perf] 추가
    private final ChatLogMongoRepository chatLogRepository;
    private final ApiRateLimiter rateLimiter;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Perf] SSE 스트리밍 메시지 전송
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @PostMapping(value = "/rooms/{roomId}/messages/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendStream(
        @PathVariable Long roomId,
        @RequestBody @Valid SendChatRequest request,
        Authentication authentication
    ) {
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 3);
        }

        SseEmitter emitter = new SseEmitter(120_000L);

        emitter.onTimeout(() -> {
            log.warn("⏱️ [SSE] Emitter timeout: roomId={}", roomId);
            emitter.complete();
        });
        emitter.onError(ex ->
            log.warn("⚠️ [SSE] Emitter error: roomId={} | {}", roomId, ex.getMessage())
        );

        // 비동기 처리 시작 — SseEmitter 즉시 반환
        chatStreamService.sendMessageStream(roomId, request.message(), emitter);

        return emitter;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  기존 REST 엔드포인트 (폴백용 유지)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @PostMapping("/rooms/{roomId}/messages")
    public SendChatResponse sendRestful(
        @PathVariable Long roomId,
        @RequestBody @Valid SendChatRequest request,
        Authentication authentication
    ) {
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다. 잠시 후 다시 시도해주세요.", 3);
        }
        return chatService.sendMessage(roomId, request.message());
    }

    @GetMapping("/rooms/{roomId}/logs")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public Page<ChatLogResponse> getLogs(
        @PathVariable Long roomId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return chatLogRepository.findByRoomId(roomId, pageable).map(this::toDto);
    }

    @PostMapping("/rooms/{roomId}/init")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public void init(@PathVariable Long roomId, Authentication authentication) {
        if (rateLimiter.checkChatInit(authentication.getName())) {
            throw new RateLimitException("초기화 요청이 너무 빠릅니다.", 5);
        }
        chatService.initializeChatRoom(roomId);
    }

    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @GetMapping("/rooms/{roomId}")
    public ChatRoomInfoResponse getRoomInfo(@PathVariable Long roomId) {
        return chatService.getChatRoomInfo(roomId);
    }

    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @DeleteMapping("/rooms/{roomId}")
    public void deleteRoom(@PathVariable Long roomId) {
        chatService.deleteChatRoom(roomId);
    }

    // ━━━ [Phase 5.1] 유저 평가 (RLHF) ━━━

    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @PatchMapping("/rooms/{roomId}/logs/{logId}/rate")
    public Map<String, String> rateChatLog(
        @PathVariable Long roomId, @PathVariable String logId,
        @RequestBody @Valid RateChatLogRequest request
    ) {
        String updatedRating = chatService.rateChatLog(logId, roomId, request.rating(), request.dislikeReason());
        Map<String, String> result = new HashMap<>();
        result.put("rating", updatedRating != null ? updatedRating : "");
        return result;
    }

    // ━━━ [Phase 5.1] 단건 메시지 삭제 ━━━

    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @DeleteMapping("/rooms/{roomId}/logs/{logId}")
    public void deleteSingleLog(@PathVariable Long roomId, @PathVariable String logId) {
        chatService.deleteSingleChatLog(logId, roomId);
    }

    // ━━━ [Phase 5.5-IT] 속마음 해금 ━━━

    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @PostMapping("/rooms/{roomId}/logs/{logId}/unlock-thought")
    public Map<String, Object> unlockInnerThought(
        @PathVariable Long roomId, @PathVariable String logId,
        Authentication authentication
    ) {
        String innerThought = chatService.unlockInnerThought(logId, roomId, authentication.getName());
        Map<String, Object> result = new HashMap<>();
        result.put("innerThought", innerThought);
        result.put("energyCost", 1);
        return result;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ChatLogResponse toDto(ChatLogDocument doc) {
        String visibleInnerThought = doc.isThoughtUnlocked() ? doc.getInnerThought() : null;
        return new ChatLogResponse(
            doc.getId(), doc.getRole(), doc.getRawContent(), doc.getCleanContent(),
            doc.getEmotionTag(), doc.getCreatedAt(), doc.getRating(), doc.getDislikeReason(),
            doc.hasInnerThought(), visibleInnerThought, doc.isThoughtUnlocked());
    }
}