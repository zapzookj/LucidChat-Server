package com.spring.aichat.controller;

import com.spring.aichat.dto.theater.TheaterRequests.ResumeFromInterventionRequest;
import com.spring.aichat.dto.theater.TheaterRequests.StartInterventionRequest;
import com.spring.aichat.dto.theater.TheaterResponses.InterventionResume;
import com.spring.aichat.dto.theater.TheaterResponses.InterventionStart;
import com.spring.aichat.service.theater.TheaterInterventionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * [Phase 5.5-Theater] 난입(Intervention) 엔드포인트
 *
 * POST  /api/v1/theater/rooms/{roomId}/intervention/start    — 난입 시작 (에너지 차감)
 * POST  /api/v1/theater/rooms/{roomId}/intervention/resume   — Theater 흐름 복귀
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/theater/rooms/{roomId}/intervention")
public class TheaterInterventionController {

    private final TheaterInterventionService interventionService;

    @PostMapping("/start")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public InterventionStart startIntervention(
        @PathVariable Long roomId,
        @RequestBody(required = false) StartInterventionRequest request,
        Authentication authentication
    ) {
        String trigger = request != null && request.trigger() != null
            ? request.trigger() : "USER_INITIATED";
        return interventionService.startIntervention(roomId, authentication.getName(), trigger);
    }

    @PostMapping("/resume")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public InterventionResume resumeFromIntervention(
        @PathVariable Long roomId,
        @RequestBody @Valid ResumeFromInterventionRequest request,
        Authentication authentication
    ) {
        return interventionService.resumeFromIntervention(
            roomId, authentication.getName(), request.checkpointToken());
    }
}