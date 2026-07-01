package com.spring.aichat.service.notice;

import com.spring.aichat.domain.notice.Notice;
import com.spring.aichat.domain.notice.NoticeRepository;
import com.spring.aichat.dto.notice.NoticeResponse;
import com.spring.aichat.dto.notice.NoticeUpsertRequest;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 공지사항 서비스. 유저는 게시분만, 관리자는 전체 CRUD. */
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<NoticeResponse> publicList() {
        return noticeRepository.findByPublishedTrueOrderByPinnedDescPublishedAtDesc()
            .stream().map(NoticeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public NoticeResponse get(Long id) {
        return NoticeResponse.from(load(id));
    }

    @Transactional(readOnly = true)
    public List<NoticeResponse> adminList() {
        return noticeRepository.findAllByOrderByPinnedDescIdDesc()
            .stream().map(NoticeResponse::from).toList();
    }

    @Transactional
    public NoticeResponse create(String actor, NoticeUpsertRequest req) {
        Notice n = noticeRepository.save(Notice.create(
            req.title(), req.body(),
            Boolean.TRUE.equals(req.pinned()),
            req.published() == null || req.published()));
        auditLogService.record(actor, "NOTICE_CREATE", "NOTICE", String.valueOf(n.getId()), req.title());
        return NoticeResponse.from(n);
    }

    @Transactional
    public NoticeResponse update(String actor, Long id, NoticeUpsertRequest req) {
        Notice n = load(id);
        n.update(
            req.title(), req.body(),
            req.pinned() == null ? n.isPinned() : req.pinned(),
            req.published() == null ? n.isPublished() : req.published());
        auditLogService.record(actor, "NOTICE_UPDATE", "NOTICE", String.valueOf(id), req.title());
        return NoticeResponse.from(n);
    }

    @Transactional
    public void delete(String actor, Long id) {
        if (!noticeRepository.existsById(id)) throw new BusinessException(ErrorCode.NOT_FOUND, "공지를 찾을 수 없습니다: " + id);
        noticeRepository.deleteById(id);
        auditLogService.record(actor, "NOTICE_DELETE", "NOTICE", String.valueOf(id), null);
    }

    private Notice load(Long id) {
        return noticeRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공지를 찾을 수 없습니다: " + id));
    }
}
