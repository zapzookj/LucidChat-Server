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

    // ── [2026-08-05 디자인 리롤] 황금샷 배치별 외형 스냅샷 ──
    // 리롤이 디자인을 갈아치우는 구조에서 과거 배치 원화를 보존·선택 가능하게 하기 위해,
    // 배치 제출 시점의 컨셉을 startIndex(그 배치 첫 키의 누적 인덱스)와 함께 기록한다.

    /** 황금샷 배치 스냅샷 — startIndex = 이 배치 첫 이미지의 누적 키 인덱스. */
    public record GoldenSnapshot(int startIndex, String conceptJson) {}

    public List<GoldenSnapshot> readGoldenSnapshots(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("goldenSnapshots 파싱 실패", e);
        }
    }

    public String writeGoldenSnapshots(List<GoldenSnapshot> snapshots) {
        try {
            return objectMapper.writeValueAsString(snapshots);
        } catch (Exception e) {
            throw new IllegalStateException("goldenSnapshots 직렬화 실패", e);
        }
    }

    /**
     * 선택 인덱스가 속한 배치의 스냅샷 해석 — startIndex ≤ index 중 최대. 같은 startIndex가
     * 중복이면(재시도 재기록) 마지막 것을 취한다. 스냅샷 부재(레거시 잡)면 null.
     */
    public static GoldenSnapshot resolveSnapshot(List<GoldenSnapshot> snapshots, int index) {
        GoldenSnapshot chosen = null;
        for (GoldenSnapshot s : snapshots) {
            if (s.startIndex() <= index && (chosen == null || s.startIndex() >= chosen.startIndex())) {
                chosen = s;
            }
        }
        return chosen;
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
