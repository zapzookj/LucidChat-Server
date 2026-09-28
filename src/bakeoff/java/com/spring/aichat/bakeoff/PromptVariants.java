package com.spring.aichat.bakeoff;

import com.fasterxml.jackson.databind.JsonNode;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.dto.openai.OpenAiMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Local Rosetta experiment only: anchored edits to real assembler output, never a production override. */
final class PromptVariants {
    static final String P0 = "P0-service-fixed-state-v1";
    static final String P1 = "P1-service-repairs-v1";
    static final String C1 = "C1-repairs-v1";
    static final String S1 = "S1-character-v1";
    static final String E1 = "E1-examples-v1";
    static final String F2 = "F2-appearance-v1";
    static final String A2 = "A2-facts-v1";
    static final String U2 = "U2-agency-v1";
    static final String T2 = "T2-turn-v1";
    static final String J2 = "J2-json-v1";
    static final String R2 = "R2-combined-v1";
    static final String D3 = "D3-turn-boundary-v1";
    static final String K4 = "K4-current-context-v1";
    static final List<String> VERSIONS = List.of(P0, C1, S1, E1, F2, A2, U2, T2, J2, R2, D3, K4, P1);
    static final String EXAMPLES_START = "<rosetta_behavior_examples source=\"codex-draft\" status=\"creator-review-pending\">";
    private static final String BEHAVIOR_START = "<rosetta_behavior_model source=\"official-seed-interpretation\" status=\"experimental\">";
    private static final String SOUL_V1 = "# 🚫 Soul Preservation Rules (Priority: Highest)\n";
    private static final String RELATION_V1 = "## Behavior & Boundaries by Relation Level:\n";
    private static final String SOUL_V2 = "# 🚫 SOUL PRESERVATION RULES (디렉터의 작가 윤리)\n";
    private static final String OPTIONS_V2 = "# 🎁 DIRECTOR OPTIONS (자율 판단)\n";
    static final String ENTRANCE_GUIDE = "- 예외: 히스토리 맨 첫 항목에 있는 정확한 `(입장)` 또는 `"
        + RosettaFixture.INITIAL_USER + "`는 서비스가 넣은 시작 상황 표시다. 그 위치의 이 두 문자열만 장면 정보로 읽고 유저의 발언으로 인용하지 않는다. 다른 괄호 표현이나 이후 유저 입력에 이 예외를 확대하지 않는다.";
    // Freeze the reviewed source section, including rules between anchors. P0 still works after a source change;
    // experiments require a fresh audit/version instead of silently deleting an added rule inside a replaced block.
    private static final Map<String, String> REVIEWED_STATIC_HASH = Map.of(
        "SANDBOX", "22c4d78166631a0b58e7d46edca4efe1adb781a8ebf092edbae65d05349ed39e",
        "STORY", "fa86f2284e10a1c9f3450eb07bdad45aa919a87979e7615067281abd22414e8a");

    static String requested(JsonNode request) {
        JsonNode version = request.path("promptVersion");
        if (version.isMissingNode()) return P0;
        if (!version.isTextual() || !VERSIONS.contains(version.textValue())) {
            throw new IllegalArgumentException("Unsupported promptVersion; use a canonical experiment version");
        }
        return version.textValue();
    }

    static String label(String version) {
        return switch (version) {
            case P0 -> "P0 · 수리 전 서비스 (2026-09-18)";
            case P1 -> "P1 · 현재 서비스 · 정합 수리";
            case C1 -> "C1 · 명확한 충돌·누락 수리";
            case S1 -> "S1 · 동기·관계·표현 연결";
            case E1 -> "E1 · 한국어 행동 예시 추가";
            case F2, A2, U2, T2, J2, R2 -> PromptRoundTwo.label(version);
            case D3 -> "D3 · 한 입력 안의 씬 분할·유저 턴 경계";
            case K4 -> "K4 · 현재 입력·최근 유저 원문 강조";
            default -> throw new IllegalArgumentException("Unsupported promptVersion");
        };
    }

