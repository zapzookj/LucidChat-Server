package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.dto.ugc.StructuredWorld;
import com.spring.aichat.dto.ugc.WorldDraft;
import com.spring.aichat.dto.ugc.WorldIllustrationAssets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [UGC 세계관 빌더] UgcWorldCreationJob의 TEXT-JSON 필드 코덱 (단일 소스 — {@link UgcJobJson} 관례).
 */
@Component
@RequiredArgsConstructor
public class UgcWorldJobJson {

    private final ObjectMapper objectMapper;

    // ── W0 산출 ──

    public StructuredWorld readStructured(String json) {
        try {
            return objectMapper.readValue(json, StructuredWorld.class);
        } catch (Exception e) {
            throw new IllegalStateException("structuredWorldJson 파싱 실패", e);
        }
    }

    public String writeStructured(StructuredWorld world) {
        try {
            return objectMapper.writeValueAsString(world);
        } catch (Exception e) {
            throw new IllegalStateException("structuredWorldJson 직렬화 실패", e);
        }
    }

    // ── W1 드래프트 ──

    public WorldDraft readDraft(String json) {
        try {
            return objectMapper.readValue(json, WorldDraft.class);
        } catch (Exception e) {
            throw new IllegalStateException("draftWorldJson 파싱 실패", e);
        }
    }

    public String writeDraft(WorldDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (Exception e) {
            throw new IllegalStateException("draftWorldJson 직렬화 실패", e);
        }
    }

    // ── W2 일러 상태 ──

    public WorldIllustrationAssets readAssets(String json) {
        if (json == null || json.isBlank()) return WorldIllustrationAssets.empty();
        try {
            return objectMapper.readValue(json, WorldIllustrationAssets.class);
        } catch (Exception e) {
            throw new IllegalStateException("illustrationAssetsJson 파싱 실패", e);
        }
    }

    public String writeAssets(WorldIllustrationAssets assets) {
        try {
            return objectMapper.writeValueAsString(assets);
        } catch (Exception e) {
            throw new IllegalStateException("illustrationAssetsJson 직렬화 실패", e);
        }
    }

    // ── 외부 잡 추적 맵 (token → "PENDING"|fal requestId) ──

    public Map<String, String> readScratch(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("externalJobsJson 파싱 실패", e);
        }
    }

    public String writeScratch(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("externalJobsJson 직렬화 실패", e);
        }
    }
}
