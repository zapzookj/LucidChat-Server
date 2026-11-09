package com.spring.aichat.service.admin;

import com.spring.aichat.config.CharacterSeedProperties;
import com.spring.aichat.config.CharacterSeeder;
import com.spring.aichat.config.WorldSeedProperties;
import com.spring.aichat.config.WorldSeeder;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.CharacterSource;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.world.World;
import com.spring.aichat.domain.world.WorldRepository;
import com.spring.aichat.dto.admin.AdminContentDtos;
import com.spring.aichat.dto.admin.CharacterVisibilityRequest;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.service.audit.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** [2026-11-09] 백오피스 콘텐츠 RUD — 부분 편집·검증·시드 잠금·되돌리기 계약. */
class AdminContentRudTest {

    // ━━━ fixtures ━━━

    private static Character official(String slug) {
        Character c = new Character("아이리", slug, "SYS", "model-a");
        ReflectionTestUtils.setField(c, "id", 1L);
        ReflectionTestUtils.setField(c, "source", CharacterSource.OFFICIAL);
        return c;
    }

    private static CharacterSeedProperties.CharacterSeed charSeed(String slug, String name) {
        CharacterSeedProperties.CharacterSeed seed = mock(CharacterSeedProperties.CharacterSeed.class);
        when(seed.slug()).thenReturn(slug);
        when(seed.name()).thenReturn(name);
        when(seed.baseSystemPrompt()).thenReturn("SEED-SYS");
        when(seed.llmModelName()).thenReturn("seed-model");
        return seed;
    }

    private static WorldSeedProperties.WorldSeed worldSeed(String id, String displayName, Boolean active) {
        WorldSeedProperties.WorldSeed seed = mock(WorldSeedProperties.WorldSeed.class);
        when(seed.id()).thenReturn(id);
        when(seed.displayName()).thenReturn(displayName);
        when(seed.active()).thenReturn(active);
        return seed;
    }

    private static Character.AdminContentEdit charEdit(String name, String tagline, String baseSystemPrompt) {
        return new AdminContentDtos.CharacterEditRequest(
            name, tagline, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null,
            baseSystemPrompt, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, "r").toEdit();
    }

    private static AdminContentDtos.CharacterEditRequest charEditReq(String name) {
        return new AdminContentDtos.CharacterEditRequest(
            name, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, "오탈자 수정");
    }

    private static World world(WorldId id) {
        return World.create(id, "저택", "t", "d", "h", "th", "o", "ROMANTIC", "m", false, 1);
    }

    // ━━━ Character.applyAdminEdit ━━━

    @Test
    @DisplayName("null 필드는 그대로, 바뀐 필드만 보고하고 선택 필드의 공란은 null로 저장")
    void characterPartialEdit() {
        Character c = official("airi");
        ReflectionTestUtils.setField(c, "tagline", "옛 태그라인");

        List<String> changed = c.applyAdminEdit(charEdit("아이리", "  ", null));

        assertEquals(List.of("tagline"), changed); // name은 동일값 → 미보고
        assertNull(c.getTagline());
        assertEquals("SYS", c.getBaseSystemPrompt());
    }

    @Test
    @DisplayName("필수 필드 공란·길이 초과는 거부")
    void characterValidation() {
        Character c = official("airi");
        assertThrows(IllegalArgumentException.class, () -> c.applyAdminEdit(charEdit(null, null, " ")));
        assertThrows(IllegalArgumentException.class, () -> c.applyAdminEdit(charEdit("가".repeat(51), null, null)));
    }

    // ━━━ World.applyAdminEdit ━━━

    @Test
    @DisplayName("세계관 편집: 무효 BGM은 아무 필드도 바꾸기 전에 거부")
    void worldInvalidBgmRejectedBeforeMutation() {
        World w = world(WorldId.values()[0]);
        var edit = new World.AdminContentEdit("새 이름", null, null, null, null, null, "NOPE", null, null, null);
        assertThrows(IllegalArgumentException.class, () -> w.applyAdminEdit(edit));
        assertEquals("저택", w.getDisplayName());
    }

