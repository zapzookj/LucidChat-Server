package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.UgcPipelineProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Frozen recipe from the user-selected paid 1-A sample, with output prefix anonymized. */
class ApprovedIllustrationRecipeTest {
    @Test void reproducesLegacyApprovedWholeWorkflow() throws Exception {
        var mapper = new ObjectMapper();
        var resources = getClass().getClassLoader();
        try (var tagsFile = resources.getResourceAsStream("ugc/approved-1a-tags.json");
             var workflowFile = resources.getResourceAsStream("ugc/approved-1a-workflow.json")) {
            var tags = mapper.readValue(tagsFile,com.spring.aichat.dto.ugc.StructuredConcept.class);
            var props = new UgcPipelineProperties(null,null,null,null,
                new UgcPipelineProperties.Generation(1,null,null,null,null),null,null,null);
            var factory = new UgcWorkflowFactory(mapper,props);
            factory.loadTemplates();
            var positive = new UgcPromptAssembler(props).goldenShotPositive(tags.appearanceTags(),
                tags.personaTags(),tags.sceneTags(),false);
            assertThat(mapper.readTree(factory.buildGoldenShot(positive,"approved_1a_test",2026100701L,2026110701L,false).toString()))
                .isEqualTo(mapper.readTree(workflowFile));
        }
    }
}
