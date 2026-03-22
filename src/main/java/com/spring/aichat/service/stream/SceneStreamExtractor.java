package com.spring.aichat.service.stream;

import lombok.extern.slf4j.Slf4j;

/**
 * [Phase 5.5-Perf] LLM 스트리밍 버퍼에서 첫 번째 씬(Scene) JSON을 실시간 추출
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  동작 원리:
 *   1. LLM이 토큰을 하나씩 뱉으며 버퍼에 누적
 *   2. 매 토큰 추가 후 tryExtractFirstScene() 호출
 *   3. "scenes": [ { ... } 패턴에서 첫 번째 { } 블록이 닫히는 순간 포착
 *   4. 해당 JSON 문자열만 칼같이 도려내어 반환
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * [JSON 구조]
 *   {
 *     "reasoning": "...",       ← 여기 안에 "scenes" 단어가 있어도 안전 (이스케이프됨)
 *     "scenes": [
 *       { "narration": "...", "dialogue": "...", "emotion": "JOY", ... },  ← 이걸 추출
 *       { ... }
 *     ],
 *     "stat_changes": {...},
 *     "bpm": 120,
 *     ...
 *   }
 *
 * [안전성]
 *   - JSON 문자열 안의 중괄호/대괄호는 무시 (inString 추적)
 *   - 이스케이프 시퀀스 처리 (\" 등)
 *   - reasoning 필드 안의 "scenes" 단어와 혼동 없음 (이스케이프된 따옴표)
 *   - 한 번 추출 후 재추출 방지 (firstSceneExtracted 플래그)
 */
@Slf4j
public class SceneStreamExtractor {

    private boolean firstSceneExtracted = false;

    /**
     * 현재 버퍼에서 첫 번째 씬 JSON을 추출 시도.
     *
     * @param buffer LLM이 지금까지 출력한 전체 텍스트
     * @return 첫 번째 씬의 완전한 JSON 문자열, 아직 미완성이면 null
     */
    public String tryExtractFirstScene(String buffer) {
        if (firstSceneExtracted) return null;
        if (buffer == null || buffer.length() < 30) return null;

        // Markdown 래핑 제거 (```json ... ```)
        String clean = stripLeadingMarkdown(buffer);

        // ── Step 1: "scenes" 키 탐색 ──
        // JSON 최상위의 "scenes" 키를 찾는다.
        // reasoning 문자열 안에 있는 "scenes"는 \"scenes\"로 이스케이프되어 있으므로
        // indexOf("\"scenes\"")는 안전하게 최상위 키만 매칭한다.
        int scenesKeyIdx = clean.indexOf("\"scenes\"");
        if (scenesKeyIdx == -1) return null;

        // ── Step 2: "scenes" 뒤의 [ 탐색 ──
        int arrayStart = -1;
        for (int i = scenesKeyIdx + 8; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '[') {
                arrayStart = i;
                break;
            }
            // : 과 공백만 허용 — 다른 문자가 나오면 잘못된 위치
            if (c != ':' && !Character.isWhitespace(c)) return null;
        }
        if (arrayStart == -1) return null;

        // ── Step 3: 배열 안의 첫 번째 { 탐색 ──
        int objStart = -1;
        for (int i = arrayStart + 1; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '{') {
                objStart = i;
                break;
            }
            if (!Character.isWhitespace(c)) return null;
        }
        if (objStart == -1) return null;

        // ── Step 4: 중괄호 카운팅 (문자열 인식) ──
        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = objStart; i < clean.length(); i++) {
            char c = clean.charAt(i);

            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) {
                    // 첫 번째 씬 완성!
                    firstSceneExtracted = true;
                    String extracted = clean.substring(objStart, i + 1);
                    log.info("🎬 [STREAM] First scene extracted at buffer[{}..{}] ({}chars)",
                        objStart, i + 1, extracted.length());
                    return extracted;
                }
            }
        }

        // 아직 첫 번째 씬의 닫는 } 가 도착하지 않음
        return null;
    }

    /**
     * 추출 완료 여부
     */
    public boolean isFirstSceneExtracted() {
        return firstSceneExtracted;
    }

    /**
     * 버퍼 선두의 Markdown 코드 블록 태그 제거
     * LLM이 ```json 으로 감싸는 경우 대비
     */
    private String stripLeadingMarkdown(String text) {
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("```json")) {
            return trimmed.substring(7);
        }
        if (trimmed.startsWith("```")) {
            return trimmed.substring(3);
        }
        return trimmed;
    }
}