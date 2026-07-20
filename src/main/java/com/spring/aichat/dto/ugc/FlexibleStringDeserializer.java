package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * [UGC v1] LLM 산출 관용 역직렬화 — 문자열 필드에 배열이 와도 수용한다.
 *
 * <p>Stage 0 프롬프트가 "bullet 5~7개"를 요구하면 LLM이 {@code core_values}를
 * JSON 배열로 반환하는 경우가 실측됨(2026-07-20 jobId=1 파싱 실패). 스키마 강제는
 * response_format=json_object 수준이라 필드 타입까지는 보장되지 않으므로 수신측에서 관용 처리:
 * <ul>
 *   <li>문자열 → 그대로</li>
 *   <li>배열 → 각 항목을 "- " bullet 라인으로 개행 결합</li>
 *   <li>기타(객체 등) → toString 폴백</li>
 * </ul>
 */
public class FlexibleStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        return flatten(node);
    }

    private String flatten(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                String s = item.isTextual() ? item.asText() : item.toString();
                if (s == null || s.isBlank()) continue;
                if (!sb.isEmpty()) sb.append("\n");
                String trimmed = s.trim();
                sb.append(trimmed.startsWith("-") ? trimmed : "- " + trimmed);
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return node.toString();
    }
}
