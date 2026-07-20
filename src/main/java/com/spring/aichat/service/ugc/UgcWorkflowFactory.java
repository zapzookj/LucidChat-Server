package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.UgcPipelineProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;

/**
 * [UGC v1] ComfyUI 워크플로 치환 엔진.
 *
 * <p>{@code resources/workflows_json/}의 API Export 3종(WF-1 황금샷 t2i / WF-2 i2i 리파인 /
 * WF-3 누끼)을 템플릿으로 로드하고, FIELD_SPEC이 계약한 경로만 잡별로 치환한다.
 * <b>명세 외 경로는 절대 건드리지 않는다</b> — 파라미터는 Pod 검증값으로 동결(진실원본 = Export JSON).
 *
 * <p><b>치환 계약</b> (2026-07-17 확정 — 실제 Export JSON 기준. FIELD_SPEC §3의 노드 ID는
 * 구버전이라 wf3는 실제 JSON의 "1"/"23"을 따른다):
 * <ul>
 *   <li>WF-1: {@code "12".inputs.text}(positive) · {@code "11"/"17".inputs.seed} ·
 *       {@code "6".inputs.batch_size} · {@code "9".inputs.filename_prefix}</li>
 *   <li>WF-2: {@code "19".inputs.image}(입력 파일명) · {@code "12".inputs.text} ·
 *       {@code "11"/"17".inputs.seed} · {@code "9".inputs.filename_prefix} ·
 *       (선택) {@code "11".inputs.denoise} — {@code ugc.generation.refine-denoise} 지정 시만</li>
 *   <li>WF-3: {@code "1".inputs.image} · {@code "23".inputs.filename_prefix}</li>
 * </ul>
 *
 * <p><b>WF-1 FaceDetailer 예외</b>: 검증 Export는 cfg 8 / denoise 0.5였으나 종원 결정(2026-07-17)으로
 * FIELD_SPEC 값(cfg 4 / denoise 0.4)을 채택 — 리소스 JSON 자체에 반영되어 있다(치환 아님).
 *
 * <p>seed 정책: API Export에는 control_after_generate가 없어 seed를 매 잡 난수로 주입하지 않으면
 * 같은 이미지가 반복된다. KSampler·FaceDetailer 모두 {@link SecureRandom} 64-bit 양수 주입.
 *
 * <p>입력 이미지 파일명은 RunPod 제출 페이로드의 {@code input.images[].name}과 <b>동일 문자열</b>이어야
 * 한다(워커가 이름으로 매칭) — 페어링 책임은 RunPod 클라이언트에 있다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UgcWorkflowFactory {

    private static final String WF1_PATH = "workflows_json/wf1_goldenshot.json";
    private static final String WF2_PATH = "workflows_json/wf2_refine.json";
    private static final String WF3_PATH = "workflows_json/wf3_rembg.json";

    private final ObjectMapper objectMapper;
    private final UgcPipelineProperties props;
    private final SecureRandom seedRandom = new SecureRandom();

    private JsonNode wf1Template;
    private JsonNode wf2Template;
    private JsonNode wf3Template;

    @PostConstruct
    void loadTemplates() {
        wf1Template = load(WF1_PATH);
        wf2Template = load(WF2_PATH);
        wf3Template = load(WF3_PATH);

        // fail-fast: 치환 계약 경로가 템플릿에 실존하는지 기동 시점에 검증 (템플릿 드리프트 방지)
        requireInput(wf1Template, WF1_PATH, "12", "text");
        requireInput(wf1Template, WF1_PATH, "11", "seed");
        requireInput(wf1Template, WF1_PATH, "17", "seed");
        requireInput(wf1Template, WF1_PATH, "6", "batch_size");
        requireInput(wf1Template, WF1_PATH, "9", "filename_prefix");

        requireInput(wf2Template, WF2_PATH, "19", "image");
        requireInput(wf2Template, WF2_PATH, "12", "text");
        requireInput(wf2Template, WF2_PATH, "11", "seed");
        requireInput(wf2Template, WF2_PATH, "11", "denoise");
        requireInput(wf2Template, WF2_PATH, "17", "seed");
        requireInput(wf2Template, WF2_PATH, "17", "wildcard");
        requireInput(wf2Template, WF2_PATH, "9", "filename_prefix");

        requireInput(wf3Template, WF3_PATH, "1", "image");
        requireInput(wf3Template, WF3_PATH, "23", "filename_prefix");

        log.info("[UGC] Workflow templates loaded: wf1/wf2/wf3 (치환 계약 검증 통과)");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  WF-1 · 황금샷 t2i (Stage 1 / 황금샷 리롤)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public ObjectNode buildGoldenShot(String positivePrompt, String filenamePrefix) {
        return buildGoldenShot(positivePrompt, filenamePrefix, newSeed(), newSeed());
    }

    ObjectNode buildGoldenShot(String positivePrompt, String filenamePrefix, long samplerSeed, long detailerSeed) {
        ObjectNode wf = wf1Template.deepCopy();
        inputs(wf, "12").put("text", positivePrompt);
        inputs(wf, "11").put("seed", samplerSeed);
        inputs(wf, "17").put("seed", detailerSeed);
        inputs(wf, "6").put("batch_size", props.generation().batchSize());
        inputs(wf, "9").put("filename_prefix", filenamePrefix);
        return wf;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  WF-2 · i2i 리파인 (Stage 2 베이스 / Stage 3 감정 15종 공용 — positive만 다름)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public ObjectNode buildRefine(String inputImageName, String positivePrompt,
                                  String faceWildcard, String filenamePrefix) {
        return buildRefine(inputImageName, positivePrompt, faceWildcard, filenamePrefix, newSeed(), newSeed());
    }

    ObjectNode buildRefine(String inputImageName, String positivePrompt, String faceWildcard,
                           String filenamePrefix, long samplerSeed, long detailerSeed) {
        ObjectNode wf = wf2Template.deepCopy();
        inputs(wf, "19").put("image", inputImageName);
        inputs(wf, "12").put("text", positivePrompt);
        inputs(wf, "11").put("seed", samplerSeed);
        inputs(wf, "17").put("seed", detailerSeed);
        // [2026-07-20] 얼굴 디테일 일관성 — 캐릭터 얼굴 태그를 병합한 와일드카드 주입
        //   (null이면 템플릿 검증값 "detailed beautiful eyes" 유지)
        if (faceWildcard != null && !faceWildcard.isBlank()) {
            inputs(wf, "17").put("wildcard", faceWildcard);
        }
        Double denoiseOverride = props.generation().refineDenoiseOverride();
        if (denoiseOverride != null) {
            inputs(wf, "11").put("denoise", denoiseOverride);
        }
        inputs(wf, "9").put("filename_prefix", filenamePrefix);
        return wf;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  WF-3 · 누끼 (Stage 4 — 15종 전부)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public ObjectNode buildCutout(String inputImageName, String filenamePrefix) {
        ObjectNode wf = wf3Template.deepCopy();
        inputs(wf, "1").put("image", inputImageName);
        inputs(wf, "23").put("filename_prefix", filenamePrefix);
        return wf;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** SecureRandom 64-bit 양수 seed. */
    public long newSeed() {
        return seedRandom.nextLong() & Long.MAX_VALUE;
    }

    /** [어드민 프롬프트 인스펙션] 템플릿 동결 네거티브 (wf2 node 13 — wf1과 동일 값). */
    public String templateNegative() {
        return wf2Template.path("13").path("inputs").path("text").asText();
    }

    private JsonNode load(String classpath) {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("[UGC] Workflow template load failed: " + classpath, e);
        }
    }

    private static ObjectNode inputs(ObjectNode workflow, String nodeId) {
        JsonNode node = workflow.path(nodeId).path("inputs");
        if (!node.isObject()) {
            throw new IllegalStateException("[UGC] Workflow node inputs not found: nodeId=" + nodeId);
        }
        return (ObjectNode) node;
    }

    private static void requireInput(JsonNode template, String path, String nodeId, String key) {
        if (template.path(nodeId).path("inputs").path(key).isMissingNode()) {
            throw new IllegalStateException(
                "[UGC] 치환 계약 경로 누락 — 템플릿 드리프트 의심: %s → \"%s\".inputs.%s".formatted(path, nodeId, key));
        }
    }
}
