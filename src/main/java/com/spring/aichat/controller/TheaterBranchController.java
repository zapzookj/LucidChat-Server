package com.spring.aichat.controller;

import com.spring.aichat.domain.enums.BranchLevel;
import com.spring.aichat.dto.theater.TheaterRequests.ChooseBranchRequest;
import com.spring.aichat.dto.theater.TheaterResponses.BranchOption;
import com.spring.aichat.dto.theater.TheaterResponses.BranchOptions;
import com.spring.aichat.service.theater.TheaterBranchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [Phase 5.5-Theater] 분기 엔드포인트
 *
 * POST /api/v1/theater/rooms/{roomId}/branches/location       — 장소 선택 분기 요청
 * POST /api/v1/theater/rooms/{roomId}/branches/scene          — 씬 분기 생성 (LLM)
 * POST /api/v1/theater/rooms/{roomId}/branches/choose         — 선택 확정
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/theater/rooms/{roomId}/branches")
public class TheaterBranchController {

    private final TheaterBranchService branchService;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  장소 선택
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping("/location")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public BranchOptions getLocationBranch(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        return branchService.generateLocationBranch(roomId, authentication.getName());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  씬 분기 생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record GenerateSceneBranchRequest(
        String level,
        String contextSummary
    ) {}

    @PostMapping("/scene")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public BranchOptions generateSceneBranch(
        @PathVariable Long roomId,
        @RequestBody GenerateSceneBranchRequest request,
        Authentication authentication
    ) {
        BranchLevel lvl;
        try {
            lvl = BranchLevel.valueOf(request.level().toUpperCase());
        } catch (Exception e) {
            lvl = BranchLevel.MINOR;
        }
        return branchService.generateSceneBranch(
            roomId, authentication.getName(), lvl, request.contextSummary());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  선택 확정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record ConfirmBranchRequest(
        String level,
        int chosenIndex,
        String branchToken,
        List<BranchOption> optionsSnapshot
    ) {}

    @PostMapping("/choose")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Void> chooseBranch(
        @PathVariable Long roomId,
        @RequestBody ConfirmBranchRequest request,
        Authentication authentication
    ) {
        BranchLevel lvl;
        try {
            lvl = BranchLevel.valueOf(request.level().toUpperCase());
        } catch (Exception e) {
            lvl = BranchLevel.MINOR;
        }
        branchService.applyBranchChoice(
            roomId, authentication.getName(), lvl,
            request.chosenIndex(), request.branchToken(),
            request.optionsSnapshot()
        );
        return ResponseEntity.ok().build();
    }
}