    @Test
    @DisplayName("세계관 편집: 바뀐 필드만 보고")
    void worldEdit() {
        World w = world(WorldId.values()[0]);
        var edit = new World.AdminContentEdit("저택", "새 태그", null, null, null, null, "TENSE", null, true, 1);
        assertEquals(List.of("tagline", "defaultBgm", "secretAllowed"), w.applyAdminEdit(edit));
        assertTrue(w.isSecretAllowed());
    }

    // ━━━ 시더가 잠긴 행을 건너뛴다 ━━━

    @Test
    @DisplayName("CharacterSeeder: 잠긴 필드만 보존하고 나머지 필드는 시드를 따른다")
    void characterSeederPreservesOnlyLockedFields() {
        Character c = official("airi");
        c.lockSeedFields(List.of("name"));
        CharacterRepository repo = mock(CharacterRepository.class);
        when(repo.findBySlug("airi")).thenReturn(Optional.of(c));
        CharacterSeeder seeder = new CharacterSeeder(repo,
            new CharacterSeedProperties(List.of(charSeed("airi", "시드이름"))));
        ReflectionTestUtils.setField(seeder, "updateExisting", true);

        seeder.seedAllCharacters();

        assertEquals("아이리", c.getName());                 // 잠긴 필드 — 어드민 값 유지
        assertEquals("SEED-SYS", c.getBaseSystemPrompt());   // 안 잠긴 필드 — 시드 반영
        assertEquals("seed-model", c.getLlmModelName());
        assertTrue(c.isSeedLocked());                        // 시더는 잠금을 풀지 않는다
        verify(repo).save(c);
    }

    @Test
    @DisplayName("WorldSeeder: 잠긴 필드(active·displayName)만 보존, 잠금 해제 후엔 전부 시드로")
    void worldSeederRespectsFieldLocks() {
        WorldId a = WorldId.values()[0];
        World w = world(a);
        w.setActive(false);
        w.lockSeedFields(List.of("active", "displayName"));
        WorldRepository repo = mock(WorldRepository.class);
        when(repo.findById(a)).thenReturn(Optional.of(w));
        WorldSeeder seeder = new WorldSeeder(repo,
            new WorldSeedProperties(List.of(worldSeed(a.name(), "시드이름", null))));
        ReflectionTestUtils.setField(seeder, "updateExisting", true);

        seeder.seedAllWorlds();
        assertEquals("저택", w.getDisplayName());
        assertFalse(w.isActive());
        assertEquals("", w.getTagline()); // 안 잠긴 필드 — 시드(null→"") 반영

        w.unlockSeed();
        seeder.seedAllWorlds();
        assertEquals("시드이름", w.getDisplayName());
        assertTrue(w.isActive()); // active 미지정 시드 = true (시더 관례)
    }

