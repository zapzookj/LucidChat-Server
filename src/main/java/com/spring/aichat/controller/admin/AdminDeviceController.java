package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.admin.DeviceInfoResponse;
import com.spring.aichat.security.DeviceFingerprintGuard;
import com.spring.aichat.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자 디바이스/IP 어뷰징 조회 + 소프트밴 해제.
 * (섀도우밴 데이터는 가입 시 DeviceFingerprintGuard.onAccountCreated 가 호출되어야 채워진다 —
 *  프론트 FingerprintJS 연동은 별도 작업.)
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/devices")
public class AdminDeviceController {

    private final DeviceFingerprintGuard deviceGuard;
    private final AuditLogService auditLogService;

    @GetMapping("/{userId}")
    public DeviceInfoResponse info(@PathVariable Long userId) {
        String fp = deviceGuard.getFingerprint(userId);
        return new DeviceInfoResponse(
            userId,
            mask(fp),
            deviceGuard.isUserSoftBanned(userId),
            deviceGuard.getDeviceAccountCount(userId));
    }

    @PostMapping("/{userId}/unban")
    public DeviceInfoResponse unban(@PathVariable Long userId, Authentication auth) {
        boolean cleared = deviceGuard.clearSoftBanByUser(userId);
        auditLogService.record(auth.getName(), "DEVICE_SOFTBAN_CLEAR", "USER", String.valueOf(userId),
            cleared ? "소프트밴 해제" : "해제 대상 없음");
        return info(userId);
    }

    private String mask(String fp) {
        if (fp == null || fp.isBlank()) return null;
        return fp.length() <= 8 ? fp : fp.substring(0, 8) + "…";
    }
}
