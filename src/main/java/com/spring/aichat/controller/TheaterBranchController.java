package com.spring.aichat.controller;

import com.spring.aichat.dto.theater.TheaterResponses.BranchOption;
import com.spring.aichat.dto.theater.TheaterResponses.BranchOptions;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.service.theater.TheaterBranchService;
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

    /**
     * [버그픽스 B-4.b/d · docs/17_assets/defect_register.md]
     * 구 프론트 페이로드 역호환용으로 필드 구성만 남긴다 — <b>두 값 모두 서버가 무시한다.</b>
     *
     * <p>기존엔 level을 여기서 valueOf하고 실패하면 MINOR로 폴백했고(임의 레벨 LLM 호출 + 무과금 경로),
     * contextSummary는 클라이언트 문자열이 그대로 다음 배치 시스템 프롬프트까지 흘렀다.
     * 이제 둘 다 캐시된 배치의 branchSignal에서 서버가 확정한다.
     */
    public record GenerateSceneBranchRequest(
        String level,
        String contextSummary
    ) {}

    @PostMapping("/scene")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public BranchOptions generateSceneBranch(
        @PathVariable Long roomId,
        @RequestBody(required = false) GenerateSceneBranchRequest request,
        Authentication authentication
    ) {
        // request는 읽지 않는다 (위 record 주석 참조). 본문이 없어도 정상 동작한다.
        return branchService.generateSceneBranch(roomId, authentication.getName());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  선택 확정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [버그픽스 B-4.a/b/e/f · docs/17_assets/defect_register.md]
     * <b>필드 구성은 그대로 두되</b>({@code level}·{@code optionsSnapshot}은 구 프론트 페이로드
     * 역호환용) 서버는 {@code chosenIndex}와 {@code branchToken}만 읽는다.
     *
     * <p>{@code chosenIndex}를 {@code Integer}로 바꾼 이유: 원시 {@code int}는 필드 누락 시
     * Jackson이 조용히 0으로 채워 <b>0번 선택지가 확정</b>돼 버린다.
     */
    public record ConfirmBranchRequest(
        String level,
        Integer chosenIndex,
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
        if (request == null || request.chosenIndex() == null) {
            throw new BadRequestException("잘못된 선택 인덱스입니다.");
        }
        // [B-4.b/e] level 폴백(valueOf 실패 → MINOR) 제거. 레벨은 서버 오퍼 원본에서만 결정된다.
        branchService.applyBranchChoice(
            roomId, authentication.getName(),
            request.chosenIndex(), request.branchToken()
        );
        return ResponseEntity.ok().build();
    }
}