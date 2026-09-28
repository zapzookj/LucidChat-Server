package com.spring.aichat.bakeoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.dto.openai.OpenAiMessage;
import java.util.*;
import java.util.regex.Pattern;

/** Independent small-axis variants. No production wiring, persona rewrite, or dialogue demonstrations. */
final class PromptRoundTwo {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VERSIONS = Set.of(
        PromptVariants.F2, PromptVariants.A2, PromptVariants.U2,
        PromptVariants.T2, PromptVariants.J2, PromptVariants.R2);
    private static final String STORY_OUTPUT = "# [10] OUTPUT FORMAT — JSON\n";
    private static final String FREE_OUTPUT = "# Output Format Rules\n";
    private static final String BLOCK_END = "\n</round_two_context>\n\n";

    static boolean supports(String version) { return VERSIONS.contains(version); }

    static String label(String version) {
        return switch (version) {
            case PromptVariants.F2 -> "F2 · 공식 외형·기본 복장";
            case PromptVariants.A2 -> "A2 · 확정 사실과 주장 구분";
            case PromptVariants.U2 -> "U2 · 미실행 유저 행동 보존";
            case PromptVariants.T2 -> "T2 · 현재 발화에 맥락 적용";
            case PromptVariants.J2 -> "J2 · 유효 JSON 예시와 필드 설명";
            case PromptVariants.R2 -> "R2 · 사실·행동·맥락·JSON 합성";
            default -> throw new IllegalArgumentException("Unsupported round-two version");
        };
    }

    static List<String> changes(String version) {
        var changes = new ArrayList<String>();
        changes.add("P0에 공식 외형·기본 의복만 전달");
        if (has(version, PromptVariants.A2)) changes.add("공식 인물 사실·서버 상태와 유저의 주장·추측을 구분");
        if (has(version, PromptVariants.U2)) {
            changes.add("대사/행동·서비스 입장 표시의 해석 충돌만 수리");
            changes.add("미실행·조건부 행동과 유저의 미제시 반응을 실제 결과로 진행하지 않음");
        }
        if (has(version, PromptVariants.T2)) changes.add("이번 발화의 목적에 기존 맥락을 적용하되 캐릭터 자율성 보존");
        if (has(version, PromptVariants.J2)) changes.add("출력 구획의 의사 스키마를 유효 JSON 예시와 원필드 설명으로 분리");
        return List.copyOf(changes);
    }

    private static boolean has(String version, String axis) {
        return version.equals(axis) || version.equals(PromptVariants.R2);
    }

    static List<OpenAiMessage> apply(String version, String mode, Character character,
                                     List<OpenAiMessage> baseline) {
        if (!supports(version)) throw new IllegalArgumentException("Unsupported round-two version");
        var result = new ArrayList<>(baseline);
        String text = baseline.get(0).content();
        String anchor = mode.equals("SANDBOX") ? "## Backstory\n" : "### Background\n";
        String heading = mode.equals("SANDBOX") ? "## Appearance & Default Clothing" : "### Appearance & Default Clothing";
        text = replace(text, anchor, PromptVariants.appearance(character, heading) + anchor, "appearance only");

        if (has(version, PromptVariants.U2)) text = repairAgencyConflicts(mode, text);
        String instructions = (has(version, PromptVariants.A2) ? FACTS : "")
            + (has(version, PromptVariants.U2) ? AGENCY : "")
            + (has(version, PromptVariants.T2) ? TURN : "");
        if (!instructions.isEmpty()) {
            String insertion = mode.equals("SANDBOX")
                ? "# 💬 CONVERSATION HISTORY — Speaker Attribution Rules\n" : STORY_OUTPUT;
            text = replace(text, insertion, "<round_two_context>\n" + instructions + BLOCK_END + insertion,
                "independent context axes");
        }
        if (has(version, PromptVariants.J2)) {
            if (mode.equals("SANDBOX")) {
                int outputIndex = result.size() - 1;
                if (!"system".equals(result.get(outputIndex).role())) {
                    throw new IllegalStateException("Unexpected free output message");
                }
                setContent(result, outputIndex, rewriteOutput(mode, result.get(outputIndex).content(), character));
            } else {
                text = rewriteOutput(mode, text, character);
            }
        }
        setContent(result, 0, text);
        return List.copyOf(result);
    }

