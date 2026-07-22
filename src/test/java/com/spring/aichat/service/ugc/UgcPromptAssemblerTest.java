package com.spring.aichat.service.ugc;

import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.dto.ugc.StructuredConcept;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-07-21 캐릭터 빌더 개선] 프롬프트 조립 계약 테스트 —
 * 와일드카드 재구성·동적 감정 슬롯·자세 템플릿·배경 가중치.
 */
class UgcPromptAssemblerTest {

    private final UgcPromptAssembler assembler = new UgcPromptAssembler(
        new UgcPipelineProperties(null, null, null, null, null, null, null));

    @Test
    @DisplayName("와일드카드: 눈·식별점 태그만 채택(헤어 제외) + persona + 감정 표정 태그 포함")
    void wildcardComposition() {
        String wc = assembler.faceDetailWildcard(
            List.of("silver hair", "long hair", "blue eyes", "tsurime", "mole under eye", "bangs", "slit pupils"),
            List.of("kuudere", "cold beauty"),
            EmotionTag.JOY);

        assertThat(wc).startsWith("detailed beautiful eyes");
        assertThat(wc).contains("blue eyes").contains("mole under eye").contains("slit pupils");
        assertThat(wc).contains("kuudere").contains("cold beauty");
        assertThat(wc).contains("smile"); // JOY wf2 표정 태그
        // 헤어 태그 제외 — 디테일 패스에서 무의미·어텐션 희석 (2026-07-21 실측)
        assertThat(wc).doesNotContain("silver hair").doesNotContain("long hair").doesNotContain("bangs");
    }

    @Test
    @DisplayName("동적 감정 슬롯: override 있으면 상수 대신 주입, 구도 가드 문장은 항상 유지")
    void emotionOverrideSlot() {
        StructuredConcept.EmotionPromptOverride override = new StructuredConcept.EmotionPromptOverride(
            "a rare unguarded smile breaking through her cool mask",
            "one hand brushing her hair back, chin lifted slightly");

        String dynamic = assembler.qwenEmotionPrompt(EmotionTag.JOY, "kuudere", override);
        assertThat(dynamic).contains("unguarded smile").contains("brushing her hair back");
        assertThat(dynamic).contains("must not lean or move toward or away from the camera");

        String fallback = assembler.qwenEmotionPrompt(EmotionTag.JOY, "kuudere", null);
        assertThat(fallback).contains("bright open smile"); // 상수 폴백
        assertThat(fallback).contains("must not lean or move toward or away from the camera");
    }

    @Test
    @DisplayName("자세 템플릿: basePose 슬롯 주입 + 카메라 가드 유지, null이면 기본 스탠스 폴백")
    void posePromptSlot() {
        String dynamic = assembler.qwenPosePrompt("Arms crossed loosely, head tilted with a wry half-smile");
        assertThat(dynamic).contains("Arms crossed loosely");
        assertThat(dynamic).contains("cowboy shot");
        assertThat(dynamic).contains("must not lean toward or away from the camera");

        String fallback = assembler.qwenPosePrompt(null);
        assertThat(fallback).contains("One hand resting lightly on her hip");
    }

    @Test
    @DisplayName("WF-2 배경 보색: 어텐션 가중치 부여 (기본 1.3, 노브 반영)")
    void bgEmphasisWeights() {
        String positive = assembler.refinePositive(
            List.of("silver hair"), List.of(), EmotionTag.NEUTRAL, "light gray");
        assertThat(positive).contains("(simple background:1.2)");
        assertThat(positive).contains("(light gray background:1.3)");
        assertThat(positive).contains("(flat lighting:1.1)");

        UgcPromptAssembler tuned = new UgcPromptAssembler(new UgcPipelineProperties(
            null, null, null, null, new UgcPipelineProperties.Generation(null, null, 1.45), null, null));
        assertThat(tuned.refinePositive(List.of(), List.of(), EmotionTag.NEUTRAL, "muted teal"))
            .contains("(muted teal background:1.45)");
    }

    @Test
    @DisplayName("구도 가드: 상수 자세에 카메라 방향 기울임이 남아있지 않다 (비율 붕괴 완화)")
    void noCameraAxisPosesInConstants() {
        for (EmotionTag tag : UgcPromptAssembler.derivedEmotions()) {
            String prompt = assembler.qwenEmotionPrompt(tag, null, null);
            assertThat(prompt.toLowerCase())
                .as("emotion %s", tag)
                .doesNotContain("leaning toward the viewer")
                .doesNotContain("toward the viewer,")
                .doesNotContain("pulled back");
        }
    }
}
