package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.ClaudeConsoleProperties;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.external.ClaudeChatStreamClient.Attempt;
import com.spring.aichat.external.ClaudeChatStreamClient.Converted;
import com.spring.aichat.service.util.LlmOutputParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeChatStreamClientTest {

    // ── 메시지 변환 ──

    @Test
    void v1Shape_leadingAndTrailingSystemGoToSystem_midSystemFoldsIntoUser() {
        Converted c = ClaudeChatStreamClient.convert(List.of(
            OpenAiMessage.systemCached("STATIC", Map.of("type", "ephemeral")),
            OpenAiMessage.user("안녕"),
            OpenAiMessage.assistant("어서 와"),
            OpenAiMessage.system("[NARRATION] 비가 내린다"),
            OpenAiMessage.user("우산 있어?"),
            OpenAiMessage.system("DYNAMIC"),
            OpenAiMessage.system("OUTPUT_FORMAT")));

        assertThat(c.system()).containsExactly("STATIC", "DYNAMIC", "OUTPUT_FORMAT");
        assertThat(c.cacheFirstSystem()).isTrue();
        assertThat(c.turns()).extracting("role").containsExactly("user", "assistant", "user");
        assertThat(c.turns().get(2).content()).isEqualTo(ClaudeChatStreamClient.SYSTEM_NOTE_OPEN
            + "[NARRATION] 비가 내린다" + ClaudeChatStreamClient.SYSTEM_NOTE_CLOSE + "\n\n우산 있어?");
    }

    @Test
    void trailingSystemStaysInSystem_andSyntheticUserCloses_whenHistoryEndsWithAssistant() {
        Converted c = ClaudeChatStreamClient.convert(List.of(
            OpenAiMessage.system("STATIC"),
            OpenAiMessage.system("DYNAMIC"),
            OpenAiMessage.user("가자"),
            OpenAiMessage.assistant("좋아"),
            OpenAiMessage.system("[ACTION: 장소 이동]")));

        assertThat(c.system()).containsExactly("STATIC", "DYNAMIC", "[ACTION: 장소 이동]");
        assertThat(c.turns()).extracting("role").containsExactly("user", "assistant", "user");
        assertThat(c.turns().get(2).content()).isEqualTo("(계속)");
    }

    @Test
    void openingWithNoHistory_stillProducesSingleUserTurn() {
        Converted onlySystem = ClaudeChatStreamClient.convert(List.of(
            OpenAiMessage.system("STATIC"), OpenAiMessage.system("DYNAMIC")));
        assertThat(onlySystem.system()).containsExactly("STATIC", "DYNAMIC");
        assertThat(onlySystem.turns()).extracting("role").containsExactly("user");

        Converted assistantFirst = ClaudeChatStreamClient.convert(List.of(
            OpenAiMessage.system("STATIC"), OpenAiMessage.assistant("인트로")));
        assertThat(assistantFirst.turns()).extracting("role").containsExactly("user", "assistant", "user");
    }

    @Test
    void consecutiveSameRoleMerges_andBlankIsDropped() {
        Converted c = ClaudeChatStreamClient.convert(List.of(
            OpenAiMessage.user("a"), OpenAiMessage.user("b"), OpenAiMessage.assistant(" "),
            OpenAiMessage.user("c")));
        assertThat(c.system()).isEmpty();
        assertThat(c.cacheFirstSystem()).isFalse();
        assertThat(c.turns()).hasSize(1);
        assertThat(c.turns().get(0).content()).isEqualTo("a\n\nb\n\nc");
    }

    // ── 응답 유효성 ──

    @Test
    void onlyJsonObjectWithScenesCountsAsUsable() {
        assertThat(ClaudeChatStreamClient.isChatJson("{\"reasoning\":\"x\",\"scenes\":[{\"dialogue\":\"hi\"}]}")).isTrue();
        assertThat(ClaudeChatStreamClient.isChatJson("```json\n{\"scenes\":[]}\n```")).isTrue();
        assertThat(ClaudeChatStreamClient.isChatJson("죄송하지만 이 대화는 이어갈 수 없습니다.")).isFalse();
        assertThat(ClaudeChatStreamClient.isChatJson("{\"reasoning\":\"x\",\"scenes\":[{\"dialogue\":\"잘린")).isFalse();
        assertThat(ClaudeChatStreamClient.isChatJson("{\"answer\":\"no scenes\"}")).isFalse();
    }

    // ── 크레딧 소진 판별 ──

    @Test
    void creditExhaustionDetection() {
        assertThat(ClaudeChatStreamClient.isCreditExhausted(400, "invalid_request_error",
            "Your credit balance is too low to access the Anthropic API.")).isTrue();
        assertThat(ClaudeChatStreamClient.isCreditExhausted(402, "billing_error", "x")).isTrue();
        assertThat(ClaudeChatStreamClient.isCreditExhausted(400, "invalid_request_error", "max_tokens too large")).isFalse();
        assertThat(ClaudeChatStreamClient.isCreditExhausted(429, "rate_limit_error", "slow down")).isFalse();
    }

    // ── 비활성·폴백 ──

    @Test
    void noKey_orSecretMode_skipsWithoutCalling() {
        ClaudeChatStreamClient off = new ClaudeChatStreamClient(props(""));
        assertThat(off.tryStream(false, false, sample(), 512, null, null).succeeded()).isFalse();

        ClaudeChatStreamClient on = new ClaudeChatStreamClient(props("sk-ant-not-a-real-key"));
        long t0 = System.currentTimeMillis();
        assertThat(on.tryStream(false, true, sample(), 512, null, null).succeeded()).isFalse();
        assertThat(System.currentTimeMillis() - t0).isLessThan(200);   // 네트워크를 타지 않았다
    }

    /** 실 API: 틀린 키는 던지지 않고 실패를 돌려주며, 쿨다운 동안 재시도하지 않는다. */
    @Test
    @EnabledIfEnvironmentVariable(named = "CLAUDE_LIVE_TEST", matches = "true")
    void live_badKey_failsSoftAndCoolsDown() {
        ClaudeChatStreamClient c = new ClaudeChatStreamClient(props("sk-ant-api03-invalid"));
        assertThat(c.tryStream(false, false, sample(), 512, null, null).succeeded()).isFalse();
        long t0 = System.currentTimeMillis();
        assertThat(c.tryStream(false, false, sample(), 512, null, null).succeeded()).isFalse();
        assertThat(System.currentTimeMillis() - t0).isLessThan(200);
    }

    /** 실 API: 일반(Haiku)·부스트(Sonnet) 모두 파싱 가능한 JSON과 first_scene을 낸다. */
    @Test
    @EnabledIfEnvironmentVariable(named = "CLAUDE_LIVE_TEST", matches = "true")
    void live_bothModels_streamParsableJson() throws Exception {
        ClaudeChatStreamClient c = new ClaudeChatStreamClient(props(System.getenv("CLAUDE_API_KEY")));
        ObjectMapper om = new ObjectMapper();
        for (boolean boost : new boolean[]{false, true}) {
            List<String> firstScenes = new ArrayList<>();
            Attempt a = c.tryStream(boost, false, sample(), 2048, firstScenes::add, null);
            assertThat(a.succeeded()).as("boost=%s", boost).isTrue();
            System.out.printf("[LIVE] boost=%s ttft=%dms ttfs=%dms raw=%s%n",
                boost, a.result().ttft(), a.result().ttfs(), a.result().fullResponse());
            JsonNode root = om.readTree(LlmOutputParser.extractJson(a.result().fullResponse()));
            assertThat(root.path("scenes").isArray()).isTrue();
            assertThat(firstScenes).hasSize(1);
            assertThat(om.readTree(firstScenes.get(0)).has("dialogue")).isTrue();
        }
    }

    private static ClaudeConsoleProperties props(String key) {
        return new ClaudeConsoleProperties(key, null, null, null, null, 15000L, null, null);
    }

    private static List<OpenAiMessage> sample() {
        return List.of(
            OpenAiMessage.systemCached("너는 카페 점원 '하나'다. 손님(유저)과 한국어로 대화한다.", Map.of("type", "ephemeral")),
            OpenAiMessage.assistant("어서 오세요!"),
            OpenAiMessage.user("아메리카노 한 잔 주세요."),
            OpenAiMessage.system("현재 호감도: 12. 시간대: 오후."),
            OpenAiMessage.system("""
                반드시 아래 JSON 형식으로만 답하라.
                {"reasoning":"...","scenes":[{"speaker":"하나","narration":"...","dialogue":"...","emotion":"JOY"}]}
                scenes는 1~2개."""));
    }
}
