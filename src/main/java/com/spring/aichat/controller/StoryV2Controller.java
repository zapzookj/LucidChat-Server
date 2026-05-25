package com.spring.aichat.controller;

import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.story.StoryV2Requests.CreateStoryV2Request;
import com.spring.aichat.dto.story.StoryV2Requests.ResetStoryRequest;
import com.spring.aichat.dto.story.StoryV2Requests.SendStoryV2MessageRequest;
import com.spring.aichat.dto.story.StoryV2Responses.CreateContextResponse;
import com.spring.aichat.dto.story.StoryV2Responses.CreateStoryV2Response;
import com.spring.aichat.dto.story.StoryV2Responses.NotificationResponse;
import com.spring.aichat.dto.story.StoryV2Responses.StoryRoomV2DetailResponse;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.story.ChatStreamServiceV2;
import com.spring.aichat.service.story.OffscreenNotificationService;
import com.spring.aichat.service.story.StoryV2Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * [Story V2] V2 디렉터 시점 World 탐험 API 컨트롤러
 *
 * <p>V1 {@link StoryController}는 *디렉터/이벤트/시간넘기기* 엔드포인트로 *유지* (Sandbox 자산 이관).
 * 본 컨트롤러는 V2 패러다임 전용으로 *별도 분리*.
 *
 * <pre>
 * GET  /api/v2/story/worlds/{worldId}/create-context
 *                                           — CreateFlow 진입 데이터 (히로인 풀 / 페르소나 프리셋 / 시작 장소)
 * POST /api/v2/story/rooms                  — V2 방 생성 (또는 overwrite 시 기존 방 reset)
 * GET  /api/v2/story/rooms/{roomId}         — 방 상세 (히로인 상태 / presence / 알림 카운트)
 * POST /api/v2/story/rooms/{roomId}/reset   — 스토리 초기화 (페르소나 포함 옵션)
 * POST /api/v2/story/rooms/{roomId}/messages/stream (SSE)
 *                                           — 메시지/액션 전송 (4종 actionType 지원)
 * GET  /api/v2/story/rooms/{roomId}/notifications
 *                                           — 미확인 알림 조회 (UI 토스트)
 * POST /api/v2/story/rooms/{roomId}/notifications/{notificationId}/read
 *                                           — 알림 읽음 마킹
 * </pre>
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/v1/v2/story")
public class StoryV2Controller {

    private final StoryV2Service storyV2Service;
    private final ChatStreamServiceV2 chatStreamServiceV2;
    private final OffscreenNotificationService notificationService;
    private final UserRepository userRepository;
    private final ApiRateLimiter rateLimiter;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  CreateFlow
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * CreateFlow 진입 시 단일 호출로 받는 통합 데이터:
     * - World 카드 정보
     * - 선택 가능 히로인 풀
     * - 사전 정의 페르소나 프리셋
     * - 시작 가능 장소 풀
     * - 자유 페르소나 BM 보유 여부
     */
    @GetMapping("/worlds/{worldIdStr}/create-context")
    public CreateContextResponse getCreateContext(
        @PathVariable("worldIdStr") String worldIdStr,
        Authentication authentication
    ) {
        WorldId worldId = parseWorldId(worldIdStr);
        User user = resolveUser(authentication);
        return storyV2Service.getCreateContext(worldId, user);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  방 생성 / 초기화
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * V2 방 생성.
     * <p>같은 World에 기존 방이 있고 {@code overwriteExisting=false}이면 409 응답 →
     * UI는 confirm 모달 후 {@code overwriteExisting=true}로 재호출.
     */
    @PostMapping("/rooms")
    public CreateStoryV2Response createRoom(
        @RequestBody @Valid CreateStoryV2Request request,
        Authentication authentication
    ) {
        User user = resolveUser(authentication);
        return storyV2Service.createOrReuseRoom(user, request);
    }

    @GetMapping("/rooms/{roomId}")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public StoryRoomV2DetailResponse getRoomDetail(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        User user = resolveUser(authentication);
        return storyV2Service.getRoomDetail(roomId, user);
    }

    /**
     * 스토리 초기화. {@code includePersona} 옵션으로 페르소나 보존/리셋 결정.
     */
    @PostMapping("/rooms/{roomId}/reset")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Void> resetStory(
        @PathVariable Long roomId,
        @RequestBody @Valid ResetStoryRequest request,
        Authentication authentication
    ) {
        User user = resolveUser(authentication);
        storyV2Service.resetStory(roomId, user, request);
        return ResponseEntity.noContent().build();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  메시지 SSE 스트리밍
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * V2 메시지/액션 전송 (SSE 스트리밍).
     * <p>일반 채팅 + 4종 액션 UI ({@code MOVE/TIME_ADVANCE/NEXT_SCENE}) 모두 통합 처리.
     * <p>SSE 이벤트: {@code first_scene} → {@code final_result} (V1 패턴과 동일).
     *
     * <p>[Timeout 정책]
     * V1 패턴과 동일하게 SSE timeout = LLM timeout(120s) + 30s buffer = 150s.
     */
    @PostMapping(value = "/rooms/{roomId}/messages/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SseEmitter sendStream(
        @PathVariable Long roomId,
        @RequestBody @Valid SendStoryV2MessageRequest request,
        Authentication authentication
    ) {
        if (rateLimiter.checkChatSend(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 3);
        }

        SseEmitter emitter = new SseEmitter(150_000L);
        emitter.onTimeout(() -> {
            log.warn("⏱️ [V2-SSE] Emitter timeout: roomId={}", roomId);
            emitter.complete();
        });
        emitter.onError(ex ->
            log.warn("⚠️ [V2-SSE] Emitter error: roomId={} | {}", roomId, ex.getMessage())
        );

        chatStreamServiceV2.sendMessageStream(roomId, request, emitter);
        return emitter;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  오프스크린 알림
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 미확인 알림 조회 — UI 토스트 노출용.
     * 가장 최근에 도착한 알림이 배열 앞.
     */
    @GetMapping("/rooms/{roomId}/notifications")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public List<NotificationResponse> getUnreadNotifications(@PathVariable Long roomId) {
        return notificationService.findUnreadForToast(roomId);
    }

    /**
     * 알림 읽음 마킹 — 토스트를 유저가 닫았을 때.
     * <p>읽음과 응답은 분리 — 읽음은 UI 토스트 노출 처리, 응답은 디렉터가 *그 알림을 화제로 꺼낼 때*.
     */
    @PostMapping("/rooms/{roomId}/notifications/{notificationId}/read")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Void> markNotificationRead(
        @PathVariable Long roomId,
        @PathVariable Long notificationId
    ) {
        notificationService.markRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private WorldId parseWorldId(String s) {
        try {
            return WorldId.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Invalid worldId: " + s);
        }
    }

    private User resolveUser(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
    }
}