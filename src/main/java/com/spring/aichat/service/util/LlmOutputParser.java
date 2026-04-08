package com.spring.aichat.service.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.dto.chat.SendChatResponse.SceneResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Function;

/**
 * [Bug #6 Fix] LLM 출력 파싱/변환 공통 유틸리티
 *
 * ChatService, ChatStreamService, EndingService, DirectorService 등에서
 * 동일하게 사용되는 LLM JSON 파싱 헬퍼를 단일 소스로 통합.
 *
 * 모든 메서드는 stateless static으로 설계.
 * ObjectMapper가 필요한 메서드는 파라미터로 주입받음.
 */
@Slf4j
public final class LlmOutputParser {

    private LlmOutputParser() {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. JSON 추출 — Markdown 코드 블록 + 프리앰블/포스트앰블 제거
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * LLM raw output에서 순수 JSON 문자열만 추출.
     *
     * 처리 순서:
     *   1. ```json / ``` 마크다운 코드 블록 제거
     *   2. 첫 번째 '{' 이전의 프리앰블 텍스트 제거 (로그 경고)
     *   3. 마지막 '}' 이후의 포스트앰블 텍스트 제거
     *
     * null/blank 입력 시 원본을 그대로 반환하여 호출측에서 에러 처리하게 함.
     */
    public static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        String text = raw.trim();

        // Step 1: Markdown 코드 블록 제거
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        text = text.trim();

        // Step 2: 첫 번째 '{' 이전 프리앰블 제거
        int jsonStart = text.indexOf('{');
        if (jsonStart < 0) {
            return text;
        }

        if (jsonStart > 0) {
            String preamble = text.substring(0, jsonStart).trim();
            if (!preamble.isEmpty()) {
                log.warn("⚠️ [JSON] Stripped preamble before JSON ({}chars): '{}'",
                    preamble.length(),
                    preamble.substring(0, Math.min(80, preamble.length())));
            }
            text = text.substring(jsonStart);
        }

        // Step 3: 마지막 '}' 이후 제거
        int lastBrace = text.lastIndexOf('}');
        if (lastBrace >= 0 && lastBrace < text.length() - 1) {
            text = text.substring(0, lastBrace + 1);
        }

        return text.trim();
    }

    /**
     * 간이 마크다운 코드 블록만 제거 (프리앰블/포스트앰블 처리 없음).
     * 히스토리 빌드 등 raw JSON이 이미 정형화되어 있는 경우 사용.
     */
    public static String stripMarkdown(String text) {
        if (text == null) return null;
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 감정 / 문자열 변환 유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 문자열 → EmotionTag 안전 파싱. 실패 시 NEUTRAL 반환.
     */
    public static EmotionTag parseEmotion(String emotionStr) {
        try {
            return EmotionTag.valueOf(emotionStr.toUpperCase());
        } catch (Exception e) {
            return EmotionTag.NEUTRAL;
        }
    }

    /**
     * null/blank/"null" 안전 대문자 변환. 해당 조건 시 null 반환.
     */
    public static String safeUpperCase(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value.toUpperCase().trim();
    }

    /**
     * SceneResponse 리스트에서 마지막으로 non-null인 필드 값을 추출.
     * 씬 디렉션 필드(location, time, outfit 등) 최종 값 결정에 사용.
     */
    public static String extractLastNonNull(
        List<SceneResponse> scenes,
        Function<SceneResponse, String> extractor
    ) {
        for (int i = scenes.size() - 1; i >= 0; i--) {
            String val = extractor.apply(scenes.get(i));
            if (val != null) return val;
        }
        return null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. ASSISTANT 히스토리 정제 (LLM 컨텍스트 빌드용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [Hallucination Fix] ASSISTANT 과거 대화를 LLM 히스토리용으로 정제.
     *
     * 환각 방지 원칙:
     *   - 감정 메타데이터({Emotion: XXX}) 제거 — 시각적 렌더링용이지 대화 맥락이 아님
     *   - [speaker] 프리픽스 유지 — NPC 구분에 필수
     *   - (narration) 유지 — 행동 맥락 제공
     *   - "dialogue" 유지 — 실제 대사
     *   - 속마음: includeInnerThought=true이면 {💭 Previous thought: "..."} 형태로 부착
     *
     * @param objectMapper   JSON 파싱용
     * @param chatLog        대상 ASSISTANT 로그
     * @param characterName  캐릭터 이름 (speaker fallback용)
     * @param includeInnerThought 속마음 포함 여부
     */
    public static String buildSanitizedAssistantContent(
        ObjectMapper objectMapper,
        ChatLogDocument chatLog,
        String characterName,
        boolean includeInnerThought
    ) {
        try {
            String raw = chatLog.getRawContent();
            if (raw == null || raw.isBlank()) {
                return chatLog.getCleanContent() != null ? chatLog.getCleanContent() : "";
            }

            String cleaned = extractJson(raw);
            AiJsonOutput parsed = objectMapper.readValue(cleaned, AiJsonOutput.class);

            StringBuilder sb = new StringBuilder();
            for (AiJsonOutput.Scene scene : parsed.scenes()) {
                // NPC 발화 시에만 speaker 표기 (캐릭터 자신은 role="assistant"로 식별)
                String speaker = (scene.speaker() != null && !scene.speaker().isBlank())
                    ? scene.speaker() : null;
                if (speaker != null && !speaker.equals(characterName)) {
                    sb.append("[").append(speaker).append("] ");
                }

                // 나레이션(행동 묘사) — 대화 맥락 유지
                if (scene.narration() != null && !scene.narration().isBlank()) {
                    sb.append("(").append(scene.narration().trim()).append(") ");
                }

                // 대사 — 핵심 컨텐츠
                if (scene.dialogue() != null && !scene.dialogue().isBlank()) {
                    sb.append("\"").append(scene.dialogue().trim()).append("\"");
                }
                sb.append("\n");
            }

            // 속마음 컨텍스트 주입
            if (includeInnerThought) {
                String thought = chatLog.getInnerThought();
                if (thought != null && !thought.isBlank()) {
                    sb.append("{💭 Previous thought: \"").append(thought.trim()).append("\"}\n");
                }
            }

            String result = sb.toString().trim();
            return result.isEmpty()
                ? (chatLog.getCleanContent() != null ? chatLog.getCleanContent() : "")
                : result;

        } catch (Exception e) {
            return chatLog.getCleanContent() != null ? chatLog.getCleanContent() : chatLog.getRawContent();
        }
    }

    /** 하위 호환: 속마음 미포함 버전 */
    public static String buildSanitizedAssistantContent(
        ObjectMapper objectMapper,
        ChatLogDocument chatLog,
        String characterName
    ) {
        return buildSanitizedAssistantContent(objectMapper, chatLog, characterName, false);
    }
}