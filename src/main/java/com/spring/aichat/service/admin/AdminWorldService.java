package com.spring.aichat.service.admin;

import com.spring.aichat.config.WorldSeedProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.BgmMode;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.world.World;
import com.spring.aichat.domain.world.WorldRepository;
import com.spring.aichat.dto.admin.AdminContentDtos;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * [2026-11-09] 관리자 공식 세계관 관리 — 조회·편집·활성 토글(소프트 삭제)·시드로 되돌리기.
 *
 * <p>World PK는 {@link WorldId} enum이라 하드 삭제는 의미가 없다(다음 부팅에 시더가 재생성).
 * 세계관 행은 전부 YAML 시드 관리 대상이라, 편집·활성 토글한 필드는 필드 단위 시드 잠금에 더해진다.
 * UGC 세계관(UgcWorld)은 범위 밖 — 기존 UGC 심사 화면 소관.
 */
@Service
@RequiredArgsConstructor
public class AdminWorldService {

    private final WorldRepository worldRepository;
    private final CharacterRepository characterRepository;
    private final WorldSeedProperties worldSeedProperties;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<AdminContentDtos.WorldSummary> list() {
        Map<WorldId, Long> counts = characterRepository.findAll().stream()
            .filter(c -> c.getWorldId() != null)
            .collect(Collectors.groupingBy(Character::getWorldId, Collectors.counting()));
        return worldRepository.findAll().stream()
            .sorted(Comparator.comparingInt(World::getDisplayOrder).thenComparing(w -> w.getId().name()))
            .map(w -> AdminContentDtos.WorldSummary.from(w, findSeed(w.getId()).isPresent(),
                counts.getOrDefault(w.getId(), 0L)))
            .toList();
    }

    @Transactional(readOnly = true)
    public AdminContentDtos.WorldDetail detail(String id) {
        World w = load(id);
        return AdminContentDtos.WorldDetail.from(w, findSeed(w.getId()).isPresent(), bgmOptions());
    }

    @Transactional
    public AdminContentDtos.WorldDetail edit(String actor, String id, AdminContentDtos.WorldEditRequest req) {
        World w = load(id);
        List<String> changed;
        try {
            changed = w.applyAdminEdit(req.toEdit());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        boolean seedManaged = findSeed(w.getId()).isPresent();
        if (!changed.isEmpty()) {
            if (seedManaged) w.lockSeedFields(changed);
            auditLogService.record(actor, "WORLD_EDIT", "WORLD", w.getId().name(),
                AdminCharacterService.clip(String.format("fields=%s seedLocked=%s (사유: %s)",
                    changed, w.isSeedLocked(), req.reason())));
        }
        return AdminContentDtos.WorldDetail.from(w, seedManaged, bgmOptions());
    }

    /** 소프트 삭제/복구. 시더가 active도 덮으므로 시드 관리 행이면 잠근다. */
    @Transactional
    public AdminContentDtos.WorldDetail setActive(String actor, String id, AdminContentDtos.WorldActiveRequest req) {
        if (req == null || req.active() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "active 값이 필요합니다");
        }
        World w = load(id);
        boolean seedManaged = findSeed(w.getId()).isPresent();
        if (w.isActive() != req.active()) {
            w.setActive(req.active());
            if (seedManaged) w.lockSeedFields(List.of("active"));
            auditLogService.record(actor, req.active() ? "WORLD_ACTIVATE" : "WORLD_DEACTIVATE", "WORLD",
                w.getId().name(), AdminCharacterService.clip(String.format("active=%s seedLocked=%s (사유: %s)",
                    w.isActive(), w.isSeedLocked(), req.reason())));
        }
        return AdminContentDtos.WorldDetail.from(w, seedManaged, bgmOptions());
    }

    @Transactional
    public AdminContentDtos.RevertSeedResult revertSeed(String actor, String id, String reason) {
        World w = load(id);
        Optional<WorldSeedProperties.WorldSeed> seed = findSeed(w.getId());
        seed.ifPresent(w::applySeed);
        w.unlockSeed();
        auditLogService.record(actor, "WORLD_SEED_REVERT", "WORLD", w.getId().name(),
            AdminCharacterService.clip(String.format("seedFound=%s (사유: %s)", seed.isPresent(), reason)));
        return new AdminContentDtos.RevertSeedResult(seed.isPresent());
    }

    private World load(String id) {
        WorldId worldId;
        try {
            worldId = WorldId.valueOf(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "세계관을 찾을 수 없습니다: " + id);
        }
        return worldRepository.findById(worldId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "세계관을 찾을 수 없습니다: " + id));
    }

    private Optional<WorldSeedProperties.WorldSeed> findSeed(WorldId id) {
        if (worldSeedProperties.worlds() == null) return Optional.empty();
        return worldSeedProperties.worlds().stream()
            .filter(s -> id.name().equals(s.id()))
            .findFirst();
    }

    private static List<String> bgmOptions() {
        return Arrays.stream(BgmMode.values()).map(Enum::name).toList();
    }
}
