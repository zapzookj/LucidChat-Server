package com.spring.aichat.controller.admin;

import com.spring.aichat.domain.enums.UserStatus;
import com.spring.aichat.dto.admin.AdminUserDetail;
import com.spring.aichat.dto.admin.AdminUserSummary;
import com.spring.aichat.dto.admin.EnergyAdjustRequest;
import com.spring.aichat.dto.admin.ReasonRequest;
import com.spring.aichat.dto.admin.StatusChangeRequest;
import com.spring.aichat.dto.admin.SubscriptionGrantRequest;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.admin.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자 유저 관리 API. /api/v1/admin/** 은 SecurityConfig 에서 ROLE_ADMIN 게이트.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public Page<AdminUserSummary> search(
        @RequestParam(required = false) String query,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(
            Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "id"));
        return adminUserService.search(query, pageable);
    }

    @GetMapping("/{id}")
    public AdminUserDetail detail(@PathVariable Long id) {
        return adminUserService.getDetail(id);
    }

    @PostMapping("/{id}/energy")
    public AdminUserDetail energy(@PathVariable Long id, @RequestBody @Valid EnergyAdjustRequest req, Authentication auth) {
        return adminUserService.adjustEnergy(auth.getName(), id, req.amount(), req.reason());
    }

    @PostMapping("/{id}/subscription")
    public AdminUserDetail grantSubscription(@PathVariable Long id, @RequestBody @Valid SubscriptionGrantRequest req, Authentication auth) {
        return adminUserService.grantSubscription(auth.getName(), id, req.tier(), req.reason());
    }

    @PostMapping("/{id}/subscription/clear")
    public AdminUserDetail clearSubscription(@PathVariable Long id, @RequestBody(required = false) ReasonRequest req, Authentication auth) {
        return adminUserService.clearSubscription(auth.getName(), id, req != null ? req.reason() : null);
    }

    @PostMapping("/{id}/status")
    public AdminUserDetail status(@PathVariable Long id, @RequestBody @Valid StatusChangeRequest req, Authentication auth) {
        UserStatus status;
        try {
            status = UserStatus.valueOf(req.status());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "알 수 없는 상태: " + req.status());
        }
        return adminUserService.changeStatus(auth.getName(), id, status, req.reason());
    }

    @PostMapping("/{id}/adult/release")
    public AdminUserDetail releaseAdult(@PathVariable Long id, @RequestBody(required = false) ReasonRequest req, Authentication auth) {
        return adminUserService.releaseAdult(auth.getName(), id, req != null ? req.reason() : null);
    }
}