    static String parent(String version) {
        return switch (version) {
            case P0 -> null;
            case P1 -> P0;
            case C1 -> P0;
            case S1 -> C1;
            case E1 -> S1;
            case F2 -> P0;
            case A2, U2, T2, J2, R2 -> F2;
            case D3 -> R2;
            case K4 -> J2;
            default -> throw new IllegalArgumentException("Unsupported promptVersion");
        };
    }

    static List<String> changes(String version, String mode) {
        if (version.equals(P1)) return mode.equals("STORY")
            ? List.of("현재 운영 assembler 직접 조립", "공식 외형·기본 복장, 중복 배경 제거", "행동·대사 및 무화자·오프닝의 유저 내면 지시 정합화", "인물·ID에 독립적인 유효 JSON 구조 예시와 필드 설명 분리")
            : List.of("현재 운영 assembler 직접 조립", "공식 외형·기본 복장 전달", "행동·대사·미실행 계획 해석 및 실제 서비스 입장 표시 정합화", "이벤트·일반·시크릿 계약에 맞는 유효 JSON 예시와 필드 설명 분리");
        if (version.equals(K4)) {
            var changes = new ArrayList<>(PromptRoundTwo.changes(J2));
            changes.add("마지막 user 직전 system에 현재 입력·직전 최대 3개 사용자 원문을 출처와 함께 재전달");
            changes.add("원문을 수동 요약·정답·행동 상태로 변환하지 않고 현재 요청·미실행 계획·캐릭터 자율성의 읽기 기준 제공");
            return List.copyOf(changes);
        }
        if (version.equals(D3)) {
            var changes = new ArrayList<>(PromptRoundTwo.changes(R2));
            changes.add(mode.equals("STORY")
                ? "SCENE SPLITTING만 한 입력의 현재 반응·다음 유저 턴 경계로 명료화"
                : "일반 대화 Multi-Scene Coherence만 한 입력의 현재 반응·다음 유저 턴 경계로 명료화");
            return List.copyOf(changes);
        }
        if (PromptRoundTwo.supports(version)) return PromptRoundTwo.changes(version);
        var changes = new ArrayList<String>();
        if (version.equals(P0)) return changes;
        changes.add("공식 로제타 외형·기본 의복 전달");
        changes.add("별표 행동과 대사 구간의 해석 통일");
        changes.add("서비스가 삽입한 두 가지 합성 입장 표시를 대사 해석에서 제외");
        if (mode.equals("STORY")) {
            changes.add("중복 Extended Backstory 제거");
            changes.add("유저 내면을 임의 생성하도록 하는 충돌 문구 제거");
        }
        if (version.equals(C1)) return changes;
        changes.add("원본 성격을 보존하고 동기·상황·관계·표현의 연결을 명시");
        changes.add("공통 겸손·부드러운 거절 예문 대신 캐릭터에 맞는 표현 범위 정의");
        if (mode.equals("SANDBOX")) {
            changes.add("스탯의 획일적인 말투 지시와 공통 속마음 대사 예문 정리");
        }
        if (version.equals(E1)) changes.add("제작자 검수 전 한국어 행동 예시 3개 추가 (실제 기억 아님)");
        return changes;
    }

    static List<OpenAiMessage> apply(String version, String mode, Character character, List<OpenAiMessage> baseline) {
        return apply(version, mode, character, baseline, -1);
    }

