package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [UGC v1] Stage 0 산출 관용 역직렬화 계약 — 2026-07-20 실측 버그(core_values 배열 반환) 회귀 방지.
 */
class StructuredConceptDeserializationTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("core_values/flaws가 배열로 와도 bullet 문자열로 결합된다 (실측 재현 케이스)")
    void arraysAreFlattenedToBulletStrings() throws Exception {
        String json = """
            { "appearance_tags": ["1girl", "brown hair"],
              "scene_tags": ["library"],
              "bg_color": "light gray",
              "character": {
                "name": "하연", "tagline": "다정한 사서", "age": 24, "role": "도서관 사서",
                "personality": "차분하고 다정하다.",
                "tone": "존댓말",
                "appearance": "갈색 생머리",
                "clothing": "니트",
                "backstory": "오래된 도서관에서 자랐다.",
                "core_values": ["책은 사람을 잇는다", "- 약속은 지킨다", "조용한 친절"],
                "flaws": ["거절을 못 한다", "혼자 끙끙 앓는다"],
                "speech_quirks": "말끝에 '...요'를 길게 끈다",
                "first_greeting": "어서 오세요."
              },
              "moderation": { "minor_signal": false, "reason": "" } }
            """;

        StructuredConcept concept = om.readValue(json, StructuredConcept.class);

        assertThat(concept.character().coreValues())
            .isEqualTo("- 책은 사람을 잇는다\n- 약속은 지킨다\n- 조용한 친절");
        assertThat(concept.character().flaws())
            .isEqualTo("- 거절을 못 한다\n- 혼자 끙끙 앓는다");
        // 문자열 필드는 그대로
        assertThat(concept.character().personality()).isEqualTo("차분하고 다정하다.");
        assertThat(concept.character().name()).isEqualTo("하연");
        assertThat(concept.character().age()).isEqualTo(24);
    }

    @Test
    @DisplayName("전 필드 문자열인 정상 응답도 그대로 통과한다")
    void plainStringsPassThrough() throws Exception {
        String json = """
            { "appearance_tags": ["1girl"], "scene_tags": [], "bg_color": "light gray",
              "character": { "name": "설아", "age": 23,
                "core_values": "- 진실만 말한다", "flaws": "- 고집이 세다",
                "backstory": "과거사", "speech_quirks": "버릇", "first_greeting": "안녕",
                "personality": "성격", "tone": "말투", "appearance": "외형", "clothing": "복장",
                "tagline": "태그", "role": "역할" },
              "moderation": { "minor_signal": false, "reason": "" } }
            """;

        StructuredConcept concept = om.readValue(json, StructuredConcept.class);

        assertThat(concept.character().coreValues()).isEqualTo("- 진실만 말한다");
        assertThat(concept.character().flaws()).isEqualTo("- 고집이 세다");
    }
}
