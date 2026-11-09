package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.admin.AdminContentDtos;
import com.spring.aichat.dto.admin.CharacterAdminResponse;
import com.spring.aichat.dto.admin.ReasonRequest;
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

    // ━━━ [2026-11-09] 콘텐츠 RUD — 삭제는 visibility(hidden) 소프트 삭제 ━━━

    @GetMapping("/{id:\\d+}")
    public AdminContentDtos.CharacterDetail detail(@PathVariable Long id) {
        return adminCharacterService.detail(id);
    }

    /** 부분 갱신 — null 필드는 변경 없음. 시드 관리 행이면 시드 잠금이 켜진다. */
    @PatchMapping("/{id:\\d+}")
    public AdminContentDtos.CharacterDetail edit(@PathVariable Long id,
                                                 @RequestBody AdminContentDtos.CharacterEditRequest req,
                                                 Authentication auth) {
        return adminCharacterService.edit(auth.getName(), id, req);
    }

    @PostMapping("/{id:\\d+}/revert-seed")
    public AdminContentDtos.RevertSeedResult revertSeed(@PathVariable Long id,
                                                        @RequestBody(required = false) ReasonRequest req,
                                                        Authentication auth) {
        return adminCharacterService.revertSeed(auth.getName(), id, req == null ? null : req.reason());
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

    /** [2026-07-30 P0] 공개 강제 철회 — 부적절 공개 캐릭터 즉시 내림 (PENDING 불요, PUBLIC 대상). */
    @PostMapping("/ugc/{id}/unpublish")
    public ResponseEntity<Void> ugcUnpublish(@PathVariable Long id,
                                             @RequestBody UgcReviewDtos.UnpublishRequest req,
                                             Authentication auth) {
        adminUgcReviewService.unpublish(auth.getName(), id, req == null ? null : req.note());
        return ResponseEntity.ok().build();
    }
}
