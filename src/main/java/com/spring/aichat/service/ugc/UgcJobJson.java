package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.dto.ugc.BaseCandidate;
import com.spring.aichat.dto.ugc.EmotionAssetState;
import com.spring.aichat.dto.ugc.StructuredConcept;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [UGC v1] CharacterCreationJob의 TEXT-JSON 필드 코덱 (단일 소스).
 *
 * <p>코드베이스 관례(TEXT + 앱 레벨 Jackson)에 따라 잡의 구조화 스크래치 필드를 직렬화한다.
 * 워커·서비스·컨트롤러가 전부 이 코덱만 사용한다.
 */
@Component
@RequiredArgsConstructor
public class UgcJobJson {

    private final ObjectMapper objectMapper;

    // ── 감정 상태 맵 ──

    public Map<EmotionTag, EmotionAssetState> readEmotions(String json) {
        if (json == null || json.isBlank()) return new EnumMap<>(EmotionTag.class);
        try {
            Map<String, EmotionAssetState> raw = objectMapper.readValue(json, new TypeReference<>() {});
            Map<EmotionTag, EmotionAssetState> map = new EnumMap<>(EmotionTag.class);
            raw.forEach((k, v) -> map.put(EmotionTag.valueOf(k), v));
            return map;
        } catch (Exception e) {
            throw new IllegalStateException("emotionAssetsJson 파싱 실패", e);
        }
    }

    public String writeEmotions(Map<EmotionTag, EmotionAssetState> map) {
        try {
            Map<String, EmotionAssetState> raw = new LinkedHashMap<>();
            map.forEach((k, v) -> raw.put(k.name(), v));
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("emotionAssetsJson 직렬화 실패", e);
        }
    }

    // ── 황금샷 키 배열 ──

    public List<String> readKeys(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("goldenShotKeysJson 파싱 실패", e);
        }
    }

    public String writeKeys(List<String> keys) {
        try {
            return objectMapper.writeValueAsString(keys);
        } catch (Exception e) {
            throw new IllegalStateException("goldenShotKeysJson 직렬화 실패", e);
        }
    }

    // ── 베이스 스탠딩 후보 배열 (2026-07-20 개편) ──

    public List<BaseCandidate> readBaseCandidates(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("baseCandidatesJson 파싱 실패", e);
        }
    }

    public String writeBaseCandidates(List<BaseCandidate> candidates) {
        try {
            return objectMapper.writeValueAsString(candidates);
        } catch (Exception e) {
            throw new IllegalStateException("baseCandidatesJson 직렬화 실패", e);
        }
    }

    // ── 외부 잡/스크래치 맵 (RunPod job id 추적 + K_ 접두 내부 키) ──

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

    // ── Stage 0 산출 ──

    public StructuredConcept readConcept(String json) {
        try {
            return objectMapper.readValue(json, StructuredConcept.class);
        } catch (Exception e) {
            throw new IllegalStateException("structuredConceptJson 파싱 실패", e);
        }
    }

    public String writeConcept(StructuredConcept concept) {
        try {
            return objectMapper.writeValueAsString(concept);
        } catch (Exception e) {
            throw new IllegalStateException("structuredConceptJson 직렬화 실패", e);
        }
    }
}
