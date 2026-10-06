package com.spring.aichat.external;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.exception.ExternalApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class OpenRouterClientPrivacyTest {
    final String privateMarker = "PRIVATE_DIALOGUE_AND_SCENE_8472";
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger logger = (Logger) LoggerFactory.getLogger(OpenRouterClient.class);
    HttpServer server;
    OpenRouterClient client;

    @BeforeEach void setup() throws Exception {
        logs.start(); logger.addAppender(logs);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var props = new OpenAiProperties("fake-test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
            "model", "model", "model", "http://localhost", "Test");
        client = new OpenRouterClient(new ObjectMapper(), props, RestClient.create());
    }
    @AfterEach void cleanup() { server.stop(0); logger.detachAppender(logs); logs.stop(); }

    void serve(int status, String body) {
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }

    void assertPrivateFailureIsSanitized() {
        assertThatThrownBy(() -> client.completeJson("model", "system", privateMarker, 100, 0.3))
            .isInstanceOf(ExternalApiException.class).hasMessageNotContaining(privateMarker).hasNoCause();
        assertThat(logs.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(privateMarker);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test void providerErrorEchoCannotEnterLogs() {
        serve(400, "{\"error\":\"" + privateMarker + "\"}");
        assertPrivateFailureIsSanitized();
        assertThat(logs.list.get(0).getFormattedMessage()).contains("HTTP 400");
    }
    @Test void malformedSuccessBodyCannotEnterExceptionOrLogs() {
        serve(200, "{\"" + privateMarker + "\": broken-json");
        assertPrivateFailureIsSanitized();
        assertThat(logs.list.get(0).getFormattedMessage()).contains("type=");
    }
    @Test void validCompletionStillReturnsItsContent() {
        serve(200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}");
        assertThat(client.completeJson("model", "system", privateMarker, 100, 0.3)).isEqualTo("{\"ok\":true}");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(privateMarker));
    }
}
