package com.spring.aichat.controller;

import com.spring.aichat.dto.notice.NoticeResponse;
import com.spring.aichat.service.notice.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 유저 대면 공지사항 — 게시된 항목만. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notices")
public class NoticeController {

    private final NoticeService noticeService;

    @GetMapping
    public List<NoticeResponse> list() {
        return noticeService.publicList();
    }

    @GetMapping("/{id}")
    public NoticeResponse get(@PathVariable Long id) {
        return noticeService.get(id);
    }
}
