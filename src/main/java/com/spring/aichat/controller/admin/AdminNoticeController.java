package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.notice.NoticeResponse;
import com.spring.aichat.dto.notice.NoticeUpsertRequest;
import com.spring.aichat.service.notice.NoticeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 관리자 공지사항 관리. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/notices")
public class AdminNoticeController {

    private final NoticeService noticeService;

    @GetMapping
    public List<NoticeResponse> list() {
        return noticeService.adminList();
    }

    @PostMapping
    public NoticeResponse create(@RequestBody @Valid NoticeUpsertRequest req, Authentication auth) {
        return noticeService.create(auth.getName(), req);
    }

    @PutMapping("/{id}")
    public NoticeResponse update(@PathVariable Long id, @RequestBody @Valid NoticeUpsertRequest req, Authentication auth) {
        return noticeService.update(auth.getName(), id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, Authentication auth) {
        noticeService.delete(auth.getName(), id);
    }
}
