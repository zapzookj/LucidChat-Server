package com.spring.aichat.controller;

import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.dto.chat.ChatLogResponse;
import com.spring.aichat.dto.chat.ChatRoomInfoResponse;
import com.spring.aichat.dto.chat.SendChatRequest;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 채팅 API 컨트롤러
 *
 * [Phase 5] MongoDB 마이그레이션:
 * - ChatLogRepository(JPA) → ChatLogMongoRepository
 * - ChatLog → ChatLogDocument
 * - toDto(): getId() → String
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatLogMongoRepository chatLogRepository;
    private final ApiRateLimiter rateLimiter;

    /**
     * 채팅 전송: 특정 방에 메시지 보내기
     */
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @PostMapping("/rooms/{roomId}/messages")
    public SendChatResponse sendRestful(
        @PathVariable Long roomId,
        @RequestBody @Valid SendChatRequest request,
        Authentication authentication
    ) {
        // ── Rate Limit: 3초에 1회 ──
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다. 잠시 후 다시 시도해주세요.", 3);
        }
        return chatService.sendMessage(roomId, request.message());
    }

    /**
     * 채팅 로그 조회 (페이지네이션)
     *
     * [MongoDB 최적화]
     * findByRoomId는 복합 인덱스 {roomId:1, createdAt:-1}로 커버됨.
     * Sort.by(DESC, "createdAt")가 인덱스 방향과 일치하여 인-메모리 정렬 불필요.
     */
    @GetMapping("/rooms/{roomId}/logs")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public Page<ChatLogResponse> getLogs(
        @PathVariable Long roomId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        return chatLogRepository.findByRoomId(roomId, pageable)
            .map(this::toDto);
    }

    @PostMapping("/rooms/{roomId}/init")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public void init(@PathVariable Long roomId, Authentication authentication) {
        // ── Rate Limit: 5초에 1회 ──
        if (rateLimiter.checkChatInit(authentication.getName())) {
            throw new RateLimitException("초기화 요청이 너무 빠릅니다.", 5);
        }
        chatService.initializeChatRoom(roomId);
    }

    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @GetMapping("/rooms/{roomId}")
    public ChatRoomInfoResponse getRoomInfo(
        @PathVariable Long roomId
    ) {
        return chatService.getChatRoomInfo(roomId);
    }

    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @DeleteMapping("/rooms/{roomId}")
    public void deleteRoom(
        @PathVariable Long roomId
    ) {
        chatService.deleteChatRoom(roomId);
    }

    private ChatLogResponse toDto(ChatLogDocument doc) {
        return new ChatLogResponse(
            doc.getId(),
            doc.getRole(),
            doc.getRawContent(),
            doc.getCleanContent(),
            doc.getEmotionTag(),
            doc.getCreatedAt()
        );
    }
}