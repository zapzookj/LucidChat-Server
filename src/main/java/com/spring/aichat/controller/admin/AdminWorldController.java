package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.admin.AdminContentDtos;
import com.spring.aichat.dto.admin.ReasonRequest;
import com.spring.aichat.service.admin.AdminWorldService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [2026-11-09] 관리자 공식 세계관 관리.
 * <pre>
 * GET   /api/v1/admin/worlds                    목록
 * GET   /api/v1/admin/worlds/{id}               상세
 * PATCH /api/v1/admin/worlds/{id}               부분 편집 (시드 잠금)
 * POST  /api/v1/admin/worlds/{id}/active        활성/비활성 = 소프트 삭제·복구 (시드 잠금)
 * POST  /api/v1/admin/worlds/{id}/revert-seed   시드 재적용 + 잠금 해제
 * </pre>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/worlds")
public class AdminWorldController {

    private final AdminWorldService adminWorldService;

    @GetMapping
    public List<AdminContentDtos.WorldSummary> list() {
        return adminWorldService.list();
    }

    @GetMapping("/{id}")
    public AdminContentDtos.WorldDetail detail(@PathVariable String id) {
        return adminWorldService.detail(id);
    }

    @PatchMapping("/{id}")
    public AdminContentDtos.WorldDetail edit(@PathVariable String id,
                                             @RequestBody AdminContentDtos.WorldEditRequest req,
                                             Authentication auth) {
        return adminWorldService.edit(auth.getName(), id, req);
    }

    @PostMapping("/{id}/active")
    public AdminContentDtos.WorldDetail active(@PathVariable String id,
                                               @RequestBody AdminContentDtos.WorldActiveRequest req,
                                               Authentication auth) {
        return adminWorldService.setActive(auth.getName(), id, req);
    }

    @PostMapping("/{id}/revert-seed")
    public AdminContentDtos.RevertSeedResult revertSeed(@PathVariable String id,
                                                        @RequestBody(required = false) ReasonRequest req,
                                                        Authentication auth) {
        return adminWorldService.revertSeed(auth.getName(), id, req == null ? null : req.reason());
    }
}
