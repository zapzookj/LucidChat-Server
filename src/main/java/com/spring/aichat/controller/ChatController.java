package com.spring.aichat.controller;

import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.dto.chat.ChatLogResponse;
import com.spring.aichat.dto.chat.ChatRoomInfoResponse;
import com.spring.aichat.dto.chat.RateChatLogRequest;
import com.spring.aichat.dto.chat.SendChatRequest;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.ChatService;
import com.spring.aichat.service.stream.ChatStreamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * 채팅 컨트롤러
 *
 * [Bug #4 Fix] 레거시 REST sendRestful() 엔드포인트 제거.
 *   모든 채팅 메시지 전송은 SSE 스트리밍(/messages/stream)으로 통합.
 *
 * [Bug #3 Fix] 채팅방 단위 시크릿 모드 / 페르소나 API 추가.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatStreamService chatStreamService;
    private final ChatLogMongoRepository chatLogRepository;
    private final ApiRateLimiter rateLimiter;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  SSE 스트리밍 메시지 전송 (유일한 채팅 경로)
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

        chatStreamService.sendMessageStream(roomId, request.message(), emitter);
        return emitter;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  채팅방 관리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/rooms/{roomId}/logs")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public Page<ChatLogResponse> getLogs(
        @PathVariable Long roomId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return chatLogRepository.findByRoomIdAndHiddenFalse(roomId, pageable).map(this::toDto);
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Bug #3 Fix] 채팅방 단위 설정 (시크릿 모드 / 페르소나)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 채팅방 시크릿 모드 토글
     *
     * Body: { "enabled": true }
     *
     * enabled=true: SecretModeService로 패스/해금/구독 검증
     * enabled=false: 즉시 비활성화
     */
    @PatchMapping("/rooms/{roomId}/secret-mode")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Map<String, Object>> toggleRoomSecretMode(
        @PathVariable Long roomId,
        @RequestBody Map<String, Boolean> body,
        Authentication authentication
    ) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        chatService.toggleRoomSecretMode(roomId, enabled, authentication.getName());
        return ResponseEntity.ok(Map.of("secretModeActive", enabled));
    }

    /**
     * 채팅방 전용 유저 페르소나 설정
     *
     * Body: { "persona": "대학교 3학년 미대생" }
     * null/빈 문자열 전송 시 → 방 전용 페르소나 해제 (유저 기본값 폴백)
     */
    @PatchMapping("/rooms/{roomId}/persona")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Map<String, Object>> updateRoomPersona(
        @PathVariable Long roomId,
        @RequestBody RoomPersonaRequest request
    ) {
        chatService.updateRoomPersona(roomId, request.persona());
        return ResponseEntity.ok(Map.of(
            "userPersona", request.persona() != null ? request.persona() : ""
        ));
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
            doc.hasInnerThought(), visibleInnerThought, doc.isThoughtUnlocked(),
            doc.getScenesJson());
    }

    // ── DTO ──
    public record RoomPersonaRequest(
        @Size(max = 500, message = "페르소나는 500자 이내로 입력해주세요.")
        String persona
    ) {}
}