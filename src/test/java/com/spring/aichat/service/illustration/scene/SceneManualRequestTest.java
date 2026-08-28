package com.spring.aichat.service.illustration.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.SceneIllustrationProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.illustration.SceneIllustration;
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
            1L, 7, "hash", "prompt", 42L, 5);
        assertEquals("MANUAL", manual.getTriggerSource());
        assertEquals(5, manual.getEnergyCharged());
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

        SceneRenderService.SceneRenderPlan plan = service.planRender(List.of(mia), spec, true);
        assertTrue(plan.prompt().sceneTags().contains("1girl"), plan.prompt().sceneTags());
        assertFalse(plan.prompt().sceneTags().contains("1boy"),
            "pov 정규화 — 유저(=카메라)는 화면 밖: " + plan.prompt().sceneTags());
        assertTrue(plan.prompt().sceneTags().contains("pov"), plan.prompt().sceneTags());
        assertTrue(plan.prompt().sceneTags().contains("sfw"), "비시크릿 sfw 게이트 유지");
        assertNotNull(plan.sceneHash());
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
