package com.spring.aichat.external;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FalGptEmotionEditClientTest {
    @Test void observedEditSchemaAndAllowedQualityArePreserved() {
        var input = FalGptEmotionEditClient.input("prompt", "https://assets.example/base.png", "low");
        assertThat(input).containsEntry("quality", "low").containsEntry("num_images", 1).containsEntry("background", "opaque")
            .doesNotContainKeys("seed", "negative_prompt", "strength");
        assertThatThrownBy(() -> FalGptEmotionEditClient.input("p", "u", "medium")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void queueReceiptCannotRedirectApiKeyToAnotherHost() {
        assertThat(FalGptEmotionEditClient.checkedQueueUrl("https://queue.fal.run/openai/requests/123/status").getHost()).isEqualTo("queue.fal.run");
        assertThatThrownBy(() -> FalGptEmotionEditClient.checkedQueueUrl("https://queue.fal.run.evil.example/status")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FalGptEmotionEditClient.checkedQueueUrl("http://queue.fal.run/status")).isInstanceOf(IllegalArgumentException.class);
    }
}