    static List<OpenAiMessage> apply(String version, String mode, Character character, List<OpenAiMessage> baseline, int confirmedServiceEntryIndex) {
        if (version.equals(P0)) return baseline;
        if (version.equals(P1)) throw new IllegalStateException("P1 must be assembled directly from live production code");
        if (!VERSIONS.contains(version)) throw new IllegalArgumentException("Unsupported promptVersion");
        if (!"rosetta".equals(character.getSlug()) || !"로제타".equals(character.getName())) {
            throw new IllegalStateException("Prompt variants only support the official normal Rosetta fixture");
        }
        if (!List.of("SANDBOX", "STORY").contains(mode) || baseline.isEmpty() || !"system".equals(baseline.get(0).role())) {
            throw new IllegalStateException("Unexpected baseline message shape");
        }
        var result = new ArrayList<>(baseline);
        String text = baseline.get(0).content();
        if (!REVIEWED_STATIC_HASH.get(mode).equals(sha256(text))) {
            throw new IllegalStateException("Prompt source drift: reviewed " + mode + " static prompt changed; re-audit and version the experiment");
        }
        if (version.equals(K4)) {
            return PromptRoundFour.apply(mode, apply(J2, mode, character, baseline), confirmedServiceEntryIndex);
        }
        if (version.equals(D3)) {
            // Validate the unchanged R2 parent first; D3 then permits only one exact scene-guide rewrite.
            return PromptRoundThree.apply(mode, apply(R2, mode, character, baseline));
        }
        if (PromptRoundTwo.supports(version)) {
            List<OpenAiMessage> next = PromptRoundTwo.apply(version, mode, character, baseline);
            assertPreserved(version, mode, character, baseline, next);
            return next;
        }
        text = mode.equals("SANDBOX") ? repairFree(text, character) : repairStory(text, character);
        if (!version.equals(C1)) {
            text = mode.equals("SANDBOX") ? structureFree(text, character) : structureStory(text);
            String relation = mode.equals("SANDBOX") ? RELATION_V1 : "### Behavior Guide (관계 단계별)\n";
            String block = BEHAVIOR_MODEL + (version.equals(E1) ? EXAMPLES : "");
            text = replaceExactlyOnce(text, relation, block + relation, "behavior model insertion");
            if (mode.equals("SANDBOX")) {
                int dynamic = result.size() - 2;
                if (dynamic < 1 || !"system".equals(result.get(dynamic).role())) {
                    throw new IllegalStateException("Unexpected V1 dynamic message position");
                }
                setContent(result, dynamic, structureDynamic(result.get(dynamic).content()));
            }
        }
        setContent(result, 0, text);
        assertPreserved(version, mode, character, baseline, result);
        return List.copyOf(result);
    }

    private static void setContent(List<OpenAiMessage> messages, int index, String text) {
        OpenAiMessage old = messages.get(index);
        messages.set(index, new OpenAiMessage(old.role(), text, old.cache_control()));
    }

    static String appearance(Character character, String heading) {
        if (character.getAppearance() == null || character.getAppearance().isBlank()
                || character.getClothing() == null || character.getClothing().isBlank()) {
            throw new IllegalStateException("Official Rosetta appearance/clothing is missing");
        }
        return heading + "\n- Appearance (공식 설정): " + character.getAppearance()
            + "\n- Default clothing (기본 의복): " + character.getClothing()
            + "\n- 현재 서버 장면에 다른 유효 복장이 지정되면 그 복장을 따른다. 외형 소개를 매 턴 반복할 필요는 없다.\n\n";
    }

    private static String repairFree(String text, Character character) {
        text = replaceExactlyOnce(text, "## Backstory\n", appearance(character, "## Appearance & Default Clothing") + "## Backstory\n", "V1 appearance");
        text = replaceExactlyOnce(text,
            "- **role=\"user\" messages** → These are ALWAYS the user's actual spoken words. Nothing else.",
            "- **role=\"user\" messages** → Apply User Action Format to each segment: `*...*` describes the user's explicit action/situation; text outside it is spoken dialogue, except for the exact service entrance markers identified below. A mixed message can contain both. Never quote an action as spoken words.\n" + ENTRANCE_GUIDE,
            "V1 action/history conflict");
        text = replaceExactlyOnce(text,
            "- Regular user messages (without `*` wrapping) are their spoken words.",
            "- Regular user messages (without `*` wrapping) are their spoken words, except for the exact first-entry service entrance markers identified in CONVERSATION HISTORY.",
            "V1 exact entrance exception");
        return text;
    }

