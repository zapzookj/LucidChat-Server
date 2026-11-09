package com.spring.aichat.service.admin;

import com.spring.aichat.config.CharacterSeedProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.CharacterSource;
import com.spring.aichat.dto.admin.AdminContentDtos;
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
import java.util.Optional;

/**
 * 관리자 캐릭터 관리 (Phase 6 노출 토글 + 2026-11-09 콘텐츠 RUD).
 *
 * <p>삭제는 소프트 삭제(hidden)만 둔다 — 채팅방·스토리 히로인·루틴·Mongo 로그가 캐릭터를 참조한다.
 *
 * <p>[시드 잠금] 공식 캐릭터는 부팅마다 YAML 시드로 덮인다. 시드가 관리하는 필드(콘텐츠 전부 +
 * storyAvailable/theaterAvailable)를 어드민이 바꾸면 {@code seedLocked}를 켜서 시더가 건너뛰게 한다.
 * hidden은 시드 밖이라 잠그지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminCharacterService {

    private final CharacterRepository characterRepository;
    private final AuditLogService auditLogService;
    private final CharacterSeedProperties characterSeedProperties;

    @Transactional(readOnly = true)
    public List<CharacterAdminResponse> list() {
        return characterRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
            .stream().map(CharacterAdminResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AdminContentDtos.CharacterDetail detail(Long id) {
        Character c = load(id);
        return AdminContentDtos.CharacterDetail.from(c, findSeed(c).isPresent());
    }

    @Transactional
    public CharacterAdminResponse updateVisibility(String actor, Long id, CharacterVisibilityRequest req) {
        Character c = load(id);
        boolean seedFieldChanged =
            (req.storyAvailable() != null && req.storyAvailable() != c.isStoryAvailable())
                || (req.theaterAvailable() != null && req.theaterAvailable() != c.isTheaterAvailable());

        if (req.hidden() != null) c.setHidden(req.hidden());
        if (req.storyAvailable() != null) c.setStoryAvailable(req.storyAvailable());
        if (req.theaterAvailable() != null) c.setTheaterAvailable(req.theaterAvailable());
        // story/theater 토글은 시드가 덮는 필드다 — 잠그지 않으면 다음 배포에 원복된다
        if (seedFieldChanged && findSeed(c).isPresent()) c.lockSeed();

        auditLogService.record(actor, "CHARACTER_VISIBILITY", "CHARACTER", String.valueOf(id),
            clip(String.format("hidden=%s story=%s theater=%s seedLocked=%s (사유: %s)",
                c.isHidden(), c.isStoryAvailable(), c.isTheaterAvailable(), c.isSeedLocked(), req.reason())));
        return CharacterAdminResponse.from(c);
    }

    @Transactional
    public AdminContentDtos.CharacterDetail edit(String actor, Long id, AdminContentDtos.CharacterEditRequest req) {
        Character c = load(id);
        List<String> changed;
        try {
            changed = c.applyAdminEdit(req.toEdit());
        } catch (IllegalArgumentException e) {
            // 트랜잭션 롤백으로 일부만 바뀐 엔티티는 영속되지 않는다
            throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        boolean seedManaged = findSeed(c).isPresent();
        if (!changed.isEmpty()) {
            if (seedManaged) c.lockSeed();
            auditLogService.record(actor, "CHARACTER_EDIT", "CHARACTER", String.valueOf(id),
                clip(String.format("fields=%s seedLocked=%s (사유: %s)", changed, c.isSeedLocked(), req.reason())));
        }
        return AdminContentDtos.CharacterDetail.from(c, seedManaged);
    }

    /**
     * 시드 재적용 + 잠금 해제. 시드에 값이 없는(null) 필드는 applySeed 관례상 현재 값이 남는다.
     * 시드에 없는 캐릭터(UGC·시드에서 빠진 공식)는 잠금만 푼다.
     */
    @Transactional
    public AdminContentDtos.RevertSeedResult revertSeed(String actor, Long id, String reason) {
        Character c = load(id);
        Optional<CharacterSeedProperties.CharacterSeed> seed = findSeed(c);
        seed.ifPresent(c::applySeed);
        c.unlockSeed();
        auditLogService.record(actor, "CHARACTER_SEED_REVERT", "CHARACTER", String.valueOf(id),
            clip(String.format("seedFound=%s (사유: %s)", seed.isPresent(), reason)));
        return new AdminContentDtos.RevertSeedResult(seed.isPresent());
    }

    private Character load(Long id) {
        return characterRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "캐릭터를 찾을 수 없습니다: " + id));
    }

    /** 공식 캐릭터이고 현재 프로필의 시드 목록에 같은 slug가 있으면 시드 관리 대상. */
    private Optional<CharacterSeedProperties.CharacterSeed> findSeed(Character c) {
        if (c.getSource() != CharacterSource.OFFICIAL || characterSeedProperties.characters() == null) {
            return Optional.empty();
        }
        return characterSeedProperties.characters().stream()
            .filter(s -> s.slug() != null && s.slug().equals(c.getSlug()))
            .findFirst();
    }

    /** audit_logs.summary는 500자 — 긴 사유가 INSERT를 깨지 않게 자른다. */
    static String clip(String s) {
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }
}
