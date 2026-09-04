package com.spring.aichat.service.illustration.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.SceneIllustrationProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.illustration.SceneIllustration;
import com.spring.aichat.domain.user.EnergySplit;
import com.spring.aichat.dto.chat.AiJsonOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [2026-07-31 에픽 B] 씬 일러 수동 트리거 계약 테스트 — 씬 디렉터 파싱·cast 검증,
 * 과금/환불 행 상태 머신, 스펙 직접 planRender, 트리거 모드 게이트.
 */
class SceneManualRequestTest {

    private final SceneDirectorService director = new SceneDirectorService(
        null, new ObjectMapper(), props("manual"), "google/gemini-3-flash-preview");

    private static SceneIllustrationProperties props(String trigger) {
        return new SceneIllustrationProperties(true, trigger, null, null, null, null);
    }

    private static Character heroine(String name, String appearanceTags) {
        return Character.createUgc(new Character.UgcCharacterSpec(
            1L, name, "ugc-" + name, "system", "model",
            "tagline", "desc", "role", null, "personality", "tone",   // age=null (안건 9-D 배선)
            "appearance", "clothing", "backstory", "core", "flaws", "quirks",
            "greeting", "intro", "http://img", "http://thumb", "DEFAULT",
            null, null, "160cm", "likes", "dislikes", "hobby", "무드", "quote",
            appearanceTags, "kuudere", null));
    }

    // ━━━━━━━━━━ 씬 디렉터 — 파싱·cast 검증 ━━━━━━━━━━

    @Test
    @DisplayName("정상 스펙 JSON을 파싱하고 명단 내 cast는 보존한다")
    void parsesValidSpec() {
        Character mia = heroine("미아", "pink hair");
        String raw = """
            {"location_description":"cafe interior, window seat",
             "action_description":"sitting across table",
             "cast":[{"ref":"미아","kind":"heroine","gender":"female","emotion":"smile, blush","pose":"leaning forward"},
                     {"ref":"user","kind":"user","gender":"male","emotion":"","pose":"pov"}]}
            """;
        AiJsonOutput.SceneIllustrationSpec spec = director.parseSpec(raw, List.of(mia));
        assertEquals("cafe interior, window seat", spec.locationDescription());
        assertEquals(2, spec.cast().size());
        assertEquals("미아", spec.cast().get(0).ref());
        assertTrue(spec.cast().get(1).isUser());
    }

    @Test
    @DisplayName("마크다운 펜스로 감싼 출력도 파싱한다 (extractJson 경유)")
    void parsesFencedJson() {
        String raw = "```json\n{\"location_description\":\"park\",\"action_description\":\"\",\"cast\":[]}\n```";
        AiJsonOutput.SceneIllustrationSpec spec = director.parseSpec(raw, List.of());
        assertEquals("park", spec.locationDescription());
    }

    @Test
    @DisplayName("명단 밖 cast ref는 제거한다 — 무명 인물 렌더 방지 (user는 항상 허용)")
    void dropsUnknownCastRefs() {
        Character mia = heroine("미아", "pink hair");
        String raw = """
            {"location_description":"street","action_description":"walking",
             "cast":[{"ref":"미아","kind":"heroine","gender":"female","emotion":"smile","pose":"walking"},
                     {"ref":"유령히로인","kind":"heroine","gender":"female","emotion":"smile","pose":"walking"},
                     {"ref":"user","kind":"user","gender":"male","emotion":"","pose":"from behind"}]}
            """;
        AiJsonOutput.SceneIllustrationSpec spec = director.parseSpec(raw, List.of(mia));
        assertEquals(2, spec.cast().size());
        assertTrue(spec.cast().stream().noneMatch(c -> "유령히로인".equals(c.ref())));
    }

    @Test
    @DisplayName("비JSON 출력은 IllegalStateException — 호출측 환불 트리거")
    void throwsOnGarbage() {
        assertThrows(IllegalStateException.class,
            () -> director.parseSpec("죄송합니다, 생성할 수 없습니다.", List.of()));
    }

    // ━━━━━━━━━━ 과금·환불 행 상태 머신 ━━━━━━━━━━

    @Test
    @DisplayName("MANUAL 행: 차감액 존재+미환불일 때만 환불 대상 — markRefunded 후 멱등 차단")
    void manualRefundGuard() {
        SceneIllustration manual = SceneIllustration.pendingManual(
            1L, 7, "hash", "prompt", 42L, new EnergySplit(2, 3));
        assertEquals("MANUAL", manual.getTriggerSource());
        assertEquals(5, manual.getEnergyCharged());
        // [D-1.2] 유료분이 행에 남고 환불 분할이 그대로 복원된다
        assertEquals(3, manual.getEnergyChargedPaid());
        assertEquals(new EnergySplit(2, 3), manual.chargedSplit());
        assertTrue(manual.refundableOnFail());

        manual.markRefunded();
        assertFalse(manual.refundableOnFail(), "환불 후 재환불 차단(멱등)");
    }

    @Test
    @DisplayName("AUTO/SKIPPED 행은 환불 대상이 아니다")
    void autoRowsNeverRefund() {
        SceneIllustration auto = SceneIllustration.pending(1L, 7, "hash", "prompt");
        assertEquals("AUTO", auto.getTriggerSource());
        assertFalse(auto.refundableOnFail());

        SceneIllustration skipped = SceneIllustration.skipped(1L, 8, "hash", 9L, "url");
        assertFalse(skipped.refundableOnFail());
    }

    // ━━━━━━━━━━ 스펙 직접 planRender (수동 경로) ━━━━━━━━━━