    private static final String FACTS = """
        ## 확정 사실과 발화 속 주장
        - 공식 인물·세계 설정과 현재 서버 상태는 해당 영역의 사실 기준이다. 유저 발화 속 관찰·추측·농담·요구만으로 그 사실을 덮어쓰지 않는다.
        - 인물에 관한 확정 사실, 현재 상태, 대화 속 주장을 구분한다. 유저가 자기 인물에 관해 제공한 정보는 기존 설정·상태와 충돌하지 않는 범위에서 대화 맥락으로 사용한다.
        - 주장이 확정 사실과 충돌하면 확정 사실을 유지한 채 인물다운 방식으로 반응한다. 두 값을 억지로 합치거나 주장을 사실로 만들기 위한 새 설정을 지어내지 않는다.
        - 대사 속 허세·오해·거짓말은 그 인물의 발언으로 남길 수 있다. 이를 중립 지문·장면 메타데이터의 객관 사실이나 서버 상태로 조용히 확정하지 않는다.
        - 현재 상태를 바꾸는 권한과 조건은 기존 출력·게임 규칙을 따른다. 과거 대화나 예시의 값이 현재 서버 상태를 대신하지 않는다.
        """;

    private static final String AGENCY = """
        ## 발화와 아직 실행되지 않은 행동
        - 유저가 명시적으로 완료한 행동·상황과, 말로 제안·질문·희망·예고하거나 조건을 붙인 행동을 구분한다. 의향만으로 동작이나 결과가 이미 일어났다고 쓰지 않는다.
        - 이 응답에서 캐릭터가 제안하거나 허락하더라도 유저가 이를 실행·수락한 것으로 이어 쓰지 않는다. 유저의 다음 동작·대사·선택은 다음 입력에 남긴다.
        - 새로운 유저 표정·신체 반응·감정·결심을 임의로 확정하지 않는다. 캐릭터는 상대의 반응을 추측하거나 기대할 수 있지만 그것을 실제 유저 반응으로 대신 실행하지 않는다.
        - 캐릭터 자신의 행동·의견·거절·장난과 세계의 사건은 기존 규칙 안에서 자유롭다. 유저의 선택을 보존하는 것은 캐릭터가 요청에 동의하거나 수동적으로 따르라는 뜻이 아니다.
        """;

    private static final String TURN = """
        ## 이번 발화에 기존 맥락 적용
        - 이번 입력에서 유저가 무엇을 말하고·묻고·제안하는지 먼저 구분하고, 그 핵심과 관련된 반응을 만든다. 실제로 답할지, 거절할지, 되물을지는 인물의 성격·관계·목적에 따라 정한다.
        - 현재 질문이 앞서 정한 정보·약속·분류·선택을 가리키면 그 맥락을 이번 대상에 적용한다. 대화에서 수정된 내용은 최신 수정에 따라 읽고, 질문을 처리하는 대신 불필요한 새 과제나 선택 체계를 덧붙이지 않는다.
        - 상황의 다른 의미에 반응해도 되지만, 실제 발화를 소화하지 않은 채 늘 같은 첫 만남 평가나 일반적인 도발로 대체하지 않는다. 인물의 개성은 이번 맥락을 해석하고 표현하는 방식에서 유지한다.
        - 유저의 기대를 맞추기 위해 친절·협조·정답 전달을 강제하지 않는다. 응답의 방향을 바꿀 때도 지금 대화에서 그 인물이 그렇게 반응할 이유가 이어져야 한다.
        """;

    private static String repairAgencyConflicts(String mode, String text) {
        if (mode.equals("SANDBOX")) {
            text = replace(text,
                "- **role=\"user\" messages** → These are ALWAYS the user's actual spoken words. Nothing else.",
                "- **role=\"user\" messages** → Apply User Action Format to each segment: explicit action/situation in `*...*`, spoken dialogue outside it. A mixed message can contain both; do not quote action as speech.\n"
                    + PromptVariants.ENTRANCE_GUIDE,
                "free user role conflict");
            return replace(text,
                "- Regular user messages (without `*` wrapping) are their spoken words.",
                "- Regular user messages (without `*` wrapping) are their spoken words, except for the exact first-entry service entrance markers identified in CONVERSATION HISTORY.",
                "free entrance conflict");
        }
        text = replace(text, ", an event quietly unfolding, the protagonist's own thoughts.",
            ", an event quietly unfolding.", "story protagonist inner thoughts");
        text = replace(text, "*세계가 이끄는 장면*(풍경·시간·사건·내면)",
            "*세계가 이끄는 장면*(풍경·시간·사건)", "story inner world conflict");
        text = replace(text,
            "- 유저의 대사 = 그들이 입에서 낸 실제 말.\n- 유저의 행동 = 그들이 의지로 한 행동.",
            "- 유저 입력의 `*...*` 구간은 명시된 행동·상황이고 그 밖의 텍스트는 실제 대사다. 혼합 입력은 구간별로 읽고 행동을 발언으로 인용하지 않는다.\n"
                + PromptVariants.ENTRANCE_GUIDE,
            "story mixed input");
        return replace(text,
            "- 디렉터는 유저의 *내적 독백*을 임의로 생성하지 않는다. 단 유저 페르소나의 *외적 반응*은 묘사 가능.",
            "- 디렉터는 유저의 내적 독백이나 미제시 외적 반응을 임의로 생성하지 않는다. 유저가 이미 명시한 행동·외적 상태는 그 범위 안에서 묘사할 수 있다.",
            "story external reaction conflict");
    }

