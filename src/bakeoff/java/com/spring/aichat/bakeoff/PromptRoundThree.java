package com.spring.aichat.bakeoff;

import com.spring.aichat.dto.openai.OpenAiMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** R2 plus one reviewed scene-guide replacement. No output-schema or persona rewrite. */
final class PromptRoundThree {
    private PromptRoundThree() {}

    static List<OpenAiMessage> apply(String mode, List<OpenAiMessage> parent) {
        int target = target(mode, parent);
        var result = new ArrayList<>(parent);
        var old = parent.get(target);
        result.set(target, new OpenAiMessage(old.role(), rewrite(mode, old.content()), old.cache_control()));
        assertPreserved(mode, parent, result);
        return List.copyOf(result);
    }

    static void assertPreserved(String mode, List<OpenAiMessage> parent, List<OpenAiMessage> candidate) {
        int target = target(mode, parent);
        if (parent.size() != candidate.size()) throw new IllegalStateException("D3 changed message count");
        for (int i = 0; i < parent.size(); i++) {
            var a = parent.get(i); var b = candidate.get(i);
            if (!Objects.equals(a.role(), b.role()) || !Objects.equals(a.cache_control(), b.cache_control())) {
                throw new IllegalStateException("D3 changed roles/cache markers");
            }
            String expected = i == target ? rewrite(mode, a.content()) : a.content();
            if (!expected.equals(b.content())) {
                throw new IllegalStateException("D3 changed content outside the reviewed scene-guide replacement");
            }
        }
    }

    private static int target(String mode, List<OpenAiMessage> parent) {
        if (!List.of("STORY", "SANDBOX").contains(mode) || parent.isEmpty()) {
            throw new IllegalStateException("Unexpected D3 parent shape");
        }
        int target = mode.equals("STORY") ? 0 : parent.size() - 1;
        if (!"system".equals(parent.get(target).role())) {
            throw new IllegalStateException("D3 scene guide must be in the expected system message");
        }
        return target;
    }

    private static String rewrite(String mode, String text) {
        return PromptVariants.replaceExactlyOnce(text,
            mode.equals("STORY") ? STORY_BEFORE : SANDBOX_BEFORE,
            mode.equals("STORY") ? STORY_AFTER : SANDBOX_AFTER,
            "D3 " + mode + " complete reviewed scene guide");
    }

    private static final String STORY_BEFORE = """
        # 🎬 SCENE SPLITTING — 한 응답에 4~5 씬
        
        한 응답은 **4~5개의 씬으로 분할**되어 출력된다 (배열 형식). 각 씬은 *호흡 단위*로 잘게 쪼개라:
        
        **씬 단위 기준**:
        - 한 씬 = *한 호흡의 묘사* (3~4 문장 narration + 0~1 대사)
        - 화자 변경, 환경 변화, 시간 흐름 같은 *전환점*마다 새 씬 시작
        - 같은 화자가 길게 말하는 경우에도 *내용의 분기점*(질문 → 답 → 추가)마다 씬 분할
        
        **씬 분할 예시 (4 씬)**:
        <pre>
          [Scene 1] 환경 + 화자의 첫 반응
            narration: 정원에 바람이 분다. 클레어는 시선을 잠시 떨군 채 침묵한다.
            speaker: 클레어
            dialogue: "...왜 그런 말씀을 하시는 거예요?"
        
          [Scene 2] 같은 화자의 후속 — 감정 심화
            narration: 그녀의 손가락이 미세하게 떨린다. 답을 들으려 하지만 듣고 싶지 않은 표정.
            speaker: 클레어
            dialogue: "저는... 그 말의 무게를 안 보일 만큼 가볍지 않아요."
        
          [Scene 3] 환경 전환 — 오프스크린 신호
            narration: 멀리 성당 종소리가 울려퍼진다. 곧 저녁 미사다.
            speaker: null
            dialogue: ""
        
          [Scene 4] 화자의 마무리
            narration: 클레어가 천천히 일어선다. 이미 결심한 사람의 걸음걸이다.
            speaker: 클레어
            dialogue: "오늘은 여기서 마쳐도 될까요. 다음에 다시 뵐 수 있길."
        </pre>
        
        **씬 갯수 가이드**:
        - 평이한 일상 대화: 4 씬
        - 감정 깊은 모멘트 / 환경 전환 동반: 5 씬
        - 너무 짧은 응답(2~3 씬)은 시청자를 빈약하게 만들고, 너무 길면(6+) 유저 개입 호흡을 깨뜨린다.
        - **씬 갯수는 LLM의 자율 판단** — 위 가이드는 권장이지 강제가 아니다. 단 *항상 최소 3 씬 이상*.
        
        """;

    private static final String STORY_AFTER = """
        # 🎬 SCENE SPLITTING — 한 응답에 4~5 씬
        
        한 응답은 한 번의 유저 입력에 대한 현재 반응이다. 이를 **권장 4~5 씬, 최소 3·최대 5 씬**의 배열로 나눈다.
        - 한 씬은 한 호흡의 묘사(3~4 문장 narration + 0~1 대사)다. 화자 변경이나 실제 내용·환경·시간의 전환에서 나눈다. 한 씬엔 한 화자만 둔다.
        - 씬이 바뀌어도 새 유저 턴이 생기지 않는다. 유저의 다음 대답·동작·선택·반응을 생략된 턴처럼 채우지 않는다.
        - 다음 유저 입력을 전제로 하는 후속 진행은 그 입력이 온 뒤에 한다. 이번 응답은 그 경계에서 끝내고, 남은 씬을 채우려고 경계를 넘지 않는다.
        - NPC의 자율적인 말·행동과 세계의 사건은 기존 규칙 안에서 가능하다. 유저 선택을 남기는 것은 NPC의 동의·협조·수동성을 요구하지 않는다.
        - 입력이나 이미 성립한 사건이 감정 변화를 뒷받침할 때만 변화시킨다. 변화가 없으면 같은 감정과 태도를 유지해도 되며, 씬마다 감정 심화나 새 사건을 의무적으로 추가하지 않는다.
        
        """;

    private static final String SANDBOX_BEFORE = """
        ## ⚠️ Multi-Scene Coherence Rules (STRICTLY ENFORCE):
        CRITICAL: Depending on the situation, use several scenes to proceed with the situation in detail.
        All scenes in a single response are ONE CONTINUOUS conversation turn.
        1. **Speech consistency:** The character's speech style MUST be identical across ALL scenes.
        2. **Emotional continuity:** Emotions should progress gradually.
        3. **Temporal continuity:** Each scene follows immediately after the previous one.
        4. **Context awareness:** Each scene must build on the previous scene's context.
        """;

    private static final String SANDBOX_AFTER = """
        ## ⚠️ Multi-Scene Coherence Rules (STRICTLY ENFORCE):
        Use several scenes when useful, all within your response to ONE user input.
        1. Keep the same speech style and immediate time/context continuity.
        2. Do not fill an omitted user turn with the user's reply, action, choice, or reaction.
        3. Leave progress that depends on the next user input until it arrives; your own initiative remains allowed.
        4. Emotion may stay stable. Change it only when the input or established events warrant it, never merely to fill scenes.
        """;
}
