package com.spring.aichat.service.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * [2026-08-04 페르소나 풀 패키지] Persona–Stat Hybrid 블록 — Theater 검증 규칙의 전 모드 이식.
 *
 * <p>핵심 재미(종원 원체험): 페르소나 본문 = 유저의 <b>자기인식</b>, 스탯 = <b>객관 현실</b>.
 * 둘이 충돌할 때(예: '세계관 최고 미남' 페르소나 + CHARM 5) 캐릭터/디렉터는 유저의 자기인식을
 * 존중해 반응하되, 객관 묘사와 제3자 반응은 스탯 현실을 따른다 — 이 긴장이 롤플레이의 코어다.
 *
 * <p>방 스냅샷(personaStatsJson)이 없으면 null 반환 — 레거시/카드 미선택 방 완전 무변.
 */
public final class PersonaStatPromptBlock {

    private PersonaStatPromptBlock() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @return 주입 블록 또는 null(스탯 스냅샷 부재 — 무주입) */
    public static String build(String personaStatsJson) {
        if (personaStatsJson == null || personaStatsJson.isBlank()) return null;
        try {
            JsonNode s = MAPPER.readTree(personaStatsJson);
            return """

                ## USER STATS — Persona–Stat Hybrid (⚠️ 중요)
                유저(주인공)에게는 두 겹의 정체성이 있다:
                - **페르소나(위 프로필)** = 유저의 *자기인식* — 유저 내면·1인칭 서술은 이 인식을 따른다.
                - **스탯(아래 수치)** = *객관 현실* — 제3자의 반응·객관 묘사·성공/실패 판정은 이 수치를 따른다.

                | 매력(CHARM) | 재치(WIT) | 대담(BOLDNESS) | 지성(INTELLECT) | 공감(EMPATHY) |
                |---|---|---|---|---|
                | %d | %d | %d | %d | %d |

                운용 규칙:
                - 페르소나와 스탯이 충돌하면(예: 자칭 최고 미남 + 낮은 매력) 유저의 자기인식을 *부정하지 말고*,
                  주변 반응과 객관 묘사로 현실을 드러내라 — 이 간극 자체가 서사다(조롱이 아닌 연출로).
                - 높은 스탯은 해당 축의 시도를 자연스럽게 성공시키고, 낮은 스탯의 시도는 어긋나게 하라.
                - 스탯 수치를 대사에 직접 언급하지 마라 — 반응으로만 드러낸다.""".formatted(
                s.path("charm").asInt(0), s.path("wit").asInt(0), s.path("boldness").asInt(0),
                s.path("intellect").asInt(0), s.path("empathy").asInt(0));
        } catch (Exception e) {
            return null;   // 손상 JSON — 무주입(채팅 불침해)
        }
    }
}