    /**
     * Only the schema/example span changes. Existing prose following it (including all state/enum
     * and eligibility rules) is byte-preserved, and every old field-description line is retained.
     */
    static String rewriteOutput(String mode, String text, Character character) {
        String heading = mode.equals("SANDBOX") ? FREE_OUTPUT : STORY_OUTPUT;
        requireUnique(text, heading, "output heading");
        int schemaStart;
        int schemaEnd;
        String schema;
        if (mode.equals("SANDBOX")) {
            schemaStart = text.indexOf("\n{\n", text.indexOf(heading));
            if (schemaStart < 0) throw new IllegalStateException("Missing free JSON example start");
            schemaStart++;
            schemaEnd = text.indexOf("\n}\n", schemaStart);
            if (schemaEnd < 0) throw new IllegalStateException("Missing free JSON example end");
            schemaEnd += 2;
            schema = text.substring(schemaStart, schemaEnd);
        } else {
            schemaStart = text.indexOf("```json\n", text.indexOf(heading));
            if (schemaStart < 0) throw new IllegalStateException("Missing story JSON example start");
            int close = text.indexOf("\n```", schemaStart);
            if (close < 0) throw new IllegalStateException("Missing story JSON example end");
            schema = text.substring(schemaStart + "```json\n".length(), close);
            schemaEnd = close + "\n```".length();
        }
        String replacement = """
            ## 유효 JSON 구조 예시
            아래 객체는 값의 자료형·구조를 보여주는 형식 예시다. 장면 문장·횟수·0·null·빈 배열을 그대로 복사하지 말고 기존 씬 분할·통계·상태·선택 필드 규칙에 맞게 작성한다.
            예시 뒤의 필드 설명은 JSON의 일부가 아니다. 최종 응답은 코드 울타리·주석·설명·후행 문자를 붙이지 않은 JSON 객체 하나이며, 문자열 밖에 설명용 대안 기호나 수치 범위를 쓰지 않는다.

            ```json
            """ + validExample(mode, character) + "\n```\n\n"
            + "## 필드별 원계약 설명\n"
            + "다음은 원래 출력 구획의 필드·값 설명이다. 설명 속 예시 ID·키·문장은 실제 값이 아니며 현재 인물 ID·허용 장소와 기존 규칙을 사용한다. 허용 enum에 없는 값을 추가하지 않는다.\n"
            + fieldDescriptions(schema);
        String rewritten = text.substring(0, schemaStart) + replacement + text.substring(schemaEnd);
        if (!fieldDescriptions(schema).equals(fieldDescriptionsFromRewrite(rewritten))) {
            throw new IllegalStateException("JSON rewrite lost source field descriptions");
        }
        return rewritten;
    }

