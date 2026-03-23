package com.spring.aichat.service.stream;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * [Phase 5.5-Perf] LLM 스트리밍 버퍼에서 첫 번째 씬 JSON을 실시간 추출
 *
 * [Phase 5.5-EV Fix 2] event_status 선행 추출 기능 추가
 *   - JSON 포맷에서 event_status가 scenes 앞에 위치
 *   - event_status를 scenes보다 먼저 추출하여 SSE event_meta로 선발사
 *   - 프론트에서 first_scene 이전에 DialogueBox 색상 전환 가능
 */
@Slf4j
public class SceneStreamExtractor {

    private boolean firstSceneExtracted = false;

    // [Fix 2] event_status 선행 추출
    @Getter
    private boolean eventStatusExtracted = false;
    @Getter
    private String extractedEventStatus = null;

    /**
     * [Fix 2] event_status를 scenes보다 먼저 추출 시도.
     *
     * JSON 포맷 (이벤트 모드):
     * {
     *   "reasoning": "...",
     *   "event_status": "ONGOING",    ← 이것을 먼저 추출
     *   "scenes": [...]
     * }
     *
     * @param buffer 현재까지 누적된 LLM 출력
     * @return 추출된 event_status 문자열 ("ONGOING" or "RESOLVED"), 미완성이면 null
     */
    public String tryExtractEventStatus(String buffer) {
        if (eventStatusExtracted) return null;
        if (buffer == null || buffer.length() < 20) return null;

        String clean = stripLeadingMarkdown(buffer);

        // "event_status" 키 탐색
        int keyIdx = clean.indexOf("\"event_status\"");
        if (keyIdx == -1) return null;

        // 콜론 후 값 추출
        int colonIdx = clean.indexOf(':', keyIdx + 14);
        if (colonIdx == -1) return null;

        // 값의 시작 따옴표 찾기
        int quoteStart = -1;
        for (int i = colonIdx + 1; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '"') { quoteStart = i; break; }
            if (!Character.isWhitespace(c)) return null; // null 등 다른 값
        }
        if (quoteStart == -1) return null;

        // 값의 끝 따옴표 찾기
        int quoteEnd = clean.indexOf('"', quoteStart + 1);
        if (quoteEnd == -1) return null; // 아직 미완성

        String value = clean.substring(quoteStart + 1, quoteEnd);
        if ("ONGOING".equals(value) || "RESOLVED".equals(value)) {
            eventStatusExtracted = true;
            extractedEventStatus = value;
            log.info("🎬 [STREAM] event_status extracted early: {} | bufferPos={}",
                value, keyIdx);
            return value;
        }

        return null;
    }

    /**
     * 현재 버퍼에서 첫 번째 씬 JSON을 추출 시도.
     * (기존 로직 100% 유지)
     */
    public String tryExtractFirstScene(String buffer) {
        if (firstSceneExtracted) return null;
        if (buffer == null || buffer.length() < 30) return null;

        String clean = stripLeadingMarkdown(buffer);

        int scenesKeyIdx = clean.indexOf("\"scenes\"");
        if (scenesKeyIdx == -1) return null;

        int arrayStart = -1;
        for (int i = scenesKeyIdx + 8; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '[') { arrayStart = i; break; }
            if (c != ':' && !Character.isWhitespace(c)) return null;
        }
        if (arrayStart == -1) return null;

        int objStart = -1;
        for (int i = arrayStart + 1; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '{') { objStart = i; break; }
            if (!Character.isWhitespace(c)) return null;
        }
        if (objStart == -1) return null;

        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = objStart; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;

            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) {
                    firstSceneExtracted = true;
                    String extracted = clean.substring(objStart, i + 1);
                    log.info("🎬 [STREAM] First scene extracted at buffer[{}..{}] ({}chars)",
                        objStart, i + 1, extracted.length());
                    return extracted;
                }
            }
        }

        return null;
    }

    public boolean isFirstSceneExtracted() {
        return firstSceneExtracted;
    }

    private String stripLeadingMarkdown(String text) {
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("```json")) return trimmed.substring(7);
        if (trimmed.startsWith("```")) return trimmed.substring(3);
        return trimmed;
    }
}