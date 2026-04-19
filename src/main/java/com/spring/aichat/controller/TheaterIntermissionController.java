package com.spring.aichat.controller;

import com.spring.aichat.dto.theater.TheaterRequests.IntermissionChoiceRequest;
import com.spring.aichat.dto.theater.TheaterResponses.IntermissionResult;
import com.spring.aichat.dto.theater.TheaterResponses.IntermissionView;
import com.spring.aichat.service.theater.TheaterIntermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * [Phase 5.5-Theater] 인터미션 엔드포인트
 *
 * GET   /api/v1/theater/rooms/{roomId}/intermission          — 뷰 조회
 * POST  /api/v1/theater/rooms/{roomId}/intermission/perform  — 활동 선택 수행
 * POST  /api/v1/theater/rooms/{roomId}/intermission/finish   — 인터미션 종료
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/theater/rooms/{roomId}/intermission")
public class TheaterIntermissionController {

    private final TheaterIntermissionService intermissionService;

    @GetMapping
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public IntermissionView getView(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        return intermissionService.getIntermissionView(roomId, authentication.getName());
    }

    @PostMapping("/perform")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public IntermissionResult performActivity(
        @PathVariable Long roomId,
        @RequestBody @Valid IntermissionChoiceRequest request,
        Authentication authentication
    ) {
        return intermissionService.performActivity(
            roomId, authentication.getName(),
            request.activityId(), request.useExtraEnergy()
        );
    }

    @PostMapping("/finish")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Void> finish(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        intermissionService.finishIntermission(roomId, authentication.getName());
        return ResponseEntity.ok().build();
    }
}