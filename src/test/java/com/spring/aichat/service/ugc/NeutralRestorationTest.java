package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.dto.ugc.FaceDetailTags;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.domain.enums.EmotionTag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;

class NeutralRestorationTest {
    final ObjectMapper mapper = new ObjectMapper();
    StructuredConcept concept(FaceDetailTags face) {
        return new StructuredConcept(List.of("black hair", "sharp amber eyes", "subtle smoky eye makeup", "long eyelashes"),
            List.of("smug grin", "raised eyebrow", "dominant attitude"), null, null, "light gray", null, null, null, null, null, face);
    }
    @Test void keepsExactlyThreeApprovedTagsAndNoMakeupOrMood() {
        assertThat(NeutralFacePrompt.assemble(concept(new FaceDetailTags("sharp amber eyes", "smug"))))
            .isEqualTo("detailed beautiful eyes, sharp amber eyes, smug");
        assertThat(NeutralFacePrompt.assemble(concept(null)))
            .isEqualTo("detailed beautiful eyes, sharp amber eyes, smug");
    }
    @Test void alternateCharactersAndMissingTraitsDoNotGetTheSampleIdentity() {
        var c=new StructuredConcept(List.of("blue eyes"),List.of("gentle smile"),null,null,null,null,null,null,null);
        assertThat(NeutralFacePrompt.assemble(c)).isEqualTo("detailed beautiful eyes, blue eyes, gentle smile");
        c=new StructuredConcept(List.of("silver hair"),List.of("loyal"),null,null,null,null,null,null,null);
        assertThat(NeutralFacePrompt.assemble(c)).isEqualTo("detailed beautiful eyes, same eye color, neutral expression");
    }
    @Test void acceptsTypicalCharacterExpressionsAndHyphenatedEyeColor() {
        for(String expression:List.of("mischievous smile","bashful smile","timid smile","coy smile","sultry gaze","determined expression"))
            assertThat(NeutralFacePrompt.assemble(concept(new FaceDetailTags("light-blue eyes",expression))))
                .isEqualTo("detailed beautiful eyes, light blue eyes, "+expression);
    }
    @Test void malformedOptionalMetadataIsNonFatalAndFallsBack() throws Exception {
        for (String hint : List.of("[]", "42", "\"bad\"", "{\"eye_color\":[],\"expression\":{}}", "null")) {
            var c=mapper.readValue("{\"appearance_tags\":[\"green eyes\"],\"persona_tags\":[\"smile\"],\"face_detail_tags\":"+hint+"}",StructuredConcept.class);
            assertThat(NeutralFacePrompt.assemble(c)).isEqualTo("detailed beautiful eyes, green eyes, smile");
        }
        for(String bad:List.of("amber eyes, eyeliner", "(amber eyes:1.5)", "amber eyes BREAK red eyes", "__amber eyes__", "amber eyes with long eyelashes", "red eye shadow"))
            assertThat(NeutralFacePrompt.assemble(concept(new FaceDetailTags(bad,"smug, heavy makeup"))))
                .isEqualTo("detailed beautiful eyes, sharp amber eyes, smug");
        assertThat(NeutralFacePrompt.assemble(concept(new FaceDetailTags("blue eyes", "smile with heavy eye shadow"))))
            .isEqualTo("detailed beautiful eyes, blue eyes, smug");
    }
    @Test void jsonAndCopyOperationsKeepHintsAndSnapshotReplacementClearsMissingHints() throws Exception {
        var c=concept(new FaceDetailTags("sharp amber eyes","smug"));
        String json=mapper.writeValueAsString(c);
        assertThat(json).contains("\"eye_color\":\"sharp amber eyes\"");
        var copy=mapper.readValue(json,StructuredConcept.class);
        assertThat(copy.faceDetailTags()).isEqualTo(c.faceDetailTags());
        assertThat(c.withIllustrationPrompt(null).faceDetailTags()).isEqualTo(c.faceDetailTags());
        assertThat(c.withEmotionPrompts(Map.of()).faceDetailTags()).isEqualTo(c.faceDetailTags());
        // withAppearanceFrom needs profiles, including for legacy snapshots.
        var profile=mapper.readValue("{\"name\":\"세라\"}",StructuredConcept.CharacterProfile.class);
        var current=new StructuredConcept(c.appearanceTags(),c.personaTags(),null,null,null,profile,null,null,null,null,c.faceDetailTags());
        var old=new StructuredConcept(List.of("blue eyes"),List.of("smile"),null,null,null,profile,null,null,null);
        assertThat(current.withAppearanceFrom(old).faceDetailTags()).isNull();
        assertThat(NeutralFacePrompt.assemble(current.withAppearanceFrom(old))).isEqualTo("detailed beautiful eyes, blue eyes, smile");
    }
    UgcWorkflowFactory factory() {
        var f=new UgcWorkflowFactory(mapper,new UgcPipelineProperties(null,null,null,null,null,null,null,null));
        f.loadTemplates();return f;
    }
    @Test void neutralGraphKeepsGlobalPassAndRoutesSeparateFaceThenHandToSingleOutput() {
        var f=factory();var w=f.buildNeutralRefine("input.png","makeup, long eyelashes, nail polish",NeutralFacePrompt.assemble(concept(null)),"probe",11,22,33,false);
        assertThat(w.path("17").path("inputs").path("positive").get(0).asText()).isEqualTo("21");
        assertThat(w.path("21").path("inputs").path("text").asText()).isEqualTo("detailed beautiful eyes, sharp amber eyes, smug");
        assertThat(w.path("17").path("inputs").path("wildcard").asText()).isEmpty();
        assertThat(w.path("24").path("inputs").path("wildcard").asText()).isEmpty();
        assertThat(w.path("24").path("inputs").path("image").get(0).asText()).isEqualTo("17");
        assertThat(w.path("24").path("inputs").path("positive").get(0).asText()).isEqualTo("23");
        assertThat(w.path("23").path("inputs").path("text").asText()).isEqualTo("detailed hands");
        assertThat(w.path("22").path("inputs").path("model_name").asText()).isEqualTo("bbox/hand_yolov8n.pt");
        assertThat(w.path("9").path("inputs").path("images").get(0).asText()).isEqualTo("24");
        assertThat(w.properties().stream().filter(e->e.getValue().path("class_type").asText().equals("SaveImage")).count()).isEqualTo(1);
        assertThat(w.path("11").path("inputs").path("seed").asLong()).isEqualTo(11);
        assertThat(w.path("17").path("inputs").path("seed").asLong()).isEqualTo(22);
        assertThat(w.path("24").path("inputs").path("seed").asLong()).isEqualTo(33);
        assertThat(w.path("17").path("inputs").path("denoise").asDouble()).isEqualTo(.4);
        assertThat(w.path("24").path("inputs").path("denoise").asDouble()).isEqualTo(.3);
        var second=f.buildNeutralRefine("other.png","p","detailed beautiful eyes, blue eyes, smile","other",false);
        assertThat(second.path("24").path("inputs").path("seed").asLong()).isNotEqualTo(33);
        var legacy=f.buildRefine("in","p","old wildcard","x",false);
        assertThat(legacy.has("24")).isFalse();
        assertThat(legacy.path("17").path("inputs").path("wildcard").asText()).isEqualTo("old wildcard");
    }
    @Test void maleLoraFeedsBothDedicatedEncodersAndDetailers() {
        var w=factory().buildNeutralRefine("in","p",NeutralFacePrompt.assemble(concept(null)),"m",true);
        for (String node:List.of("12","21","23","17","24"))
            assertThat(w.path(node).path("inputs").path("clip").get(0).asText()).isEqualTo("900");
        for (String node:List.of("11","17","24"))
            assertThat(w.path(node).path("inputs").path("model").get(0).asText()).isEqualTo("900");
    }
}