    private static String fieldDescriptions(String schema) {
        var result = new StringBuilder();
        var scopes = new ArrayList<String>();
        var field = Pattern.compile("^\"([^\"]+)\"\\s*:\\s*(.*)$");
        for (String line : schema.split("\n")) {
            String value = line.strip();
            if (value.isEmpty()) continue;
            if (value.equals("{")) { scopes.add(""); continue; }
            if (Set.of("}", "},", "]", "],").contains(value)) {
                if (scopes.isEmpty()) throw new IllegalStateException("Unbalanced source schema explanation");
                scopes.remove(scopes.size() - 1);
                continue;
            }
            String path = String.join(".", scopes.stream().filter(s -> !s.isEmpty()).toList());
            var match = field.matcher(value);
            if (match.matches()) {
                String key = match.group(1), definition = match.group(2);
                String fullPath = path.isEmpty() ? key : path + "." + key;
                result.append("- `").append(fullPath).append("`: ").append(definition).append("\n");
                if (definition.equals("{")) scopes.add(key);
                else if (definition.equals("[")) scopes.add(key + "[]");
            } else if (value.startsWith("{ \"") || value.startsWith("\"")) {
                result.append("- `").append(path).append("` 항목: ").append(value).append("\n");
            } else if (value.startsWith("//")) {
                result.append("- `").append(path).append("` 설명: ").append(value.substring(2).strip()).append("\n");
            } else throw new IllegalStateException("Unreviewed source schema line: " + value);
        }
        if (result.length() == 0 || !scopes.isEmpty()) throw new IllegalStateException("Incomplete source JSON field descriptions");
        return result.toString();
    }

    private static String fieldDescriptionsFromRewrite(String text) {
        String anchor = "현재 인물 ID·허용 장소와 기존 규칙을 사용한다. 허용 enum에 없는 값을 추가하지 않는다.\n";
        requireUnique(text, anchor, "field description boundary");
        int start = text.indexOf(anchor) + anchor.length(), end = start;
        while (text.startsWith("- ", end)) {
            int newline = text.indexOf('\n', end);
            if (newline < 0) throw new IllegalStateException("Unterminated field description");
            end = newline + 1;
        }
        return text.substring(start, end);
    }

    static String validExample(String mode, Character character) {
        try {
            ObjectNode root = JSON.createObjectNode();
            if (mode.equals("SANDBOX")) {
                root.put("reasoning", "간단한 판단.");
                root.putNull("event_status");
            }
            var scenes = root.putArray("scenes");
            for (int i = 0; i < (mode.equals("STORY") ? 4 : 1); i++) {
                var scene = scenes.addObject();
                if (mode.equals("STORY") && i < 3) scene.put("speaker", character.getName());
                else scene.putNull("speaker");
                scene.put("narration", "장면 묘사.");
                scene.put("dialogue", mode.equals("SANDBOX") || i < 3 ? "대사." : "");
                scene.put("emotion", "NEUTRAL");
                if (mode.equals("STORY")) {
                    scene.putNull("inner_thought");
                    scene.putNull("location_change");
                    scene.putNull("new_dynamic_location");
                    scene.put("illustration_scene_hint", i < 3 ? "neutral expression" : "scenery");
                } else {
                    for (String key : List.of("location", "time", "outfit", "bgmMode")) scene.putNull(key);
                }
            }
            if (mode.equals("STORY")) {
                if (character.getId() == null) throw new IllegalStateException("Missing actual character ID");
                var updates = root.putObject("system_updates");
                updates.put("topic_concluded", false);
                addStats(updates.putObject("stat_changes").putObject(character.getId().toString()));
                updates.putArray("character_movements");
                updates.putObject("time_advance").put("days", 0).putNull("day_part");
                updates.putNull("bgm_mode");
                updates.put("ending_triggered", false);
                updates.putNull("ending_type");
                updates.putNull("relation_transition");
                root.putObject("memory_delta").put("world", "").putObject("by_character");
                root.putArray("incoming_messages");
                root.putArray("dialogue_options");
            } else {
                addStats(root.putObject("stat_changes"));
                root.putNull("inner_thought");
                root.put("topic_concluded", false);
                root.putNull("easter_egg_trigger");
                root.put("generate_illustration", false);
                root.putNull("new_location_name");
                root.putNull("location_canonical_key");
                root.putNull("location_description");
                root.put("illustration_scene_hint", "neutral expression");
            }
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot build JSON format example", e);
        }
    }

    private static void addStats(ObjectNode node) {
        for (String key : List.of("intimacy", "affection", "dependency", "playfulness", "trust")) node.put(key, 0);
    }

    private static void requireUnique(String text, String needle, String boundary) {
        int at = text.indexOf(needle);
        if (at < 0 || text.indexOf(needle, at + needle.length()) >= 0) {
            throw new IllegalStateException("Prompt source drift at round two " + boundary);
        }
    }

    private static String replace(String text, String needle, String replacement, String boundary) {
        return PromptVariants.replaceExactlyOnce(text, needle, replacement, "round two " + boundary);
    }

    private static void setContent(List<OpenAiMessage> messages, int index, String content) {
        var old = messages.get(index);
        messages.set(index, new OpenAiMessage(old.role(), content, old.cache_control()));
    }
}