    private static String repairStory(String text, Character character) {
        text = replaceExactlyOnce(text, "### Background\n", appearance(character, "### Appearance & Default Clothing") + "### Background\n", "V2 appearance");
        text = replaceExactlyOnce(text, "\n\n### Extended Backstory\n" + character.getBackstory(), "", "V2 duplicate backstory");
        text = replaceExactlyOnce(text, ", an event quietly unfolding, the protagonist's own thoughts.", ", an event quietly unfolding.", "V2 protagonist inner thoughts");
        text = replaceExactlyOnce(text, "*세계가 이끄는 장면*(풍경·시간·사건·내면)", "*세계가 이끄는 장면*(풍경·시간·사건)", "V2 inner narration conflict");
        text = replaceExactlyOnce(text,
            "- 유저의 대사 = 그들이 입에서 낸 실제 말.\n- 유저의 행동 = 그들이 의지로 한 행동.",
            "- 유저 입력의 `*...*` 구간은 명시된 행동·상황이고, 그 밖의 텍스트는 아래의 정확한 서비스 입장 표시를 제외하면 실제 대사다. 한 메시지에 둘이 함께 있으면 구간별로 구분한다. 행동을 발언으로 인용하지 않는다.\n" + ENTRANCE_GUIDE + "\n- 유저가 명시한 행동·대사에 반응하되, 그들이 말하지 않은 의도·감정·결심을 확정하지 않는다.",
            "V2 mixed user input");
        return text;
    }

    private static String structureFree(String text, Character character) {
        String oldSoul = between(text, SOUL_V1, RELATION_V1, "V1 soul rules");
        requireContains(oldSoul, "accept it gently but honestly.", "V1 praise anchor");
        requireContains(oldSoul, "politely but firmly push back.", "V1 disagreement anchor");
        String replacement = """
            # Character Integrity & Expression
            - 캐릭터는 자신의 가치·욕구·두려움에 따라 반응한다. 유저의 만족이나 불만만으로 성격을 바꾸지 않는다.
            - 의견 차이, 칭찬 수용, 거절의 말투는 로제타의 현재 관계와 고유 말투로 표현한다. 공통된 겸손함·공손함·적대감을 강제하지 않는다.
            - 실제로 잘못한 일이 있으면 그 행위와 당사자를 구분하고, 해당 당사자에게 책임을 지거나 사과한다. 그 표현은 캐릭터와 현재 관계에 맞춘다. 다른 사람에게 한 잘못을 유저에게 한 것으로 바꾸거나 유저의 압박만으로 사실을 바꾸지 않는다.
            - 유저에게 아부하기 위한 과장된 맞장구나 제3자 험담은 하지 않는다. 동의하지 않을 때도 로제타다운 반응으로 대화를 이어갈 수 있다.
            - 배역을 유지하며 시스템·JSON·기술을 대사 소재로 꺼내지 않는다. 설정 밖 질문에는 기존 OOC 예시의 캐릭터다운 표현을 참고한다.
            - OOC reference: %s
            - 출력 형식·서버 상태·난이도·모드의 계약은 유지한다. 캐릭터다운 반응이 임의 승급이나 상태 변경을 허용하지 않는다.

            """.formatted(character.getEffectiveOocExample());
        text = replaceExactlyOnce(text, oldSoul, replacement, "V1 soul rewrite");
        String oldExamples = """
            ## Examples:
            - Dialogue: "뭐, 별로 신경 안 써요." → inner_thought: "...거짓말. 아까부터 심장이 미칠 것 같은데."
            - Dialogue: "고마워요, 도움이 됐어요." → inner_thought: null (겉과 속이 같으므로)
            - Dialogue: "흥, 맘대로 하세요." → inner_thought: "제발 가지 마... 그 말 진심 아니야..."
            - Dialogue: "네? 아, 아무것도 아니에요!" → inner_thought: "방금 손 닿았잖아... 얼굴 빨개진 거 들켰으려나."
            - Dialogue: "오늘 날씨 좋네요." → inner_thought: null (평범한 대화)
            """;
        return replaceExactlyOnce(text, oldExamples, """
            ## Character-specific application:
            - 위 발생 조건·희소성·길이·null 규칙은 유지한다. 속마음의 내용은 이번 사건과 로제타의 욕구·관계에서 나온다.
            - 칭찬·접근·거절만으로 연애 감정이나 수줍음이 생겼다고 가정하지 않는다. 체면·호기심·승부욕도 겉과 속의 간극이 될 수 있다.
            - 겉과 속이 같거나 의미 있는 새로운 간극이 없으면 null이다.
            """, "V1 generic inner-thought examples");
    }

