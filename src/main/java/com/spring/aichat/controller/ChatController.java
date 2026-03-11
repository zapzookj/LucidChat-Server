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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 채팅 API 컨트롤러
 *
 * [Phase 5] MongoDB 마이그레이션:
 * - ChatLogRepository(JPA) → ChatLogMongoRepository
 * - ChatLog → ChatLogDocument
 * - toDto(): getId() → String
 *
 * [Phase 5.1] 신규 엔드포인트:
 * - PATCH /rooms/{roomId}/logs/{logId}/rate — 유저 평가 (좋아요/싫어요)
 * - DELETE /rooms/{roomId}/logs/{logId} — 단건 메시지 삭제
 *
 * [Phase 5.2] 변경:
 * - rateChatLog: dislikeReason 전달
 * - toDto: dislikeReason 포함
 *
 * [Phase 5.5-IT] 신규 엔드포인트:
 * - POST /rooms/{roomId}/logs/{logId}/unlock-thought — 속마음 해금 (에너지 -1)
 * - toDto: hasInnerThought, innerThought, thoughtUnlocked 포함
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.1] 유저 평가 (RLHF)
    //  [Phase 5.2] dislikeReason 추가
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * ASSISTANT 메시지에 좋아요/싫어요 평가 토글
     *
     * - 같은 rating을 다시 보내면 해제 (토글)
     * - 다른 rating을 보내면 변경
     * - [Phase 5.2] DISLIKE 시 dislikeReason 사유 카테고리 전달 가능
     *
     * @return { "rating": "LIKE" | "DISLIKE" | "" }
     */
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @PatchMapping("/rooms/{roomId}/logs/{logId}/rate")
    public Map<String, String> rateChatLog(
        @PathVariable Long roomId,
        @PathVariable String logId,
        @RequestBody @Valid RateChatLogRequest request
    ) {
        String updatedRating = chatService.rateChatLog(
            logId, roomId, request.rating(), request.dislikeReason());

        Map<String, String> result = new HashMap<>();
        result.put("rating", updatedRating != null ? updatedRating : "");
        return result;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.1] 단건 메시지 삭제
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 개별 채팅 로그 삭제 (USER 또는 ASSISTANT)
     * SYSTEM 메시지(나레이션)는 삭제 불가.
     */
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @DeleteMapping("/rooms/{roomId}/logs/{logId}")
    public void deleteSingleLog(
        @PathVariable Long roomId,
        @PathVariable String logId
    ) {
        chatService.deleteSingleChatLog(logId, roomId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-IT] 속마음 해금
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 속마음 해금 API
     *
     * 유저가 말풍선 클릭 시 호출.
     * 에너지 -1 차감 후, 실제 속마음 텍스트를 반환.
     * 이미 해금된 경우 에너지 차감 없이 텍스트만 반환.
     *
     * @return { "innerThought": "실제 속마음 텍스트", "energyCost": 1 }
     */
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    @PostMapping("/rooms/{roomId}/logs/{logId}/unlock-thought")
    public Map<String, Object> unlockInnerThought(
        @PathVariable Long roomId,
        @PathVariable String logId,
        Authentication authentication
    ) {
        String innerThought = chatService.unlockInnerThought(
            logId, roomId, authentication.getName());

        Map<String, Object> result = new HashMap<>();
        result.put("innerThought", innerThought);
        result.put("energyCost", 1);
        return result;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [Phase 5.5-IT] 속마음 보안 처리 포함 toDto
     *
     * - hasInnerThought: 속마음 존재 여부 (항상 전달)
     * - innerThought: 해금(thoughtUnlocked=true)된 경우만 실제 텍스트 전달, 아니면 null
     * - thoughtUnlocked: 해금 완료 여부
     */
    private ChatLogResponse toDto(ChatLogDocument doc) {
        // [Phase 5.5-IT] 보안: 해금 전이면 텍스트 은닉
        String visibleInnerThought = doc.isThoughtUnlocked() ? doc.getInnerThought() : null;

        return new ChatLogResponse(
            doc.getId(),
            doc.getRole(),
            doc.getRawContent(),
            doc.getCleanContent(),
            doc.getEmotionTag(),
            doc.getCreatedAt(),
            doc.getRating(),
            doc.getDislikeReason(),
            doc.hasInnerThought(),       // [Phase 5.5-IT] 존재 플래그
            visibleInnerThought,         // [Phase 5.5-IT] 보안 처리된 텍스트
            doc.isThoughtUnlocked()      // [Phase 5.5-IT] 해금 여부
        );
    }
}