    @Test
    @DisplayName("난이도 공란은 미지정(null)으로 비운다")
    void difficultyBlankClears() {
        Character c = official("airi");
        ReflectionTestUtils.setField(c, "difficulty", com.spring.aichat.domain.enums.CharacterDifficulty.HARD);
        var edit = new AdminContentDtos.CharacterEditRequest(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, "",
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, "r").toEdit();
        assertEquals(List.of("difficulty"), c.applyAdminEdit(edit));
        assertNull(c.getDifficulty());
        assertThrows(IllegalArgumentException.class, () -> c.applyAdminEdit(
            new AdminContentDtos.CharacterEditRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "WHAT",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "r").toEdit()));
    }

    // ━━━ 서비스: 잠금 판정·되돌리기 ━━━

    @Test
    @DisplayName("공식 캐릭터 편집은 시드 잠금, 되돌리기는 시드 재적용 후 잠금 해제")
    void characterEditLocksAndRevertUnlocks() {
        Character c = official("airi");
        CharacterRepository repo = mock(CharacterRepository.class);
        when(repo.findById(1L)).thenReturn(Optional.of(c));
        AuditLogService audit = mock(AuditLogService.class);
        var svc = new AdminCharacterService(repo, audit,
            new CharacterSeedProperties(List.of(charSeed("airi", "시드이름"))));

        var detail = svc.edit("admin", 1L, charEditReq("새이름"));
        assertTrue(detail.seedManaged());
        assertTrue(detail.seedLocked());
        assertEquals(List.of("name"), detail.lockedFields());
        verify(audit).record(eq("admin"), eq("CHARACTER_EDIT"), eq("CHARACTER"), eq("1"), contains("name"));

        var result = svc.revertSeed("admin", 1L, "원복");
        assertTrue(result.seedFound());
        assertFalse(c.isSeedLocked());
        assertEquals("시드이름", c.getName());
    }

    @Test
    @DisplayName("UGC 캐릭터 편집은 시드 대상이 아니라 잠그지 않는다 / 변경 없으면 감사 로그도 없다")
    void ugcEditDoesNotLock() {
        Character c = official("ugc-1");
        ReflectionTestUtils.setField(c, "source", CharacterSource.UGC);
        CharacterRepository repo = mock(CharacterRepository.class);
        when(repo.findById(1L)).thenReturn(Optional.of(c));
        AuditLogService audit = mock(AuditLogService.class);
        var svc = new AdminCharacterService(repo, audit,
            new CharacterSeedProperties(List.of(charSeed("ugc-1", "x"))));

        assertFalse(svc.edit("admin", 1L, charEditReq("새이름")).seedLocked());
        svc.edit("admin", 1L, charEditReq("새이름")); // 동일값 재전송
        verify(audit, times(1)).record(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("story 토글은 시드 필드라 잠그고, hidden 토글은 잠그지 않는다")
    void visibilityLocking() {
        Character c = official("airi");
        CharacterRepository repo = mock(CharacterRepository.class);
        when(repo.findById(1L)).thenReturn(Optional.of(c));
        var svc = new AdminCharacterService(repo, mock(AuditLogService.class),
            new CharacterSeedProperties(List.of(charSeed("airi", "x"))));

        svc.updateVisibility("admin", 1L, new CharacterVisibilityRequest(true, null, null, "숨김"));
        assertFalse(c.isSeedLocked());
        svc.updateVisibility("admin", 1L, new CharacterVisibilityRequest(null, !c.isStoryAvailable(), null, "스토리"));
        assertEquals(java.util.Set.of("storyAvailable"), c.getSeedLockedFieldSet());
    }

    @Test
    @DisplayName("검증 실패는 400(BusinessException)으로 변환")
    void validationMapsToBusinessException() {
        Character c = official("airi");
        CharacterRepository repo = mock(CharacterRepository.class);
        when(repo.findById(1L)).thenReturn(Optional.of(c));
        var svc = new AdminCharacterService(repo, mock(AuditLogService.class), new CharacterSeedProperties(null));
        assertThrows(BusinessException.class, () -> svc.edit("admin", 1L, charEditReq(" ")));
    }

    @Test
    @DisplayName("세계관 비활성화(소프트 삭제)는 시드 잠금, 없는 id는 NOT_FOUND")
    void worldDeactivate() {
        WorldId a = WorldId.values()[0];
        World w = world(a);
        WorldRepository repo = mock(WorldRepository.class);
        when(repo.findById(a)).thenReturn(Optional.of(w));
        var svc = new AdminWorldService(repo, mock(CharacterRepository.class),
            new WorldSeedProperties(List.of(worldSeed(a.name(), "시드", true))), mock(AuditLogService.class));

        var detail = svc.setActive("admin", a.name(), new AdminContentDtos.WorldActiveRequest(false, "중단"));
        assertEquals(List.of("active"), detail.lockedFields());
        assertFalse(detail.active());
        assertTrue(detail.seedLocked());
        assertThrows(BusinessException.class, () -> svc.detail("NOT_A_WORLD"));
    }
}
