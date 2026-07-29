package com.spring.aichat.service.illustration.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.SceneIllustrationProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.dto.chat.AiJsonOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [2026-07-30 B-2/A-1 재피벗] L1 다중인물 규약 + TIPO/Concat 그래프 검증 — 디오라마 검증
 * 테스트의 개별 포팅(순수 단위 — DB/RunPod 불필요). 태그는 LLM 산출 무가공 통과를 검증한다.
 */
class SceneRenderServiceTest {

    private final ScenePromptAssembler assembler = new ScenePromptAssembler();
    private final SceneRenderService service =
        new SceneRenderService(props(false, null), assembler, null, null, null, null, null);

    private static SceneIllustrationProperties props(boolean enabled, Boolean tipoEnabled) {
        return new SceneIllustrationProperties(enabled, null,
            new SceneIllustrationProperties.Generation(null, null, null, tipoEnabled, null));
    }

    private static Character heroine(String name, String appearanceTags) {
        return Character.createUgc(new Character.UgcCharacterSpec(
            1L, name, "ugc-" + name, "system", "model",
            "tagline", "desc", "role", "personality", "tone",
            "appearance", "clothing", "backstory", "core", "flaws", "quirks",
            "greeting", "intro", "http://img", "http://thumb", "DEFAULT",
            null, null, "160cm", "likes", "dislikes", "hobby", "무드", "quote",
            appearanceTags, "kuudere", null));
    }

    private static AiJsonOutput out(Boolean skip, AiJsonOutput.SceneIllustrationSpec spec) {
        return new AiJsonOutput(null, List.of(), 0, null, null, null, null, null,
            null, null, null, null, null, null, null, skip, spec);
    }

    // ━━━━━━━━━━ L1 다중인물 규약 (다중 모드 = 씬 레이어 + 블록) ━━━━━━━━━━

    @Test
    @DisplayName("팔씨름 씬(여2+남1) — 다중 모드: 씬 레이어에 counts·행위·장소, 블록에만 외형, 태그 무가공 통과")
    void armWrestlingSceneConvention() {
        Character mia = heroine("미아", "pink hair, twintails, blue eyes");
        Character jun = heroine("준", "short black hair, brown eyes");
        Character yuna = heroine("유나", "silver hair, braid, ice blue eyes");

        AiJsonOutput o = out(false, new AiJsonOutput.SceneIllustrationSpec(
            "classroom, indoors, daytime",
            "table, elbows on table, clasped hands, face-to-face",
            List.of(
                new AiJsonOutput.SceneCast("미아", "heroine", "female", "gritted teeth", "leaning forward"),
                new AiJsonOutput.SceneCast("준", "heroine", "male", "smirk", "elbow rest"),
                new AiJsonOutput.SceneCast("유나", "heroine", "female", "neutral", "crossed arms, standing, watching")
            )));

        SceneRenderService.SceneRenderPlan plan = service.planRender(List.of(mia, jun, yuna), o);
        ScenePromptAssembler.ScenePrompt p = plan.prompt();

        // 다중 모드: TIPO는 씬 레이어만
        assertFalse(p.tipoFull());
        assertTrue(p.sceneTags().contains("2girls, 1boy"), p.sceneTags());
        assertFalse(p.sceneTags().contains("3girls"), p.sceneTags());
        assertTrue(p.sceneTags().contains("classroom"), p.sceneTags());
        // [렉시콘 롤백 원칙] LLM 산출 무가공 통과 — 재작성 없음
        assertTrue(p.sceneTags().contains("clasped hands"), "무가공 통과: " + p.sceneTags());
        // 씬 레이어에 외형 태그 없음(블록 전담)
        assertFalse(p.sceneTags().contains("pink hair"), p.sceneTags());
        // 캐릭터 블록: 성별 앵커 + 약한 강조 + 원문 보존
        assertEquals(3, p.actorBlocks().size());
        assertTrue(p.actorBlocks().get(0).startsWith("(1girl, pink hair"), p.actorBlocks().get(0));
        assertTrue(p.actorBlocks().get(1).startsWith("(1boy, short black hair"), p.actorBlocks().get(1));
        assertTrue(p.actorBlocks().get(2).contains("silver hair"), "원문 보존: " + p.actorBlocks().get(2));
        assertTrue(p.actorBlocks().get(0).endsWith(":1.1)"), p.actorBlocks().get(0));
        // 다중 모드 밴: TIPO의 외형 태그 추가 차단
        assertTrue(p.banTags().contains("solo") && p.banTags().contains("pink hair"), p.banTags());

        // 해시 결정론(+성별 포함)
        assertEquals(plan.sceneHash(), service.planRender(List.of(mia, jun, yuna), o).sceneHash());
        assertEquals(64, plan.sceneHash().length());
    }

