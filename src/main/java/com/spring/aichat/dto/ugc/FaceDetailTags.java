package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/** Optional artwork metadata; malformed hints must not fail the paid profile operation. */
@JsonDeserialize(using = FaceDetailTags.Deserializer.class)
@com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FaceDetailTags(String eyeColor, String expression) {
    public static class Deserializer extends JsonDeserializer<FaceDetailTags> {
        @Override public FaceDetailTags deserialize(JsonParser p, DeserializationContext context) throws IOException {
            JsonNode value = p.getCodec().readTree(p);
            if (!value.isObject()) return null;
            return new FaceDetailTags(text(value.get("eye_color")), text(value.get("expression")));
        }
        private static String text(JsonNode value) {
            return value != null && value.isTextual() ? value.asText() : null;
        }
    }
}
