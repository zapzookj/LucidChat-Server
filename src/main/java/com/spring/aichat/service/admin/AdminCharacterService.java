package com.spring.aichat.service.admin;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.dto.admin.CharacterAdminResponse;
import com.spring.aichat.dto.admin.CharacterVisibilityRequest;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 관리자 캐릭터 노출 관리 (Phase 6). 문제 캐릭터 즉시 비노출 토글. */
@Service
@RequiredArgsConstructor
public class AdminCharacterService {

    private final CharacterRepository characterRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<CharacterAdminResponse> list() {
        return characterRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
            .stream().map(CharacterAdminResponse::from).toList();
    }

    @Transactional
    public CharacterAdminResponse updateVisibility(String actor, Long id, CharacterVisibilityRequest req) {
        Character c = characterRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "캐릭터를 찾을 수 없습니다: " + id));

        if (req.hidden() != null) c.setHidden(req.hidden());
        if (req.storyAvailable() != null) c.setStoryAvailable(req.storyAvailable());
        if (req.theaterAvailable() != null) c.setTheaterAvailable(req.theaterAvailable());

        auditLogService.record(actor, "CHARACTER_VISIBILITY", "CHARACTER", String.valueOf(id),
            String.format("hidden=%s story=%s theater=%s (사유: %s)",
                c.isHidden(), c.isStoryAvailable(), c.isTheaterAvailable(), req.reason()));
        return CharacterAdminResponse.from(c);
    }
}