    @Test
    @DisplayName("히로인+유저(성별 미지정) — 다중 모드: 유저는 1boy·faceless male 블록, 마지막 배치")
    void heroineAndUserScene() {
        Character mia = heroine("미아", "long grey hair, blue eyes");

        AiJsonOutput o = out(false, new AiJsonOutput.SceneIllustrationSpec("bedroom, night", "hug",
            List.of(
                new AiJsonOutput.SceneCast("미아", "heroine", null, "blush", "arms around neck"),
                new AiJsonOutput.SceneCast("user", "user", null, null, "from behind"))));

        ScenePromptAssembler.ScenePrompt p = service.planRender(List.of(mia), o).prompt();
        assertFalse(p.tipoFull());
        assertTrue(p.sceneTags().contains("1girl, 1boy"), p.sceneTags());
        assertEquals(2, p.actorBlocks().size());
        assertTrue(p.actorBlocks().get(0).startsWith("(1girl,"), p.actorBlocks().get(0));
        assertTrue(p.actorBlocks().get(1).startsWith("(1boy, faceless male"), "유저 블록은 마지막: " + p.actorBlocks());
        // faceless male 단독은 죽은 태그(PoC 12/12) — head out of frame 병기
        assertTrue(p.actorBlocks().get(1).contains("head out of frame"), p.actorBlocks().get(1));
    }

    // ━━━━━━━━━━ TIPO 풀 모드 (1인/무인물 — PoC 경로) ━━━━━━━━━━

    @Test
    @DisplayName("1인 씬 — 풀 TIPO 모드: 플랫 프롬프트(블록/가중치 없음)·태그 원문 보존, 반대 성별 카운트 밴")
    void singleHeroineFullTipo() {
        Character mia = heroine("미아", "silver hair, red eyes, miko");

        AiJsonOutput o = out(false, new AiJsonOutput.SceneIllustrationSpec("shrine, autumn leaves", "holding broom",
            List.of(new AiJsonOutput.SceneCast("미아", "heroine", "female", "expressionless", "standing"))));

        ScenePromptAssembler.ScenePrompt p = service.planRender(List.of(mia), o).prompt();
        assertTrue(p.tipoFull());
        assertTrue(p.actorBlocks().isEmpty());
        assertTrue(p.sceneTags().contains("1girl"), p.sceneTags());
        assertTrue(p.sceneTags().contains("silver hair"), "원문 보존: " + p.sceneTags());
        assertTrue(p.sceneTags().contains("shrine"), p.sceneTags());
        assertFalse(p.sceneTags().contains(":1.1"), "풀 모드는 가중치 블록 없음: " + p.sceneTags());
        assertTrue(p.banTags().contains("multiple girls") && p.banTags().contains("1boy"), p.banTags());
    }

    @Test
    @DisplayName("cast 미제공 폴백 — 방 히로인 솔로(풀 TIPO), DB 영속 태그 사용")
    void fallbackHeroineSolo() {
        Character mia = heroine("미아", "pink hair, blue eyes");
        ScenePromptAssembler.ScenePrompt p = service.planRender(List.of(mia), out(false, null)).prompt();
        assertTrue(p.tipoFull());
        assertTrue(p.sceneTags().contains("pink hair"), p.sceneTags());
    }