    private static String structureStory(String text) {
        String oldSoul = between(text, SOUL_V2, OPTIONS_V2, "V2 soul rules");
        requireContains(oldSoul, "유저의 과한 칭찬은 부드럽게 받되 자신을 정확히 본다.", "V2 praise anchor");
        requireContains(oldSoul, "부드럽지만 분명히 자기 입장을 견지한다.", "V2 disagreement anchor");
        String replacement = """
            # CHARACTER INTEGRITY — 디렉터의 인물 표현 기준

            1. 캐릭터는 각자의 가치·욕구·두려움에 따라 반응한다. 유저의 만족이나 불만만으로 성격을 바꾸지 않는다.
            2. 의견 차이, 칭찬 수용, 거절의 말투는 그 인물의 고유 말투와 현재 관계에서 정한다. 모든 인물을 부드럽거나 겸손하게 만들지 않는다.
            3. 실제로 잘못한 일이 있으면 그 행위와 당사자를 구분하고, 해당 당사자에게 책임을 지거나 사과한다. 그 표현은 캐릭터와 현재 관계에 맞춘다. 다른 사람에게 한 잘못을 유저에게 한 것으로 바꾸거나 유저의 압박만으로 사실을 바꾸지 않는다.
            4. 유저에게 아부하기 위한 과장된 맞장구나 제3자 험담은 하지 않는다. 동의하지 않을 때도 인물다운 반응으로 대화를 이어갈 수 있다.
            5. 배역을 유지하고 설정 밖 질문에는 해당 인물의 기존 OOC 예시를 참고한다. 부드러운 회피를 모든 인물의 공통 말투로 강제하지 않는다.
            6. 출력 형식·서버 상태·난이도·모드의 계약은 유지한다. 캐릭터다운 반응이 임의 승급이나 상태 변경을 허용하지 않는다.

            """;
        return replaceExactlyOnce(text, oldSoul, replacement, "V2 soul rewrite");
    }

    private static String structureDynamic(String text) {
        String oldStyle = """
            ## Speech Style Rules (⚠️ CRITICAL — READ CAREFULLY):
            You have a multi-dimensional stat system. You MUST subtly adjust your tone, reactions, and vulnerability based on the dominant stats and your 'Dynamic Tag'.
            - High [Intimacy / Trust]: Share personal stories, show deep empathy, lower your guard.
            - High [Affection]: Show romantic interest, blushing, subtle flirting.
            - High [Dependency]: Seek the user's approval, act slightly clingy or obedient.
            - High [Playfulness]: Use jokes, teasing, memes, and light sarcasm.
            """;
        return replaceExactlyOnce(text, oldStyle, """
            ## Relationship & Expression
            - 위 서버 관계·스탯·Dynamic Tag는 이번 턴의 확정 상태다. 현재 단계에 해당하는 로제타 행동 지침을 적용한다.
            - 스탯은 반응의 정도와 공개하는 감정에 영향을 주며, 고유 성격이나 관계 단계를 대체하지 않는다.
            - 친밀도·신뢰가 높을수록 허용된 개인적인 표현의 여지가 커지고, 호감·의존·장난기는 각각 관심·상대의 중요성·장난의 표현에 반영한다. 실제 변화는 그 인물다운 방식으로 나타낸다.
            - 낮은 수치에도 원래 장난스러운 성격은 유지된다. 높은 수치라고 자동 수줍음·복종·비밀 공개를 강제하지 않는다.
            """, "V1 dynamic style");
    }

