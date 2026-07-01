package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.faq.FaqResponse;
import com.spring.aichat.dto.faq.FaqUpsertRequest;
import com.spring.aichat.service.faq.FaqService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 관리자 QnA(FAQ) 에디터. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/faq")
public class AdminFaqController {

    private final FaqService faqService;

    @GetMapping
    public List<FaqResponse> list() {
        return faqService.adminList();
    }

    @PostMapping
    public FaqResponse create(@RequestBody @Valid FaqUpsertRequest req, Authentication auth) {
        return faqService.create(auth.getName(), req);
    }

    @PutMapping("/{id}")
    public FaqResponse update(@PathVariable Long id, @RequestBody @Valid FaqUpsertRequest req, Authentication auth) {
        return faqService.update(auth.getName(), id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, Authentication auth) {
        faqService.delete(auth.getName(), id);
    }
}
