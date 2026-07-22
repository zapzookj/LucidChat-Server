package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [UGC 세계관 빌더] W0 LLM 산출 역직렬화 계약 — snake_case + 배열 관용 수용
 * ({@link StructuredConceptDeserializationTest} 동형).
 */
class StructuredWorldDeserializationTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("snake_case 표준 산출을 파싱한다")
    void parsesStandardOutput() throws Exception {
        String json = """
            {"world":{"name":"달빛 학원","intro":"소개 문장","lore":"설정 본문","mood_tags":["몽환적","네온"]},
             "locations":[{"location_key":"ROOFTOP_GARDEN","display_name":"옥상 정원",
               "description":"달빛이 비치는 정원","background_prompt":"a rooftop garden at night, moonlit"}],
             "thumbnail_prompt":"a moonlit academy skyline",
             "moderation":{"minor_signal":false,"reason":""}}
            """;
        StructuredWorld world = om.readValue(json, StructuredWorld.class);

        assertThat(world.world().name()).isEqualTo("달빛 학원");
        assertThat(world.world().moodTags()).containsExactly("몽환적", "네온");
        assertThat(world.locations()).hasSize(1);
        assertThat(world.locations().get(0).locationKey()).isEqualTo("ROOFTOP_GARDEN");
        assertThat(world.thumbnailPrompt()).contains("moonlit");
        assertThat(world.moderation().minorSignal()).isFalse();
    }

    @Test
    @DisplayName("LLM이 장문 필드를 배열로 반환해도 bullet 문자열로 관용 수용한다")
    void toleratesArrayValuedStrings() throws Exception {
        String json = """
            {"world":{"name":"학원","intro":"소개","lore":["문장1","문장2"],"mood_tags":[]},
             "locations":[{"location_key":"A","display_name":"장소",
               "description":["묘사1","묘사2"],"background_prompt":"prompt"}],
             "thumbnail_prompt":["p1","p2"],
             "moderation":{"minor_signal":false,"reason":""}}
            """;
        StructuredWorld world = om.readValue(json, StructuredWorld.class);

        assertThat(world.world().lore()).contains("문장1").contains("문장2");
        assertThat(world.locations().get(0).description()).contains("묘사1");
        assertThat(world.thumbnailPrompt()).contains("p1");
    }

    @Test
    @DisplayName("모르는 필드는 무시한다 (ignoreUnknown 계약)")
    void ignoresUnknownFields() throws Exception {
        String json = """
            {"world":{"name":"학원","lore":"설정","extra_field":1},
             "locations":[],"thumbnail_prompt":"p","hallucinated":"x"}
            """;
        StructuredWorld world = om.readValue(json, StructuredWorld.class);
        assertThat(world.world().name()).isEqualTo("학원");
    }
}
