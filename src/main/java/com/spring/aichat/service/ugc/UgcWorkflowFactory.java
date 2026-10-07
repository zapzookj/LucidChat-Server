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

        // [2026-08-04 남캐] Male LoRA 체인 앵커(detail LoRA 노드 2) 실존 검증 — 템플릿 드리프트 시 기동 실패
        requireInput(wf1Template, WF1_PATH, DETAIL_LORA_NODE_ID, "lora_name");
        requireInput(wf2Template, WF2_PATH, DETAIL_LORA_NODE_ID, "lora_name");
        // [2026-08-04 미형 튜닝] 남성 네거티브 부착 지점(노드 13) 실존 검증
        requireInput(wf1Template, WF1_PATH, "13", "text");
        requireInput(wf2Template, WF2_PATH, "13", "text");

        log.info("[UGC] Workflow templates loaded: wf1/wf2/wf3 (치환 계약 검증 통과)");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  WF-1 · 황금샷 t2i (Stage 1 / 황금샷 리롤)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [2026-08-04 남캐] male이면 Male_Type LoRA를 그래프에 조건부 체인.
     *
     * <p>[§2-6 · 적대적 검토 반영] 무성별 오버로드 2종(2인자 public · 4인자 패키지 프라이빗)을 제거했다.
     * 프로덕션 호출부가 0인 채 남아 있으면 다음 사람이 무심코 호출해 <b>조용히 male=false로 컴파일</b>된다 —
     * E-6.1.a가 정확히 그 사고였는데, 그 커밋이 §2-6을 근거로 {@code UgcPromptAssembler}의 오버로드를
     * 지우면서 같은 함정을 이 클래스에는 남겨 뒀다. 테스트는 명시 인자를 넘긴다.
     */
    public ObjectNode buildGoldenShot(String positivePrompt, String filenamePrefix, boolean male) {
        return buildGoldenShot(positivePrompt, filenamePrefix, newSeed(), newSeed(), male);
    }

    ObjectNode buildGoldenShot(String positivePrompt, String filenamePrefix,
                               long samplerSeed, long detailerSeed, boolean male) {
        ObjectNode wf = wf1Template.deepCopy();
        if (male) {
            injectMaleLora(wf);
        }
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

    /** [2026-08-04 남캐] male이면 Male_Type LoRA를 조건부 체인. [§2-6] 무성별 오버로드 2종은 제거했다. */
    public ObjectNode buildRefine(String inputImageName, String positivePrompt,
                                  String faceWildcard, String filenamePrefix, boolean male) {
        return buildRefine(inputImageName, positivePrompt, faceWildcard, filenamePrefix,
            newSeed(), newSeed(), male);
    }

    ObjectNode buildRefine(String inputImageName, String positivePrompt, String faceWildcard,
                           String filenamePrefix, long samplerSeed, long detailerSeed, boolean male) {
        ObjectNode wf = wf2Template.deepCopy();
        if (male) {
            injectMaleLora(wf);
            appendMaleNegative(wf);
        }
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
    //  [2026-08-04 남캐] Male_Type LoRA 조건부 체인 (wf1·wf2 공용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 신규 LoraLoader 노드 id — 템플릿 노드 id 대역(1~23)과 충돌하지 않는 값. */
    private static final String MALE_LORA_NODE_ID = "900";
    private static final String DETAIL_LORA_NODE_ID = "2";
    private static final String MALE_LORA_FILE = "male_type.safetensors";

    /**
     * [2026-08-04 방향 전환] 남성 잡 네거티브 부착 — 노브 지정 시에만(기본 무부착).
     * 미학 유도는 Stage0 브리프 담당 — 이 경로는 비상용 수동 개입 전용.
     */
    private void appendMaleNegative(ObjectNode wf) {
        if (props.generation().maleNegativeOrNull() == null) return;
        String base = wf.path("13").path("inputs").path("text").asText();
        inputs(wf, "13").put("text", withMaleNegative(base));
    }

    /**
     * [E-6.1.b] 남캐 네거티브 결합 규칙의 단일 출처. 실제 워크플로 주입(appendMaleNegative)과
     * 어드민 인스펙션(templateNegative(true))이 같은 문자열을 내도록 여기 하나만 쓴다 —
     * 종전엔 인스펙션이 템플릿 원본만 읽어 남캐 잡의 화면 표시가 실제와 달랐다.
     */
    private String withMaleNegative(String base) {
        String extra = props.generation().maleNegativeOrNull();
        return extra == null ? base : base + ", " + extra;
    }

    /**
     * detail LoRA(노드 2) 뒤에 Male_Type LoRA를 체인 — 노드 2의 model/clip 출력을 소비하던
     * 모든 참조([2,0]/[2,1])를 신규 노드로 재배선한 뒤, 신규 노드가 노드 2를 입력으로 받는다.
     * VAE([1,2]) 등 체크포인트 직결 참조는 불변. 여캐 경로는 이 메서드를 아예 타지 않는다.
     * 강도는 {@code ugc.generation.male-lora-strength} 노브(PoC 튜닝 대상 — 종원 매트릭스 확정 전).
     */
    private void injectMaleLora(ObjectNode wf) {
        if (wf.has(MALE_LORA_NODE_ID)) {
            throw new IllegalStateException("[UGC] 노드 id 충돌 — 템플릿에 " + MALE_LORA_NODE_ID + "가 이미 존재");
        }
        // 1) [2,0]→[900,0] · [2,1]→[900,1] 전 참조 재배선
        wf.properties().forEach(entry -> {
            JsonNode inputsNode = entry.getValue().path("inputs");
            if (!inputsNode.isObject()) return;
            ObjectNode in = (ObjectNode) inputsNode;
            in.properties().forEach(field -> {
                JsonNode v = field.getValue();
                if (v.isArray() && v.size() == 2 && DETAIL_LORA_NODE_ID.equals(v.get(0).asText())) {
                    var arr = objectMapper.createArrayNode();
                    arr.add(MALE_LORA_NODE_ID).add(v.get(1).asInt());
                    in.set(field.getKey(), arr);
                }
            });
        });
        // 2) 신규 LoraLoader — detail LoRA를 입력으로 체인
        ObjectNode inputs = objectMapper.createObjectNode();
        inputs.put("lora_name", MALE_LORA_FILE);
        inputs.put("strength_model", props.generation().maleLoraStrengthOrDefault());
        inputs.put("strength_clip", 1.0);
        inputs.set("model", objectMapper.createArrayNode().add(DETAIL_LORA_NODE_ID).add(0));
        inputs.set("clip", objectMapper.createArrayNode().add(DETAIL_LORA_NODE_ID).add(1));
        ObjectNode node = objectMapper.createObjectNode();
        node.set("inputs", inputs);
        node.put("class_type", "LoraLoader");
        node.set("_meta", objectMapper.createObjectNode().put("title", "Male_Type LoRA (남캐 조건부)"));
        wf.set(MALE_LORA_NODE_ID, node);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** SecureRandom 64-bit 양수 seed. */
    public long newSeed() {
        return seedRandom.nextLong() & Long.MAX_VALUE;
    }

    /**
     * [어드민 프롬프트 인스펙션] WF-2 네거티브 (wf2 node 13).
     * WF-1은 2026-10-07 1-A 채택으로 negative 빈값이므로 goldenShotNegative를 별도 제공한다.
     * <p>[E-6.1.b] 남캐 잡이면 실제 제출과 동일하게 male-negative 노브를 이어붙인다.
     * §2-6대로 무인자 오버로드는 남기지 않는다 — 호출부가 조용히 여캐 값으로 컴파일되면
     * 인스펙션 화면이 다시 거짓이 된다.
     */
    public String templateNegative(boolean male) {
        String base = wf2Template.path("13").path("inputs").path("text").asText();
        return male ? withMaleNegative(base) : base;
    }

    public String goldenShotNegative(boolean male) {
        return wf1Template.path("13").path("inputs").path("text").asText();
    }

    /**
     * [E-6.1.b] 인스펙션용 — Male_Type LoRA 강도 노브 실값. 여캐면 null(=체인 없음).
     * 삼항에 원시 double과 null을 섞으면 언박싱 NPE가 나므로 명시 박싱한다.
     */
    public Double maleLoraStrengthOrNull(boolean male) {
        return male ? Double.valueOf(props.generation().maleLoraStrengthOrDefault()) : null;
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
