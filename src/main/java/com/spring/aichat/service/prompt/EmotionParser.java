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
        if (stage == null || stage.isBlank()) return EmotionTag.NEUTRAL;

        // 공백 제거 및 정규화를 통해 매칭 확률 높임
        String s = stage.replace(" ", "").replace(".", "").replace(",", "");

        // ---------------------------------------------------------
        // 1. 강한 부정/특수 감정 (우선순위 높음: 웃음/놀람과 겹칠 수 있는 것들 먼저 처리)
        // ---------------------------------------------------------

        // [경멸/비웃음] : 비웃음(웃 글자 포함 주의), 한심, 싸늘, 쳇, 흥
        if (containsAny(s, List.of("경멸", "한심", "비웃", "코웃음", "쳇", "흥", "싸늘", "차갑", "벌레", "혐오", "깔보", "도끼눈"))) {
            return EmotionTag.DISGUST;
        }

        // [당황/곤란] : 놀람과 비슷하지만 '식은땀'이나 '말을 더듬는' 뉘앙스
        if (containsAny(s, List.of("당황", "허둥", "땀", "삐질", "어버버", "곤란", "난처", "동공지진", "횡설수설", "말문", "멈칫", "동요"))) {
            return EmotionTag.PANIC;
        }

        // [편안/나른] : 휴식, 안도, 나른함
        if (containsAny(s, List.of("편안", "나른", "하품", "기지개", "턱을괴", "턱괴", "엎드", "누워", "안도", "차분", "여유", "느긋", "한숨"))) {
            // 주의: '한숨'은 슬픔(땅이 꺼져라)일 수도 있고 안도(휴)일 수도 있음.
            // 문맥 파악이 어렵다면 보통 안도/편안 보다는 '슬픔'이나 'NEUTRAL'로 빼기도 함.
            // 여기서는 '안도의 한숨'을 가정하거나 키워드를 조정하세요.
            return EmotionTag.RELAX;
        }

        // ---------------------------------------------------------
        // 2. 기본 감정 (일반적인 매핑)
        // ---------------------------------------------------------

        // [분노]
        if (containsAny(s, List.of("화내", "버럭", "짜증", "노려", "째려", "찡그", "인상", "주먹", "소리치", "윽박", "분노", "씩씩"))) {
            return EmotionTag.ANGRY;
        }

        // [슬픔]
        if (containsAny(s, List.of("울", "눈물", "슬퍼", "훌쩍", "흑흑", "흐느", "침울", "우울", "상처", "글썽", "뚝뚝", "시무룩"))) {
            return EmotionTag.SAD;
        }

        // [부끄러움/설렘] : 얼굴 색 변화가 핵심
        if (containsAny(s, List.of("붉히", "빨개", "홍당무", "당황", "수줍", "부끄", "달아오", "심쿵", "두근", "피하", "머뭇", "더듬"))) {
            // '당황' 키워드가 PANIC에 없으면 여기서 처리될 수 있음
            return EmotionTag.SHY;
        }

        // [놀람]
        if (containsAny(s, List.of("놀라", "깜짝", "헉", "헙", "동공", "눈이커", "경악", "비명", "소스라"))) {
            return EmotionTag.SURPRISE;
        }

        // [기쁨/웃음] : 가장 마지막에 배치 (다른 감정에 '웃' 글자가 섞일 수 있음 예: 비웃음)
        if (containsAny(s, List.of("웃", "미소", "활짝", "방긋", "깔깔", "킥킥", "하하", "흐뭇", "기뻐", "즐거", "행복", "싱글", "생글"))) {
            return EmotionTag.JOY;
        }

        return EmotionTag.NEUTRAL;
    }

    private boolean containsAny(String target, List<String> keywords) {
        return keywords.stream().anyMatch(target::contains);
    }

    public record ParsedEmotion(String stageDirection, EmotionTag emotionTag, String cleanContent) {}
}
