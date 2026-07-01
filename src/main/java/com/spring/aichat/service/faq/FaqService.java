package com.spring.aichat.service.faq;

import com.spring.aichat.domain.faq.FaqEntry;
import com.spring.aichat.domain.faq.FaqEntryRepository;
import com.spring.aichat.dto.faq.FaqResponse;
import com.spring.aichat.dto.faq.FaqUpsertRequest;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 편집형 QnA(FAQ) 서비스. 유저는 게시분만, 관리자는 전체 CRUD. */
@Service
@RequiredArgsConstructor
public class FaqService {

    private final FaqEntryRepository faqRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<FaqResponse> publicList() {
        return faqRepository.findByPublishedTrueOrderByCategoryAscDisplayOrderAscIdAsc()
            .stream().map(FaqResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<FaqResponse> adminList() {
        return faqRepository.findAllByOrderByCategoryAscDisplayOrderAscIdAsc()
            .stream().map(FaqResponse::from).toList();
    }

    @Transactional
    public FaqResponse create(String actor, FaqUpsertRequest req) {
        FaqEntry f = faqRepository.save(FaqEntry.create(
            req.category(), req.question(), req.answer(),
            req.displayOrder() != null ? req.displayOrder() : 0,
            req.published() == null || req.published()));
        auditLogService.record(actor, "FAQ_CREATE", "FAQ", String.valueOf(f.getId()), req.question());
        return FaqResponse.from(f);
    }

    @Transactional
    public FaqResponse update(String actor, Long id, FaqUpsertRequest req) {
        FaqEntry f = load(id);
        f.update(
            req.category(), req.question(), req.answer(),
            req.displayOrder() != null ? req.displayOrder() : f.getDisplayOrder(),
            req.published() == null ? f.isPublished() : req.published());
        auditLogService.record(actor, "FAQ_UPDATE", "FAQ", String.valueOf(id), req.question());
        return FaqResponse.from(f);
    }

    @Transactional
    public void delete(String actor, Long id) {
        if (!faqRepository.existsById(id)) throw new BusinessException(ErrorCode.NOT_FOUND, "FAQ 항목을 찾을 수 없습니다: " + id);
        faqRepository.deleteById(id);
        auditLogService.record(actor, "FAQ_DELETE", "FAQ", String.valueOf(id), null);
    }

    private FaqEntry load(Long id) {
        return faqRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "FAQ 항목을 찾을 수 없습니다: " + id));
    }
}
