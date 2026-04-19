package com.spring.aichat.controller;

import com.spring.aichat.dto.theater.TheaterRequests.*;
import com.spring.aichat.dto.theater.TheaterResponses.*;
import com.spring.aichat.service.theater.TheaterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * [Phase 5.5-Theater] Theater 진행 컨트롤러
 *
 * Scene 배치 소비 / Chapter 진행 / 재생 설정 / prefetch 등의 엔드포인트 제공.
 * 로비 관련 엔드포인트는 TheaterLobbyController가 담당.
 *
 * [Endpoints]
 * POST   /api/v1/theater/rooms/{roomId}/next-batch         — 다음 배치 요청
 * POST   /api/v1/theater/rooms/{roomId}/batch-consumed     — 배치 소비 완료 신호
 * POST   /api/v1/theater/rooms/{roomId}/chapter-end        — Chapter 종료 처리 (리포트 반환)
 * POST   /api/v1/theater/rooms/{roomId}/prefetch           — 비동기 prefetch 트리거
 * PATCH  /api/v1/theater/rooms/{roomId}/play-settings      — 재생 설정 변경
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/theater/rooms")
public class TheaterController {

    private final TheaterService theaterService;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  배치 요청
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 다음 Scene 배치 요청.
     * body.prefetch=true인 경우 에너지 차감 없이 캐시 확인 및 생성만 수행.
     */
    @PostMapping("/{roomId}/next-batch")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SceneBatch requestNextBatch(
        @PathVariable Long roomId,
        @RequestBody(required = false) NextBatchRequest request,
        Authentication authentication
    ) {
        boolean prefetch = request != null && request.prefetch();
        return theaterService.requestNextBatch(roomId, authentication.getName(), prefetch);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  배치 소비 완료 신호
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 유저가 배치의 마지막 Scene까지 감상 완료 후 호출.
     * 호감도 변화 영속화 + 상태 진행. Chapter 종료 시 chapterEnd=true 반환.
     */
    @PostMapping("/{roomId}/batch-consumed")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public Map<String, Object> onBatchConsumed(
        @PathVariable Long roomId,
        @RequestParam int batchId,
        Authentication authentication
    ) {
        boolean chapterEnd = theaterService.onBatchConsumed(roomId, authentication.getName(), batchId);
        return Map.of("chapterEnd", chapterEnd);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Chapter 종료 (리포트 빌드)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping("/{roomId}/chapter-end")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ChapterReport finalizeChapter(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        return theaterService.finalizeChapter(roomId, authentication.getName());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  비동기 prefetch 트리거
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping("/{roomId}/prefetch")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Void> triggerPrefetch(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        theaterService.prefetchNextBatchAsync(roomId);
        return ResponseEntity.accepted().build();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  재생 설정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PatchMapping("/{roomId}/play-settings")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Void> updatePlaySettings(
        @PathVariable Long roomId,
        @RequestBody @Valid UpdatePlaySettingsRequest request,
        Authentication authentication
    ) {
        theaterService.updatePlaySettings(
            roomId,
            authentication.getName(),
            request.autoPlayEnabled(),
            request.playSpeed()
        );
        return ResponseEntity.ok().build();
    }
}