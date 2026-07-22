package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.UgcPipelineProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [UGC v1] 치환 엔진 계약 테스트.
 *
 * <p>핵심 보증: ① FIELD_SPEC 계약 경로만 치환된다 ② 명세 외 경로(Pod 검증 동결값)는 불변이다
 * ③ WF-1 FaceDetailer는 종원 결정값(cfg 4 / denoise 0.4)이다 ④ 템플릿 원본은 오염되지 않는다.
 */
class UgcWorkflowFactoryTest {

    private UgcWorkflowFactory factory;
    private UgcPipelineProperties props;

    @BeforeEach
    void setUp() {
        props = new UgcPipelineProperties(null, null, null, null, null, null, null);
        factory = new UgcWorkflowFactory(new ObjectMapper(), props);
        factory.loadTemplates();
    }

    // ━━━ WF-1 ━━━

    @Test
    @DisplayName("WF-1: 계약 경로(positive/seed×2/batch/prefix)가 치환된다")
    void goldenShot_substitutesContractPaths() {
        ObjectNode wf = factory.buildGoldenShot("1girl, silver hair", "job_7_golden", 111L, 222L);

        assertThat(wf.path("12").path("inputs").path("text").asText()).isEqualTo("1girl, silver hair");
        assertThat(wf.path("11").path("inputs").path("seed").asLong()).isEqualTo(111L);
        assertThat(wf.path("17").path("inputs").path("seed").asLong()).isEqualTo(222L);
        assertThat(wf.path("6").path("inputs").path("batch_size").asInt()).isEqualTo(2); // 기본 배치 2 (2026-07-20 개편)
        assertThat(wf.path("9").path("inputs").path("filename_prefix").asText()).isEqualTo("job_7_golden");
    }

    @Test
    @DisplayName("WF-1: 동결값 불변 — KSampler(euler/simple/30/4/1.0), 자산 파일명 (a)세트, 해상도 1024")
    void goldenShot_frozenValuesUntouched() {
        ObjectNode wf = factory.buildGoldenShot("p", "x", 1L, 2L);

        JsonNode ks = wf.path("11").path("inputs");
        assertThat(ks.path("steps").asInt()).isEqualTo(30);
        assertThat(ks.path("cfg").asDouble()).isEqualTo(4);
        assertThat(ks.path("sampler_name").asText()).isEqualTo("euler");
        assertThat(ks.path("scheduler").asText()).isEqualTo("simple");
        assertThat(ks.path("denoise").asDouble()).isEqualTo(1.0);

        assertThat(wf.path("1").path("inputs").path("ckpt_name").asText()).isEqualTo("wai_illustrious.safetensors");
        assertThat(wf.path("2").path("inputs").path("lora_name").asText()).isEqualTo("detail_lora.safetensors");
        assertThat(wf.path("18").path("inputs").path("model_name").asText()).isEqualTo("bbox/anime_face_yolov8.pt");

        assertThat(wf.path("6").path("inputs").path("width").asInt()).isEqualTo(1024);
        assertThat(wf.path("6").path("inputs").path("height").asInt()).isEqualTo(1024);

        // negative는 서버 상수(검증 Export 값) 유지
        assertThat(wf.path("13").path("inputs").path("text").asText()).contains("multiple girls:1.5");
    }

    @Test
    @DisplayName("WF-1 FaceDetailer: 종원 결정(2026-07-17) — FIELD_SPEC 값 cfg 4 / denoise 0.4 채택")
    void goldenShot_faceDetailerFollowsFieldSpecDecision() {
        ObjectNode wf = factory.buildGoldenShot("p", "x", 1L, 2L);

        JsonNode fd = wf.path("17").path("inputs");
        assertThat(fd.path("cfg").asDouble()).isEqualTo(4);       // Export 원본 8 → 결정값 4
        assertThat(fd.path("denoise").asDouble()).isEqualTo(0.4); // Export 원본 0.5 → 결정값 0.4
        assertThat(fd.path("steps").asInt()).isEqualTo(20);
        assertThat(fd.path("guide_size").asInt()).isEqualTo(512);
        assertThat(fd.path("max_size").asInt()).isEqualTo(1024);
        assertThat(fd.path("wildcard").asText()).isEmpty();
    }

    // ━━━ WF-2 ━━━

    @Test
    @DisplayName("WF-2: 계약 경로(image/positive/seed×2/prefix) 치환 + 검증 동결값(denoise 0.4, wildcard) 불변")
    void refine_substitutesContractPaths() {
        ObjectNode wf = factory.buildRefine("job_7_base.png", "1girl, neutral expression", null, "job_7_refine", 11L, 22L);

        assertThat(wf.path("19").path("inputs").path("image").asText()).isEqualTo("job_7_base.png");
        assertThat(wf.path("12").path("inputs").path("text").asText()).isEqualTo("1girl, neutral expression");
        assertThat(wf.path("11").path("inputs").path("seed").asLong()).isEqualTo(11L);
        assertThat(wf.path("17").path("inputs").path("seed").asLong()).isEqualTo(22L);
        assertThat(wf.path("9").path("inputs").path("filename_prefix").asText()).isEqualTo("job_7_refine");

        // 진실원본=JSON 결정: denoise 0.4(FIELD_SPEC의 0.35 아님), wildcard는 검증값 유지
        assertThat(wf.path("11").path("inputs").path("denoise").asDouble()).isEqualTo(0.4);
        assertThat(wf.path("17").path("inputs").path("cfg").asDouble()).isEqualTo(4);
        assertThat(wf.path("17").path("inputs").path("denoise").asDouble()).isEqualTo(0.4);
        assertThat(wf.path("17").path("inputs").path("wildcard").asText()).isEqualTo("detailed beautiful eyes");

        // i2i 골격: VAEEncode(20) 경유 확인
        assertThat(wf.path("20").path("class_type").asText()).isEqualTo("VAEEncode");
    }

