package com.spring.aichat.controller;

import com.spring.aichat.dto.faq.FaqResponse;
import com.spring.aichat.service.faq.FaqService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 유저 대면 QnA(FAQ) — 게시된 항목만. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/faq")
public class FaqController {

    private final FaqService faqService;

    @GetMapping
    public List<FaqResponse> list() {
        return faqService.publicList();
    }
}