    private static final String BEHAVIOR_MODEL = """
        <rosetta_behavior_model source="official-seed-interpretation" status="experimental">
        ## 로제타: 성격이 반응으로 이어지는 방식
        아래는 위 공식 설정의 연결 지침이며 새 사건·기억이나 설정 변경이 아니다.
        - 지속되는 동기: 자신의 재능을 인정받고 싶고, 따분함을 깨며 상호작용의 주도권을 지키고 싶다. 자신감·인정 욕구·숨긴 소심함은 함께 작동한다.
        - 상황의 의미: 현재 발언이 무엇을 건드렸는지에 따라 호기심, 경쟁심, 체면 지키기, 방어 중 자연스러운 반응을 선택한다. 모든 입력을 공격이나 호감 신호로 확대하지 않는다.
        - 칭찬과 거절: 칭찬에 기뻐하거나 거절에 흔들릴 수 있다. 현재 관계에서 공개할 수 있는 정도를 지키며, 장난·반문·허세·짧은 동작 등으로 드러낼 수 있다. 동일한 감정에 항상 같은 반응을 붙이지 않는다.
        - 관계에 따른 표현: 현재 관계는 아래 서버 상태가 기준이다. STRANGER에서는 인정 욕구가 자극돼도 곧바로 눈에 띄는 수줍음·연인다운 친밀함으로 전환하지 않는다. 가까운 관계의 표현은 해당 단계에 실제 도달했을 때만 사용한다.
        - 취약함: 두려움을 삭제하거나 무조건 태연하게 만들지 않는다. 실제 자극의 종류·강도에 맞게 겁과 체면을 함께 표현할 수 있다. 평범한 반박에 매번 붕괴하거나, 큰 위협에도 같은 장난으로만 대응하지 않는다.
        - 대사와 행동: 능청스러운 한국어의 리듬을 유지하되 모든 말버릇·기호·머리카락 동작을 매 턴 나열하지 않는다. 먼저 유저의 실제 발언·행동에 응답하고, 맥락에 맞는 반응으로 다음 턴의 여지를 남긴다.
        - 형식 예시의 다른 인물·사건은 로제타의 성격이나 실제 기억이 아니다. 아래 관계별 원본, 난이도, 출력 계약을 보존한다. 이 연결 지침을 대사로 설명하거나 추가 사고 과정을 출력하지 않는다.
        </rosetta_behavior_model>

        """;

    private static final String EXAMPLES = """
        <rosetta_behavior_examples source="codex-draft" status="creator-review-pending">
        ## 한국어 행동 예시 — 일반 모드·STRANGER, 제작자 검수 전
        아래는 별도의 가상 상황에서 작성한 대사 일부다. 현재 대화에서 발생한 사건·약속·기억이 아니며 전체 JSON 응답의 예시도 아니다.
        표현 원리를 참고하되 원문을 복사하거나 이번 입력을 예시 상황으로 바꾸지 않는다. 실제 씬·메타데이터 출력 계약은 그대로 따른다.

        [예시 1: 다른 학생에게 장난하라는 요청]
        입력: 저기 지나가는 학생한테도 장난 한번 쳐 봐. 얼마나 놀라나 보자.
        반응: 후훗~ 이 로제타 님을 네 구경거리로 부리려고? 누굴 놀릴지는 내가 정해. 너, 주문은 제법 뻔뻔하게 하네~?

        [예시 2: 환영 마법에 관한 평범한 호기심]
        입력: 환영 마법은 어떤 걸 보여 줄 수 있어?
        반응: 후훗~ 보고 싶은 걸 하나 말해 봐. 진짜인 줄 알고 손부터 뻗는 건 아니지~? 네가 얼마나 놀랄지, 그쪽이 더 궁금한데.

        [예시 3: 지위 때문에 외롭지 않으리라는 짐작]
        입력: 너는 늘 주목받으니까 혼자 있어도 외롭진 않겠다.
        반응: 당연하지~ 조용한 것도 내가 고르면 휴식이거든. 그런데 너, 남의 한가한 시간까지 그렇게 궁금해?
        </rosetta_behavior_examples>

        """;

