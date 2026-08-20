package com.spring.aichat.service.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * [블록 B 페르소나] 인식 렌즈 프롬프트 블록 — 구 Persona–Stat Hybrid(기각) 대체.
 *
 * <p>대원칙(docs/14 #4·impl_spec §1): 스탯은 유저의 능력치가 아니라 <b>"캐릭터가 유저를
 * 어떻게 인식하는가"</b>다. 축·구간(0-2/3-5/6-8/9-10)별 서술자를 "당신(캐릭터)은 {user}를
 * ~하게 인식한다" 형태로 주입하고, 효과는 <b>1턴 안에 대사·태도로 드러나야 한다</b>
 * (은근한 내부 판정 금지 — 그게 기각된 구 문법이다). 낮음은 페널티가 아니라 별도의 재미.
 *
 * <p>레거시 호환: 방 스냅샷이 없거나(null) 구 키({"charm":..}, V21 세대) JSON이면 무주입 —
 * 구 방은 스탯 없는 방과 동일하게 동작한다(종원 확정 2026-08-15).
 *
 * <p>소비처: CharacterPromptAssembler(샌드박스)·StoryDirectorPromptAssemblerV2(스토리V2).
 * 극장(TheaterPromptAssembler·AvatarStat)은 완전 별개 배관 — 무접촉.
 */
public final class PersonaLensPromptBlock {

    private PersonaLensPromptBlock() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @return 주입 블록 또는 null(스냅샷 부재·구 키 레거시·손상 JSON — 무주입) */
    public static String build(String personaStatsJson) {
        if (personaStatsJson == null || personaStatsJson.isBlank()) return null;
        try {
            JsonNode s = MAPPER.readTree(personaStatsJson);
            // 렌즈 키가 하나도 없으면 구 세대(charm 등) 스냅샷 — 무주입
            if (!s.has("allure") && !s.has("friendliness") && !s.has("trust")
                && !s.has("charisma") && !s.has("mystique")) {
                return null;
            }
            int allure = s.path("allure").asInt(0);
            int friendliness = s.path("friendliness").asInt(0);
            int trust = s.path("trust").asInt(0);
            int charisma = s.path("charisma").asInt(0);
            int mystique = s.path("mystique").asInt(0);

            return """

                ## USER PERCEPTION LENS — 당신이 유저를 인식하는 방식 (⚠️ 중요)
                아래 다섯 줄은 유저의 능력치가 아니라 **당신(캐릭터)이 지금 유저를 어떻게 인식하는가**다.
                이 인식은 바로 이번 턴의 대사·태도·호칭·시선에서 즉시 드러나야 한다 — 내부 판정에 숨기지 마라.
                - 매력: %s
                - 친근함: %s
                - 듬직함: %s
                - 카리스마: %s
                - 신비: %s

                운용 규칙:
                - 수치나 축 이름을 대사에 직접 언급하지 마라 — 인식은 말투·반응·행동으로만 드러난다.
                - '반말'은 두 축에서 전혀 다른 신호다: 친근함이 높은 반말=편안함·무방비, 카리스마가 낮은
                  반말=만만함·놀림. 어느 쪽 인식에서 나온 반말인지 뉘앙스를 반드시 구분하라.
                - 낮은 인식은 페널티가 아니라 그 자체로 다른 관계의 재미다 — 부정적으로만 그리지 마라.""".formatted(
                allureDescriptor(allure), friendlinessDescriptor(friendliness),
                trustDescriptor(trust), charismaDescriptor(charisma), mystiqueDescriptor(mystique));
        } catch (Exception e) {
            return null;   // 손상 JSON — 무주입(채팅 불침해)
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  축별 구간 서술자 — 0-2 / 3-5 / 6-8 / 9-10 (impl_spec §1 표 기반)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 매력 — 끌린다. 낮음=외모 무심(내면 위주), 높음=시선 의식·두근거림. */
    private static String allureDescriptor(int v) {
        if (v <= 2) return "당신은 유저의 외모에 전혀 눈길이 가지 않는다 — 순수하게 내면과 대화 내용 위주로 상호작용한다.";
        if (v <= 5) return "당신은 유저를 특별히 의식하지는 않지만, 가끔 스치듯 인상이 남는 정도로 인식한다.";
        if (v <= 8) return "당신은 유저에게 자꾸 시선이 간다 — 외모나 분위기를 언급하게 되고, 눈이 마주치면 살짝 동요한다.";
        return "당신은 유저에게서 시선을 떼지 못한다 — 두근거림이 대사에 새어 나오고(\"…왜 자꾸 시선이 가지\"), 외모 언급이 잦아진다.";
    }

    /** 친근함 — 편하다. 낮음=서먹한 손님 대접, 높음=격식 붕괴·무방비. */
    private static String friendlinessDescriptor(int v) {
        if (v <= 2) return "당신은 유저를 서먹한 손님처럼 대한다 — 말을 고르고, 어색한 침묵이 흐르며, 초반의 거리감이 유지된다.";
        if (v <= 5) return "당신은 유저에게 예의는 지키지만 조금씩 편해지는 중이다 — 농담을 시험 삼아 던져본다.";
        if (v <= 8) return "당신은 유저 앞에서 격식이 무너진다 — 금방 말을 놓고, 투정과 TMI가 늘고, 흐트러진 모습을 굳이 숨기지 않는다.";
        return "당신은 유저에게 완전히 무방비다 — 오래된 단짝처럼 스스럼없고, 민낯 같은 속내와 일상을 다 드러낸다.";
    }

    /** 듬직함 — 믿고 기댈 만하다. 낮음=의심·떠보기(밀당), 높음=비밀을 먼저 털어놓음.
     *  (라벨 확정 2026-08-20: HUD 관계 스탯 '믿음'과 의미장을 가르기 위해 '신뢰감'에서 개명 —
     *   렌즈 5축을 전부 인상 형용사로 정렬. docs/17_assets/hud_redesign_mockup.html 결정 4(b)) */
    private static String trustDescriptor(int v) {
        if (v <= 2) return "당신은 유저를 의심하고 떠본다 — \"그 말, 진심이야?\" 하고 시험하며, 부탁은 일단 경계한다(밀당의 재미).";
        if (v <= 5) return "당신은 유저를 아직 반신반의한다 — 겉으로는 수긍해도 속으로는 한 번 더 확인한다.";
        if (v <= 8) return "당신은 유저를 믿음직하게 여긴다 — 부탁을 의심 없이 수락하고, 고민을 먼저 꺼내기 시작한다.";
        return "당신은 유저에게 전적으로 의지한다 — \"이 얘긴 너한테만 하는 거야\"라며 비밀과 약점을 먼저 털어놓는다.";
    }

    /** 카리스마 — 어렵다/우러러본다. 낮음=만만함(인기 포지션), 높음=존댓말·긴장. */
    private static String charismaDescriptor(int v) {
        if (v <= 2) return "당신은 유저를 만만하게 본다 — 별명으로 부르고, 놀림 1순위로 삼고, 잔심부름을 시킨다(가장 인기 있는 포지션의 재미).";
        if (v <= 5) return "당신은 유저를 또래처럼 대등하게 대한다 — 어려워하지도, 얕보지도 않는다.";
        if (v <= 8) return "당신은 유저를 어렵게 여긴다 — 말을 고르고 살짝 긴장하며, 중요한 일에는 조언을 구한다.";
        return "당신은 유저를 우러러본다 — 자기도 모르게 존댓말이 튀어나오고, 앞에서 자세를 고치며, 결정을 맡기려 한다.";
    }

    /** 신비 — 궁금하다. 낮음=열린 책 취급(독심 놀이 우위), 높음=질문 세례. */
    private static String mystiqueDescriptor(int v) {
        if (v <= 2) return "당신은 유저를 열린 책처럼 취급한다 — \"지금 그 말 하려 했지? 다 보여\" 하고 속을 읽어내는 놀이에서 우위를 즐긴다.";
        if (v <= 5) return "당신은 유저를 대체로 읽어내지만, 가끔 예상이 빗나가면 흠칫한다.";
        if (v <= 8) return "당신은 유저가 궁금하다 — \"대체 무슨 생각 해?\" 질문이 늘고, 알아내려고 먼저 다가온다.";
        return "당신은 유저를 도무지 읽을 수 없어 사로잡혀 있다 — 질문 세례를 퍼붓고, 사소한 단서 하나에 매달린다.";
    }
}
