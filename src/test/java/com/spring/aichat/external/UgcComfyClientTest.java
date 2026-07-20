package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.UgcPipelineProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [UGC v1] worker 5.x 응답 파싱 + /run 페이로드 가드 계약 테스트 (HTTP 미개입).
 */
class UgcComfyClientTest {

    private final ObjectMapper om = new ObjectMapper();
    private UgcComfyClient client;

    @BeforeEach
    void setUp() {
        client = new UgcComfyClient(new UgcPipelineProperties(null, null, null, null, null), om);
    }

    @Test
    @DisplayName("worker 5.x COMPLETED: output.images[]의 s3_url presigned를 파싱한다")
    void parsesCompletedWithS3UrlImages() throws Exception {
        JsonNode resp = om.readTree("""
            { "id": "sync-abc", "status": "COMPLETED",
              "output": { "images": [
                { "filename": "job_7_golden_00001_.png", "type": "s3_url", "data": "https://bucket.s3.ap-northeast-2.amazonaws.com/sync-abc/job_7_golden_00001_.png?X-Amz-Sig=x" },
                { "filename": "job_7_golden_00002_.png", "type": "s3_url", "data": "https://bucket.s3.ap-northeast-2.amazonaws.com/sync-abc/job_7_golden_00002_.png?X-Amz-Sig=y" }
              ] },
              "delayTime": 123, "executionTime": 4567 }
            """);

        UgcComfyClient.JobStatus status = client.parseStatusPayload("fallback", resp);

        assertThat(status.jobId()).isEqualTo("sync-abc");
        assertThat(status.completed()).isTrue();
        assertThat(status.failed()).isFalse();
        assertThat(status.images()).hasSize(2);
        assertThat(status.images().get(0).filename()).isEqualTo("job_7_golden_00001_.png");
        assertThat(status.images().get(0).type()).isEqualTo("s3_url");
        assertThat(status.images().get(0).data()).startsWith("https://");
        assertThat(status.delayTime()).isEqualTo(123L);
        assertThat(status.executionTime()).isEqualTo(4567L);
        assertThat(status.error()).isNull();
    }

    @Test
    @DisplayName("output.errors[] 존재 시 error로 합쳐 보고한다")
    void parsesErrors() throws Exception {
        JsonNode resp = om.readTree("""
            { "id": "j1", "status": "COMPLETED",
              "output": { "images": [], "errors": ["value not in list: ckpt_name", "node 22:14 failed"] } }
            """);

        UgcComfyClient.JobStatus status = client.parseStatusPayload(null, resp);

        assertThat(status.error()).contains("ckpt_name").contains("22:14");
        assertThat(status.images()).isEmpty();
    }

    @Test
    @DisplayName("FAILED 상태는 failed()=true이며 에러 문구를 보존한다")
    void parsesFailedStatus() throws Exception {
        JsonNode resp = om.readTree("""
            { "id": "j2", "status": "FAILED", "output": { "error": "OOM" } }
            """);

        UgcComfyClient.JobStatus status = client.parseStatusPayload(null, resp);

        assertThat(status.failed()).isTrue();
        assertThat(status.completed()).isFalse();
        assertThat(status.error()).isEqualTo("OOM");
    }

    @Test
    @DisplayName("IN_QUEUE/IN_PROGRESS는 inFlight로 판정한다")
    void parsesInFlight() throws Exception {
        for (String s : List.of("IN_QUEUE", "IN_PROGRESS")) {
            JsonNode resp = om.readTree("{ \"id\": \"j\", \"status\": \"" + s + "\" }");
            assertThat(client.parseStatusPayload(null, resp).inFlight()).isTrue();
        }
    }

    @Test
    @DisplayName("id 누락 시 fallback jobId를 쓴다 (webhook 페이로드 방어)")
    void usesFallbackJobId() throws Exception {
        JsonNode resp = om.readTree("{ \"status\": \"IN_PROGRESS\" }");
        assertThat(client.parseStatusPayload("known-id", resp).jobId()).isEqualTo("known-id");
    }

    @Test
    @DisplayName("/run 페이로드 10MB 초과는 제출 전 차단된다")
    void guardsPayloadSize() {
        ObjectNode body = om.createObjectNode();
        // base64 12MB 상당의 더미 — 이미지 1장이 한도를 넘는 상황
        body.put("blob", "A".repeat(12 * 1024 * 1024));

        assertThatThrownBy(() -> client.guardPayloadSize(body,
            List.of(new UgcComfyClient.InputImage("a.png", "..."))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("10MB");
    }

    @Test
    @DisplayName("정상 크기 페이로드는 통과한다")
    void allowsNormalPayload() {
        ObjectNode body = om.createObjectNode();
        body.put("blob", "A".repeat(1024 * 1024)); // 1MB
        client.guardPayloadSize(body, List.of());  // no exception
    }
}