    @Test
    @DisplayName("인물 없는 배경 씬 — 풀 TIPO + no humans + 인물 태그 밴, 마침표/개행은 콤마 정리")
    void sceneryOnly() {
        ScenePromptAssembler.ScenePrompt p =
            assembler.assemble("empty rainy street. neon lights\nreflective puddles", "", List.of());
        assertTrue(p.tipoFull());
        assertTrue(p.sceneTags().contains("no humans"), p.sceneTags());
        assertTrue(p.sceneTags().contains("neon lights, reflective puddles"), "구두점 정리만 수행: " + p.sceneTags());
        assertTrue(p.banTags().contains("1girl"), p.banTags());
    }

    // ━━━━━━━━━━ 워크플로 그래프 (TIPO + ConditioningConcat) ━━━━━━━━━━

    private SceneWorkflowFactory factory(Boolean tipoEnabled) {
        SceneWorkflowFactory f = new SceneWorkflowFactory(new ObjectMapper(), props(false, tipoEnabled));
        f.loadTemplate();
        return f;
    }

    @Test
    @DisplayName("다중 모드 그래프 — TIPO 씬 레이어 + 블록 인코드/Concat 체인 + positive 재배선")
    void multiModeGraph() {
        ScenePromptAssembler.ScenePrompt prompt = new ScenePromptAssembler.ScenePrompt(
            "quality, 2girls, hug, cafe", List.of("(1girl, pink hair:1.1)", "(1girl, grey hair:1.1)"),
            "solo, blonde hair", false, "full");

        ObjectNode wf = factory(true).buildScene(prompt, "test_prefix");

        assertEquals("quality, 2girls, hug, cafe", wf.path("30").path("inputs").path("tags").asText());
        assertEquals("solo, blonde hair", wf.path("30").path("inputs").path("ban_tags").asText());
        // 블록 인코드 40/41 + Concat 체인 50/51
        assertEquals("(1girl, pink hair:1.1)", wf.path("40").path("inputs").path("text").asText());
        assertEquals("(1girl, grey hair:1.1)", wf.path("41").path("inputs").path("text").asText());
        assertEquals("31", wf.path("50").path("inputs").path("conditioning_to").path(0).asText());
        assertEquals("40", wf.path("50").path("inputs").path("conditioning_from").path(0).asText());
        assertEquals("50", wf.path("51").path("inputs").path("conditioning_to").path(0).asText());
        // KSampler/FaceDetailer positive → 마지막 concat(51)
        assertEquals("51", wf.path("11").path("inputs").path("positive").path(0).asText());
        assertEquals("51", wf.path("17").path("inputs").path("positive").path(0).asText());
    }

    @Test
    @DisplayName("풀 TIPO 모드 그래프 — Concat 없음, positive는 베이스 인코드(31) 유지")
    void fullTipoGraph() {
        ScenePromptAssembler.ScenePrompt prompt = new ScenePromptAssembler.ScenePrompt(
            "quality, 1girl, shrine", List.of(), "multiple girls", true, "quality, 1girl, shrine");

        ObjectNode wf = factory(true).buildScene(prompt, "test_prefix");

        assertEquals("quality, 1girl, shrine", wf.path("30").path("inputs").path("tags").asText());
        assertTrue(wf.path("40").isMissingNode());
        assertTrue(wf.path("50").isMissingNode());
        assertEquals("31", wf.path("11").path("inputs").path("positive").path(0).asText());
    }

    @Test
    @DisplayName("TIPO 비활성 — 노드30 제거, 노드31에 씬 태그 직결")
    void tipoDisabledGraph() {
        ScenePromptAssembler.ScenePrompt prompt = new ScenePromptAssembler.ScenePrompt(
            "quality, 1girl, shrine", List.of(), "ban", true, "quality, 1girl, shrine");

        ObjectNode wf = factory(false).buildScene(prompt, "test_prefix");

        assertTrue(wf.path("30").isMissingNode());
        assertEquals("quality, 1girl, shrine", wf.path("31").path("inputs").path("text").asText());
    }
}