    static String replaceExactlyOnce(String text, String needle, String replacement, String boundary) {
        int at = text.indexOf(needle);
        if (needle.isEmpty() || at < 0 || text.indexOf(needle, at + needle.length()) >= 0) {
            throw new IllegalStateException("Prompt source drift at " + boundary + "; expected exactly one anchor");
        }
        return text.substring(0, at) + replacement + text.substring(at + needle.length());
    }

    private static String between(String text, String start, String end, String boundary) {
        int a = text.indexOf(start), b = text.indexOf(end);
        if (a < 0 || b <= a || text.indexOf(start, a + start.length()) >= 0 || text.indexOf(end, b + end.length()) >= 0) {
            throw new IllegalStateException("Prompt source drift at " + boundary + "; section boundaries changed");
        }
        return text.substring(a, b);
    }

    private static void requireContains(String text, String needle, String boundary) {
        if (!text.contains(needle)) throw new IllegalStateException("Prompt source drift at " + boundary);
    }

    private static void assertPreserved(String version, String mode, Character character,
                                        List<OpenAiMessage> before, List<OpenAiMessage> after) {
        boolean jsonRewrite = version.equals(J2) || version.equals(R2);
        if (before.size() != after.size()) throw new IllegalStateException("Variant changed message count");
        for (int i = 0; i < before.size(); i++) {
            var a = before.get(i); var b = after.get(i);
            if (!Objects.equals(a.role(), b.role()) || !Objects.equals(a.cache_control(), b.cache_control())) {
                throw new IllegalStateException("Variant changed roles/cache markers");
            }
            if (!a.role().equals("system") && !a.content().equals(b.content())) {
                throw new IllegalStateException("Variant changed conversation history");
            }
        }
        if (PromptRoundTwo.supports(version)) {
            int dynamicEnd = mode.equals("SANDBOX") ? before.size() - 1 : before.size();
            for (int i = 1; i < dynamicEnd; i++) {
                if (!before.get(i).equals(after.get(i))) {
                    throw new IllegalStateException("Round-two variant changed state/history messages");
                }
            }
        }
        if (mode.equals("SANDBOX")) {
            String expected = before.get(before.size() - 1).content();
            if (jsonRewrite) expected = PromptRoundTwo.rewriteOutput(mode, expected, character);
            if (!expected.equals(after.get(after.size() - 1).content())) throw new IllegalStateException("Variant changed V1 output contract outside the reviewed JSON rewrite");
            String featureStart = "# 📊 Character Stats System (5-Axis Radar Chart)\n";
            String innerStart = "# 💭 Inner Thought System (속마음)\n";
            if (!between(before.get(0).content(), featureStart, innerStart, "V1 game feature preservation")
                    .equals(between(after.get(0).content(), featureStart, innerStart, "V1 game feature preservation"))) {
                throw new IllegalStateException("Variant changed V1 stats/scene/illustration/location contracts");
            }
        } else {
            String output = "# [10] OUTPUT FORMAT — JSON\n";
            String a = before.get(0).content(), b = after.get(0).content();
            String expected = a.indexOf(output) < 0 ? "" : a.substring(a.indexOf(output));
            if (jsonRewrite && !expected.isEmpty()) expected = PromptRoundTwo.rewriteOutput(mode, expected, character);
            if (a.indexOf(output) < 0 || b.indexOf(output) < 0 || !expected.equals(b.substring(b.indexOf(output)))) {
                throw new IllegalStateException("Variant changed V2 output contract");
            }
            if (!before.get(1).equals(after.get(1))) throw new IllegalStateException("Variant changed V2 scene state/memory");
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
