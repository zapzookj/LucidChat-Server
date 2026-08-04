package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.UgcPipelineProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * [2026-08-04 남캐 PoC] Male_Type LoRA 강도 격리 매트릭스 — 디오라마 §8-6 방법론의 남캐판.
 *
 * <p>클래스명이 *Test가 아니라 CI(--tests "*Test")에서 자동 제외된다. 실행:
 * <pre>
 *   $env:UGC_RUNPOD_API_KEY="..."; $env:UGC_RUNPOD_ENDPOINT_ID="..."
 *   .\gradlew.bat test --tests "com.spring.aichat.service.ugc.MaleIllustMatrixPoc"
 * </pre>
 * env 미설정이면 스킵. 산출: {@code poc/out/*.png} (리포 루트 기준 — .gitignore 대상).
 *
 * <p>Phase 1 설계 — 변수는 LoRA 강도 하나만:
 * 고정 프롬프트(잡 13 '서이한' Stage0 실산출 태그 — 브리프가 이미 bishounen 계열을 뽑은 상태)
 * × LoRA {없음, 0.5, 0.7, 0.9} × seed 2종 = 8장. 프롬프트가 상수이므로 결과 차이는 전부
 * 스타일 층(LoRA) 기여로 귀속된다 — 'LoRA가 미형화를 돕는가 극화체로 당기는가'를 확정.
 */
class MaleIllustMatrixPoc {

    /** 잡 13(서이한) Stage0 실산출 — appearance + persona + scene (프로덕션 조립 순서 동일). */
    private static final String JOB13_TAGS = String.join(", ",
        // appearance
        "adult", "bishounen", "handsome", "black hair", "soft hair", "medium hair", "layered hair",
        "straight hair", "bangs", "long bangs", "hair between eyes", "partially covered eyes",
        "sidelocks", "brown eyes", "tareme", "narrow eyes", "sharp eyes", "thick eyebrows",
        "straight eyebrows", "eyelashes", "pale skin", "slender", "slim", "narrow waist",
        "narrow hips", "broad shoulders", "long legs", "tall male", "toned",
        "collared shirt", "white shirt", "dress shirt", "black jacket", "blazer", "open jacket",
        "necktie", "loosened necktie", "black pants", "belt", "black belt", "wristwatch",
        "earrings", "single earring", "ear piercing", "silver earrings", "necklace",
        "chain necklace", "black footwear", "dress shoes", "rolled-up sleeves", "shirt tucked in",
        "in-ear monitor",
        // persona
        "perfectionist", "charismatic", "disciplined", "aloof", "secretly lonely", "gentle",
        // scene (황금샷 연출)
        "recording studio", "vocal booth", "studio microphone", "headphones", "night",
        "city lights", "window", "dim lighting", "rim lighting", "cowboy shot",
        "three-quarter view", "depth of field");

    private static final String POSITIVE =
        "masterpiece, best quality, newest, absurdres, 1boy, male focus, solo, " + JOB13_TAGS;

    private static final double[] STRENGTHS = {0.0, 0.5, 0.7, 0.9};
    private static final long[] SEEDS = {101_101_101L, 202_202_202L};
    private static final long DETAILER_SEED = 777_777_777L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).build();

    @Test
    void runMatrix() throws Exception {
        String apiKey = System.getenv("UGC_RUNPOD_API_KEY");
        String endpointId = System.getenv("UGC_RUNPOD_ENDPOINT_ID");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank()
            && endpointId != null && !endpointId.isBlank(), "UGC_RUNPOD_* env 미설정 — PoC 스킵");

        Path outDir = Path.of("poc", "out");
        Files.createDirectories(outDir);

        record Combo(String name, double strength, long seed) {}
        List<Combo> combos = new ArrayList<>();
        for (double s : STRENGTHS) {
            for (long seed : SEEDS) {
                combos.add(new Combo("lora%s_seed%d".formatted(
                    s == 0.0 ? "OFF" : String.valueOf(s).replace('.', 'p'), seed % 1000), s, seed));
            }
        }

        // ── 제출 (전 조합) ──
        record Submitted(Combo combo, String jobId) {}
        List<Submitted> submitted = new ArrayList<>();
        for (Combo c : combos) {
            UgcPipelineProperties props = new UgcPipelineProperties(null, null, null, null,
                new UgcPipelineProperties.Generation(1, null, null,
                    c.strength() == 0.0 ? null : c.strength(), null), null, null, null);
            UgcWorkflowFactory factory = new UgcWorkflowFactory(mapper, props);
            factory.loadTemplates();
            // strength 0 = male=false 빌드 → 그래프에 LoRA 미주입(프롬프트는 동일) — 순수 격리
            ObjectNode wf = factory.buildGoldenShot(POSITIVE, "poc_" + c.name(),
                c.seed(), DETAILER_SEED, c.strength() > 0.0);

            ObjectNode input = mapper.createObjectNode();
            input.set("workflow", wf);
            ObjectNode body = mapper.createObjectNode();
            body.set("input", input);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.runpod.ai/v2/" + endpointId + "/run"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("제출 실패 " + c.name() + ": HTTP "
                    + res.statusCode() + " " + res.body());
            }
            String jobId = mapper.readTree(res.body()).path("id").asText();
            submitted.add(new Submitted(c, jobId));
            System.out.println("[POC] 제출 " + c.name() + " → " + jobId);
        }

        // ── 폴링 + 다운로드 ──
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(20).toMillis();
        List<Submitted> pending = new ArrayList<>(submitted);
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5000);
            for (var it = pending.iterator(); it.hasNext(); ) {
                Submitted s = it.next();
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.runpod.ai/v2/" + endpointId + "/status/" + s.jobId()))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
                JsonNode status = mapper.readTree(
                    http.send(req, HttpResponse.BodyHandlers.ofString()).body());
                String st = status.path("status").asText();
                if ("COMPLETED".equals(st)) {
                    JsonNode img = status.path("output").path("images").path(0);
                    Path out = outDir.resolve(s.combo().name() + ".png");
                    if ("base64".equals(img.path("type").asText())) {
                        Files.write(out, java.util.Base64.getDecoder().decode(img.path("data").asText()));
                    } else {
                        try (var in = URI.create(img.path("data").asText()).toURL().openStream()) {
                            Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    System.out.println("[POC] ✅ " + s.combo().name() + " → " + out);
                    it.remove();
                } else if ("FAILED".equals(st) || "CANCELLED".equals(st)) {
                    System.out.println("[POC] ❌ " + s.combo().name() + " " + st + ": " + status.path("output"));
                    it.remove();
                }
            }
        }
        if (!pending.isEmpty()) {
            System.out.println("[POC] ⏱ 타임아웃 미완 " + pending.size() + "건");
        }
        System.out.println("[POC] 완료 — poc/out/ 에서 8장 비교 (loraOFF/0.5/0.7/0.9 × seed 2)");
    }
}
