package com.spring.aichat.service.illustration.scene;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.SceneIllustrationProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.List;

/**
 * [2026-07-30 B-2/A-1 재피벗] 씬 워크플로 치환/조립 엔진 — 디오라마 wf2 검증본 개별 포팅.
 *
 * <p>정적 코어(ckpt/lora/latent/negative/sampler/detailer/save + 노드30 TIPO + 노드31 베이스 인코드)에
 * 다중 모드일 때 캐릭터 블록 CLIPTextEncode(40+i)·ConditioningConcat(50+i) 체인을 동적으로 주입하고
 * KSampler(11)/FaceDetailer(17)의 positive를 마지막 concat으로 재배선한다.
 * TIPO 비활성 시 노드30을 제거하고 노드31에 텍스트를 직결한다.
 *
 * <p>@PostConstruct 치환 계약 검증 — 템플릿 드리프트는 기동 실패로 전환(UgcWorkflowFactory 관례).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SceneWorkflowFactory {

    private static final String SCENE_PATH = "workflows_json/wf_scene_render.json";

    private final ObjectMapper objectMapper;
    private final SceneIllustrationProperties props;
    private final SecureRandom seedRandom = new SecureRandom();

    private JsonNode sceneTemplate;

    @PostConstruct
    void loadTemplate() {
        sceneTemplate = load(SCENE_PATH);
        requireInput(sceneTemplate, SCENE_PATH, "30", "tags");
        requireInput(sceneTemplate, SCENE_PATH, "30", "ban_tags");
        requireInput(sceneTemplate, SCENE_PATH, "30", "seed");
        requireInput(sceneTemplate, SCENE_PATH, "31", "text");
        requireInput(sceneTemplate, SCENE_PATH, "13", "text");
        requireInput(sceneTemplate, SCENE_PATH, "11", "seed");
        requireInput(sceneTemplate, SCENE_PATH, "17", "seed");
        requireInput(sceneTemplate, SCENE_PATH, "6", "width");
        requireInput(sceneTemplate, SCENE_PATH, "9", "filename_prefix");
        log.info("[SCENE-WF] 워크플로 템플릿 로드 완료 (wf_scene_render — 치환 계약 검증 통과)");
    }

    public ObjectNode buildScene(ScenePromptAssembler.ScenePrompt prompt, String filenamePrefix) {
        ObjectNode wf = sceneTemplate.deepCopy();
        int width = props.generation().widthOrDefault();
        int height = props.generation().heightOrDefault();

        inputs(wf, "6").put("width", width);
        inputs(wf, "6").put("height", height);
        inputs(wf, "6").put("batch_size", 1);
        String negOverride = props.generation().sceneNegativeOrNull();
        if (negOverride != null) {
            inputs(wf, "13").put("text", negOverride);
        }
        inputs(wf, "11").put("seed", newSeed());
        inputs(wf, "17").put("seed", newSeed());
        inputs(wf, "9").put("filename_prefix", filenamePrefix);

        if (props.generation().tipoEnabledOrDefault()) {
            ObjectNode tipo = inputs(wf, "30");
            tipo.put("tags", prompt.sceneTags());
            tipo.put("ban_tags", prompt.banTags() == null ? "" : prompt.banTags());
            tipo.put("tipo_model", props.generation().tipoModelOrDefault());
            tipo.put("width", width);
            tipo.put("height", height);
            tipo.put("seed", Math.abs(seedRandom.nextInt()));
        } else {
            // TIPO 바이패스 — 노드30 제거, 노드31에 씬 태그 직결
            wf.remove("30");
            inputs(wf, "31").put("text", prompt.sceneTags());
        }

        // 다중 모드: 캐릭터 블록 인코드(40+i) → ConditioningConcat 체인(50+i) → positive 재배선
        List<String> blocks = prompt.actorBlocks();
        if (!prompt.tipoFull() && blocks != null && !blocks.isEmpty()) {
            String prevRef = "31";
            for (int i = 0; i < blocks.size(); i++) {
                String encodeId = String.valueOf(40 + i);
                String concatId = String.valueOf(50 + i);

                ObjectNode encode = wf.putObject(encodeId);
                encode.put("class_type", "CLIPTextEncode");
                ObjectNode encodeInputs = encode.putObject("inputs");
                encodeInputs.put("text", blocks.get(i));
                encodeInputs.putArray("clip").add("2").add(1);

                ObjectNode concat = wf.putObject(concatId);
                concat.put("class_type", "ConditioningConcat");
                ObjectNode concatInputs = concat.putObject("inputs");
                concatInputs.putArray("conditioning_to").add(prevRef).add(0);
                concatInputs.putArray("conditioning_from").add(encodeId).add(0);

                prevRef = concatId;
            }
            rewirePositive(wf, "11", prevRef);
            rewirePositive(wf, "17", prevRef);
        }
        return wf;
    }

    public long newSeed() {
        return seedRandom.nextLong() & Long.MAX_VALUE;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static void rewirePositive(ObjectNode workflow, String nodeId, String sourceRef) {
        ObjectNode in = inputs(workflow, nodeId);
        in.remove("positive");
        in.putArray("positive").add(sourceRef).add(0);
    }

    private JsonNode load(String classpath) {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("[SCENE-WF] 워크플로 템플릿 로드 실패: " + classpath, e);
        }
    }

    private static ObjectNode inputs(ObjectNode workflow, String nodeId) {
        JsonNode node = workflow.path(nodeId).path("inputs");
        if (!node.isObject()) {
            throw new IllegalStateException("[SCENE-WF] 워크플로 노드 inputs 없음: nodeId=" + nodeId);
        }
        return (ObjectNode) node;
    }

    private static void requireInput(JsonNode template, String path, String nodeId, String key) {
        if (template.path(nodeId).path("inputs").path(key).isMissingNode()) {
            throw new IllegalStateException(
                "[SCENE-WF] 치환 계약 경로 누락 — 템플릿 드리프트: %s → \"%s\".inputs.%s".formatted(path, nodeId, key));
        }
    }
}
