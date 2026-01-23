package com.spring.aichat.service.prompt;

import com.spring.aichat.domain.enums.EmotionTag;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 응답에서 지문(괄호)을 파싱하고 감정 태그를 추론한다.
 * 예: (얼굴을 붉히며) 정말? -> SHY
 */
@Component
public class EmotionParser {

    private static final Pattern PAREN_PATTERN = Pattern.compile("\\(([^)]{1,60})\\)");

    public ParsedEmotion parse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return new ParsedEmotion("", EmotionTag.NEUTRAL, "");
        }

        String stageDirection = extractFirstParenthesis(rawContent);
        EmotionTag tag = mapStageDirectionToEmotion(stageDirection);
        String clean = removeAllParenthesis(rawContent).trim();

        return new ParsedEmotion(stageDirection, tag, clean);
    }

    private String extractFirstParenthesis(String raw) {
        Matcher m = PAREN_PATTERN.matcher(raw);
        return m.find() ? m.group(1) : "";
    }

    private String removeAllParenthesis(String raw) {
        return raw.replaceAll("\\([^)]*\\)", "");
    }

    private EmotionTag mapStageDirectionToEmotion(String stage) {
        if (stage == null) return EmotionTag.NEUTRAL;

        // 명세의 키워드 매핑 예시 반영 :contentReference[oaicite:13]{index=13}
        String s = stage.replace(" ", "");

        if (containsAny(s, List.of("웃으며", "미소", "활짝", "방긋"))) return EmotionTag.JOY;
        if (containsAny(s, List.of("붉히", "당황", "수줍", "부끄"))) return EmotionTag.SHY;
        if (containsAny(s, List.of("화내", "버럭", "짜증", "노려"))) return EmotionTag.ANGRY;
        if (containsAny(s, List.of("울", "눈물", "슬퍼", "훌쩍"))) return EmotionTag.SAD;
        if (containsAny(s, List.of("놀라", "깜짝", "헉", "경악"))) return EmotionTag.SURPRISE;
        // 기타 감정 태그 및 키워드 추가 가능 (ex: 당황, 편안, 경멸 등)

        return EmotionTag.NEUTRAL;
    }

    private boolean containsAny(String target, List<String> keywords) {
        return keywords.stream().anyMatch(target::contains);
    }

    public record ParsedEmotion(String stageDirection, EmotionTag emotionTag, String cleanContent) {}
}