    @Test
    @DisplayName("WF-2: refine-denoise 노브 지정 시에만 denoise 오버라이드")
    void refine_denoiseKnobOverrides() {
        UgcPipelineProperties tuned = new UgcPipelineProperties(
            null, null, null, null, new UgcPipelineProperties.Generation(null, 0.35, null), null, null);
        UgcWorkflowFactory tunedFactory = new UgcWorkflowFactory(new ObjectMapper(), tuned);
        tunedFactory.loadTemplates();

        ObjectNode wf = tunedFactory.buildRefine("a.png", "p", null, "x", 1L, 2L);
        assertThat(wf.path("11").path("inputs").path("denoise").asDouble()).isEqualTo(0.35);
        // FaceDetailer denoise는 별개 값 — 노브의 영향을 받지 않는다
        assertThat(wf.path("17").path("inputs").path("denoise").asDouble()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("WF-2: 얼굴 와일드카드 지정 시 FaceDetailer wildcard가 치환된다 (2026-07-20 얼굴 일관성 픽스)")
    void refine_faceWildcardOverrides() {
        ObjectNode wf = factory.buildRefine("a.png", "p",
            "detailed beautiful eyes, glowing ice blue eyes, long silver hair", "x", 1L, 2L);
        assertThat(wf.path("17").path("inputs").path("wildcard").asText())
            .isEqualTo("detailed beautiful eyes, glowing ice blue eyes, long silver hair");
    }

    // ━━━ WF-3 ━━━

    @Test
    @DisplayName("WF-3: 실제 Export 노드 ID(\"1\"/\"23\") 기준 치환 — BiRefNet 구성 동결")
    void cutout_substitutesContractPaths() {
        ObjectNode wf = factory.buildCutout("job_7_joy.png", "job_7_cutout");

        assertThat(wf.path("1").path("inputs").path("image").asText()).isEqualTo("job_7_joy.png");
        assertThat(wf.path("23").path("inputs").path("filename_prefix").asText()).isEqualTo("job_7_cutout");

        // 누끼 체인 동결: RemoveBackground → InvertMask → JoinImageWithAlpha (서브그래프 평탄화 ID)
        assertThat(wf.path("22:13").path("class_type").asText()).isEqualTo("RemoveBackground");
        assertThat(wf.path("22:14").path("inputs").path("bg_removal_name").asText()).isEqualTo("birefnet.safetensors");
        assertThat(wf.path("22:15").path("class_type").asText()).isEqualTo("InvertMask");
        assertThat(wf.path("22:16").path("class_type").asText()).isEqualTo("JoinImageWithAlpha");
    }

    // ━━━ 공통 ━━━

    @Test
    @DisplayName("템플릿 원본은 build 호출로 오염되지 않는다 (deepCopy 보증)")
    void templatesAreNotMutatedByBuilds() {
        factory.buildGoldenShot("polluted", "polluted", 9L, 9L);
        factory.buildRefine("polluted.png", "polluted", "polluted wildcard", "polluted", 9L, 9L);
        factory.buildCutout("polluted.png", "polluted");

        ObjectNode fresh1 = factory.buildGoldenShot("clean", "clean_prefix", 1L, 1L);
        ObjectNode fresh2 = factory.buildRefine("clean.png", "clean", null, "clean_prefix", 1L, 1L);
        ObjectNode fresh3 = factory.buildCutout("clean.png", "clean_prefix");

        assertThat(fresh1.path("12").path("inputs").path("text").asText()).isEqualTo("clean");
        assertThat(fresh2.path("19").path("inputs").path("image").asText()).isEqualTo("clean.png");
        assertThat(fresh3.path("1").path("inputs").path("image").asText()).isEqualTo("clean.png");
    }

    @Test
    @DisplayName("seed는 64-bit 양수이며 호출마다 달라진다")
    void seedsArePositiveAndRandom() {
        long a = factory.newSeed();
        long b = factory.newSeed();
        assertThat(a).isNotNegative();
        assertThat(b).isNotNegative();
        assertThat(a).isNotEqualTo(b); // 2^-63 확률의 위양성은 무시
    }

    @Test
    @DisplayName("공개 빌더(난수 seed)는 템플릿의 고정 seed를 반드시 덮어쓴다")
    void publicBuilders_alwaysOverrideTemplateSeeds() {
        // 템플릿 Export에 박힌 검증 당시 seed — 이 값이 남아 있으면 모든 유저가 같은 이미지를 받는다
        long wf1TemplateSamplerSeed = 115778582371004L;
        long wf1TemplateDetailerSeed = 234962801777585L;

        ObjectNode wf = factory.buildGoldenShot("p", "x");
        assertThat(wf.path("11").path("inputs").path("seed").asLong()).isNotEqualTo(wf1TemplateSamplerSeed);
        assertThat(wf.path("17").path("inputs").path("seed").asLong()).isNotEqualTo(wf1TemplateDetailerSeed);

        ObjectNode refine = factory.buildRefine("a.png", "p", null, "x");
        assertThat(refine.path("11").path("inputs").path("seed").asLong()).isNotEqualTo(681642381538195L);
        assertThat(refine.path("17").path("inputs").path("seed").asLong()).isNotEqualTo(650907528861058L);
    }
}
