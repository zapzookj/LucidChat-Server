package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.admin.QualityLogResponse;
import com.spring.aichat.dto.admin.QualitySummary;
import com.spring.aichat.service.admin.AdminQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 RLHF 품질 대시보드. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/quality")
public class AdminQualityController {

    private final AdminQualityService adminQualityService;

    @GetMapping("/summary")
    public QualitySummary summary() {
        return adminQualityService.summary();
    }

    @GetMapping("/dislikes")
    public Page<QualityLogResponse> dislikes(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "30") int size) {
        return adminQualityService.recentDislikes(
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
