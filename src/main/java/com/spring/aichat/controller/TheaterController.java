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
     * 다음 Scene 배치 요청. <b>항상 과금된다.</b>
     *
     * <p>[버그픽스 B-5.1 · docs/17_assets/defect_register.md §B-5.1] 종전에는 요청 본문의
     * {@code prefetch=true}가 서비스의 과금 2지점을 모두 건너뛰게 했는데, 그러면서도
     * <b>반환값은 유료 호출과 완전히 동일한 SceneBatch 전문</b>이었다. 즉 자기 방 소유자면
     * 누구나 {@code {"prefetch":true}} 한 줄로 Act 1~4를 에너지 0에 완주할 수 있었다.
     *
     * <p>선행 생성은 이 엔드포인트가 아니라 전용 엔드포인트 {@code POST /{roomId}/prefetch}가
     * 담당한다 — 그쪽은 202를 내고 <b>본문을 돌려주지 않아</b> 캐시 워밍 외에는 아무것도 주지 않는다.
     * 즉 이 플래그는 애초에 존재할 이유가 없었다.
     *
     * <p>FE 무영향: 유일 호출부가 {@code requestNextBatch(roomId, false)} 하나였다.
     * {@code NextBatchRequest}는 구 클라이언트 페이로드 역호환으로 받기만 하고 <b>읽지 않는다.</b>
     */
    @PostMapping("/{roomId}/next-batch")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SceneBatch requestNextBatch(
        @PathVariable Long roomId,
        @RequestBody(required = false) NextBatchRequest request,
        Authentication authentication
    ) {
        return theaterService.requestNextBatch(roomId, authentication.getName());
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