package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.admin.InjectionEventResponse;
import com.spring.aichat.dto.admin.ModerationEventResponse;
import com.spring.aichat.dto.admin.OffenderResponse;
import com.spring.aichat.service.moderation.ModerationEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 관리자 모더레이션/어뷰징 리뷰 큐. /api/v1/admin/** ROLE_ADMIN 게이트. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/moderation")
public class AdminModerationController {

    private final ModerationEventService moderationEventService;

    @GetMapping("/events")
    public Page<ModerationEventResponse> events(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "30") int size) {
        return moderationEventService.listModeration(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    @GetMapping("/injections")
    public Page<InjectionEventResponse> injections(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "30") int size) {
        return moderationEventService.listInjection(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    @GetMapping("/offenders")
    public List<OffenderResponse> offenders(@RequestParam(defaultValue = "20") int limit) {
        return moderationEventService.topOffenders(Math.min(Math.max(limit, 1), 100));
    }
}