    @Test
    @DisplayName("씬 디렉터 스펙을 직접 planRender에 태워도 L1 규약 산출이 동일하다")
    void planRenderAcceptsSpecDirectly() {
        SceneRenderService service = new SceneRenderService(
            props("manual"), new ScenePromptAssembler(), null, null, null, null, null, null);
        Character mia = heroine("미아", "pink hair, twintails");

        // [2026-08-07 pov 픽스] 유저 pose "pov"는 정규화 대상 — 씬 레이어 이동+유저 제외가 신계약
        AiJsonOutput.SceneIllustrationSpec spec = new AiJsonOutput.SceneIllustrationSpec(
            "cafe interior", "sitting, holding hands",
            List.of(new AiJsonOutput.SceneCast("미아", "heroine", "female", "smile", "sitting"),
                    new AiJsonOutput.SceneCast("user", "user", "male", "", "pov")));

        // [E-2.15] userMale=null = 스냅샷 미지정 → 종전대로 LLM cast.gender 폴백(이 테스트의 계약 유지)
        SceneRenderService.SceneRenderPlan plan = service.planRender(List.of(mia), spec, true, null);
        assertTrue(plan.prompt().sceneTags().contains("1girl"), plan.prompt().sceneTags());
        assertFalse(plan.prompt().sceneTags().contains("1boy"),
            "pov 정규화 — 유저(=카메라)는 화면 밖: " + plan.prompt().sceneTags());
        assertTrue(plan.prompt().sceneTags().contains("pov"), plan.prompt().sceneTags());
        assertTrue(plan.prompt().sceneTags().contains("sfw"), "비시크릿 sfw 게이트 유지");
        assertNotNull(plan.sceneHash());
    }

    // ━━━━━━━━━━ [E-2.15] 유저 액터 성별 — 페르소나 스냅샷이 권위 ━━━━━━━━━━

    /** 유저 액터가 화면에 남는 케이스(pov 아님)에서만 성별 태그가 의미를 갖는다. */
    private static AiJsonOutput.SceneIllustrationSpec specWithUser(String llmGender) {
        return new AiJsonOutput.SceneIllustrationSpec(
            "cafe interior", "standing side by side",
            List.of(new AiJsonOutput.SceneCast("미아", "heroine", "female", "smile", "standing"),
                    new AiJsonOutput.SceneCast("user", "user", llmGender, "", "standing")));
    }

    @Test
    @DisplayName("페르소나 스냅샷이 LLM cast.gender를 덮는다 — 여성 페르소나는 남성으로 렌더되지 않는다")
    void personaSnapshotOverridesLlmGender() {
        SceneRenderService service = new SceneRenderService(
            props("manual"), new ScenePromptAssembler(), null, null, null, null, null, null);
        Character mia = heroine("미아", "pink hair, twintails");

        // LLM은 male이라 했지만 방의 페르소나 스냅샷은 female이다 → 스냅샷이 이긴다
        String tags = service.planRender(List.of(mia), specWithUser("male"), true, false)
            .prompt().sceneTags();
        assertFalse(tags.contains("1boy"), "스냅샷(female)이 권위여야 한다: " + tags);

        // 반대 방향도 성립해야 한다 — 남성 페르소나인데 LLM이 female이라 한 경우
        String maleTags = service.planRender(List.of(mia), specWithUser("female"), true, true)
            .prompt().sceneTags();
        assertTrue(maleTags.contains("1boy"), "스냅샷(male)이 권위여야 한다: " + maleTags);
    }

    @Test
    @DisplayName("castKey가 유저 성별을 반영한다 — 페르소나 성별을 바꾸면 옛 렌더가 재사용되지 않는다")
    void sceneHashChangesWithPersonaGender() {
        SceneRenderService service = new SceneRenderService(
            props("manual"), new ScenePromptAssembler(), null, null, null, null, null, null);
        Character mia = heroine("미아", "pink hair, twintails");

        String femaleHash = service.planRender(List.of(mia), specWithUser("male"), true, false).sceneHash();
        String maleHash = service.planRender(List.of(mia), specWithUser("male"), true, true).sceneHash();

        // castKey에 스냅샷 성별을 함께 넣지 않으면 두 해시가 같아져 디덥이 성별 변경을 못 본다.
        assertNotEquals(femaleHash, maleHash,
            "scene_hash가 페르소나 성별을 인지해야 한다 — 아니면 성별을 바꿔도 옛 렌더가 재사용된다");
    }

    @Test
    @DisplayName("스냅샷 미지정(null)이면 종전대로 LLM cast.gender 폴백")
    void nullSnapshotFallsBackToLlmGender() {
        SceneRenderService service = new SceneRenderService(
            props("manual"), new ScenePromptAssembler(), null, null, null, null, null, null);
        Character mia = heroine("미아", "pink hair, twintails");

        String tags = service.planRender(List.of(mia), specWithUser("male"), true, null)
            .prompt().sceneTags();
        assertTrue(tags.contains("1boy"), "userMale=null이면 LLM 값을 그대로 쓴다: " + tags);
    }

    // ━━━━━━━━━━ 트리거 모드 게이트 ━━━━━━━━━━

    @Test
    @DisplayName("트리거 기본값은 manual — auto 명시 시에만 인밴드 경로 활성")
    void triggerDefaultsToManual() {
        assertFalse(props(null).isAutoTrigger(), "미지정=manual");
        assertFalse(props("manual").isAutoTrigger());
        assertTrue(props("auto").isAutoTrigger());
        assertEquals(5, props(null).energyCostOrDefault());
    }
}
