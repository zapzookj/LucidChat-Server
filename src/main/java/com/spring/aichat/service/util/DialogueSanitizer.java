package com.spring.aichat.service.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * [Phase 5.5 Polish · P1 #2] Dialogue prefix sanitizer
 *
 * LLM(특히 fastModel)은 종종 dialogue 필드에 화자명 prefix를 섞어 출력한다:
 *   {"speaker": "연화", "dialogue": "연화: 안녕"}                            ← 명백한 오염
 *   {"speaker": "연화", "dialogue": "연화 : 어서와"}                          ← 공백 변형
 *   {"speaker": "연화", "dialogue": "연화： 잘 지냈어?"}                       ← 한국어 콜론 변형
 *   {"speaker": "사쿠라", "dialogue": "사쿠라: \"잘 지냈어?\""}                 ← 따옴표 동반
 *   {"speaker": "AVATAR", "dialogue": "지훈: 무슨 일 있어?"}                   ← AVATAR도 마찬가지
 *
 * 프롬프트 지시만으론 100% 차단이 어렵고, 일단 들어가면 MongoDB까지 영구 저장되어
 * 매 history 조회/표시에서 깨끗하지 않은 출력이 사용자에게 노출된다.
 *
 * 이 클래스는 결정론적 sanitizer를 제공한다:
 *   - 알려진 화자 이름(아바타 + 모든 히로인) 화이트리스트 매칭만 제거.
 *   - 알 수 없는 인물의 prefix는 보존 (예: "노점상: 환영합니다!" 같은 NPC 대사는 유효).
 *   - 따옴표("…", '…', 「…」, "…") 안에 들어있으면 인용으로 간주하고 보존.
 *
 * 사용:
 *   DialogueSanitizer.stripSpeakerPrefix(rawDialogue, knownSpeakerNames);
 */
public final class DialogueSanitizer {

    private DialogueSanitizer() {}

    /** ASCII 콜론(:), 한국어 wide 콜론(：), 일본어 wide 콜론(﹕) 등을 모두 매칭. */
    private static final String COLON_CLASS = "[:：﹕꞉]";

    /** 따옴표로 시작하는 인용은 sanitize에서 제외 — LLM이 의도적으로 인용한 케이스. */
    private static final Pattern QUOTED_PREFIX = Pattern.compile(
        "^\\s*[\"'\u201C\u201D\u2018\u2019\u300C\u300E].*",
        Pattern.DOTALL
    );

    /**
     * dialogue에서 알려진 화자 prefix를 제거한다.
     *
     * @param dialogue       원본 dialogue (null/빈 문자열 안전)
     * @param knownNames     아바타 이름 + 모든 히로인 이름들 (null/empty 안전)
     * @return prefix가 제거된 dialogue. 매칭 없으면 원본 그대로.
     */
    public static String stripSpeakerPrefix(String dialogue, Collection<String> knownNames) {
        if (dialogue == null) return null;
        if (dialogue.isEmpty()) return dialogue;

        String trimmed = dialogue.stripLeading();
        if (trimmed.isEmpty()) return dialogue;

        // 인용으로 시작하는 라인은 sanitize 안 함
        if (QUOTED_PREFIX.matcher(trimmed).matches()) {
            return dialogue;
        }

        List<String> sanitized = collectNames(knownNames);
        if (sanitized.isEmpty()) return dialogue;

        // 한 dialogue에 prefix가 중첩되어 있을 수 있으므로 (드물지만) 변화 없을 때까지 반복.
        // 무한 루프 방지를 위해 최대 3회.
        String current = dialogue;
        for (int i = 0; i < 3; i++) {
            String next = stripOnce(current, sanitized);
            if (next.equals(current)) return current;
            current = next;
        }
        return current;
    }

    private static String stripOnce(String dialogue, List<String> names) {
        String leading = dialogue.stripLeading();
        if (leading.isEmpty()) return dialogue;

        for (String name : names) {
            // 패턴: ^<name><whitespace*><colon><whitespace*>
            // 이름 자체가 정규식 메타 포함 가능성을 위해 quote.
            String pattern = "^" + Pattern.quote(name) + "\\s*" + COLON_CLASS + "\\s*";
            Pattern p = Pattern.compile(pattern);
            if (p.matcher(leading).find()) {
                String after = p.matcher(leading).replaceFirst("");
                // 이전 leading whitespace는 버림 — 어차피 prefix 제거 이후 자연스러운 시작.
                return after;
            }
        }
        return dialogue;
    }

    private static List<String> collectNames(Collection<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(names.size());
        for (String n : names) {
            if (n == null) continue;
            String t = n.trim();
            if (t.isEmpty()) continue;
            out.add(t);
        }
        // 긴 이름부터 매칭 — "지훈수" / "지훈" 같이 부분 일치 회피.
        out.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return out;
    }
}