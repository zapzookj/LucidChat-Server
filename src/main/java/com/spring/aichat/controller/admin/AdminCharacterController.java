package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.admin.CharacterAdminResponse;
import com.spring.aichat.dto.admin.CharacterVisibilityRequest;
import com.spring.aichat.dto.admin.UgcReviewDtos;
import com.spring.aichat.service.admin.AdminCharacterService;
import com.spring.aichat.service.admin.AdminUgcReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자 캐릭터 콘텐츠 관리.
 *
 * <p>[UGC v1] /ugc 하위 — 승인 큐(공개 신청 + Secret 단독 신청 통합):
 * <pre>
 * GET  /api/v1/admin/characters/ugc/queue        대기 목록
 * GET  /api/v1/admin/characters/ugc/{id}         상세 검토 (에셋 15종 + 설정 한 화면)
 * POST /api/v1/admin/characters/ugc/{id}/review  판정 {publishApprove?, secretApprove?, note}
 * </pre>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/characters")
public class AdminCharacterController {

    private final AdminCharacterService adminCharacterService;
    private final AdminUgcReviewService adminUgcReviewService;

    @GetMapping
    public List<CharacterAdminResponse> list() {
        return adminCharacterService.list();
    }

    @PostMapping("/{id}/visibility")
    public CharacterAdminResponse visibility(@PathVariable Long id,
                                             @RequestBody CharacterVisibilityRequest req,
                                             Authentication auth) {
        return adminCharacterService.updateVisibility(auth.getName(), id, req);
    }

    // ━━━ [UGC v1] 승인 큐 ━━━

    @GetMapping("/ugc/queue")
    public UgcReviewDtos.QueueResponse ugcQueue() {
        return adminUgcReviewService.queue();
    }

    @GetMapping("/ugc/{id}")
    public UgcReviewDtos.DetailResponse ugcDetail(@PathVariable Long id) {
        return adminUgcReviewService.detail(id);
    }

    /** [프롬프트 인스펙션] 일러 생성 실프롬프트 재구성 — 튜닝 참조용. */
    @GetMapping("/ugc/{id}/prompts")
    public UgcReviewDtos.PromptInspection ugcPrompts(@PathVariable Long id) {
        return adminUgcReviewService.prompts(id);
    }

    @PostMapping("/ugc/{id}/review")
    public ResponseEntity<Void> ugcReview(@PathVariable Long id,
                                          @RequestBody UgcReviewDtos.ReviewRequest req,
                                          Authentication auth) {
        adminUgcReviewService.review(auth.getName(), id, req);
        return ResponseEntity.ok().build();
    }
}
