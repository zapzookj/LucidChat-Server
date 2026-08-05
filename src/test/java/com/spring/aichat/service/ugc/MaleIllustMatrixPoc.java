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
 * [2026-08-04 남캐 PoC] 서이한 태그 매트릭스 — 디오라마 §8-6 방법론의 남캐판.
 *
 * <p>클래스명이 *Test가 아니라 CI(--tests "*Test")에서 자동 제외된다. 실행:
 * <pre>
 *   $env:UGC_RUNPOD_API_KEY="..."; $env:UGC_RUNPOD_ENDPOINT_ID="..."
 *   .\gradlew.bat test --tests "com.spring.aichat.service.ugc.MaleIllustMatrixPoc"
 * </pre>
 * env 미설정이면 스킵. 산출: {@code poc/out/*.png} (리포 루트 기준 — .gitignore 대상).
 *
 * <p>Phase 1(fd98398 리비전): LoRA 강도 격리 — OFF=여성 렌더(앵커 필수 확정), 0.9 확정.
 * <p>Phase 2(c02e862 리비전): 표정·시선·조명 — 생기 부재 진단, 대표 컷 연출 지시로 브리프 반영.
 *
 * <p>Phase 3 설계 (2026-08-04, 종원 외형 불만 대응 — 헤어 촌스러움·회보라 피부·죽은 눈):
 * danbooru 태그 실측(태그 덤프 + safebooru tags.json 교차)으로 기존 프롬프트의 유령 태그를 확인 —
 * soft hair·layered hair(0건), bangs(deprecated), straight hair(형태 억제), narrow/sharp eyes(0건),
 * bright/detailed eyes·eye highlights(0건), warm/rim lighting(0건), dim lighting·recording studio·
 * vocal booth·studio microphone(미학습), three-quarter view(0건), slight smile(비실존 — light smile이 정본).
 * 형태 신호가 "medium hair+straight hair"뿐이라 여성 bob 분포로 수렴한 것이 밋밋 단발의 직접 원인,
 * pale skin+야경 청보라 통계가 송장톤의 원인, brown eyes 다크초콜릿 분포+저조도가 검은 눈의 원인.
 *
 * <p>세트(변인 격리, 비교 기준 = Phase 2 setB_vital):
 * D=헤어만 교체 / E=생기(피부·눈·조명·씬 앵커)만 교체 / F=D+E 통합 / G=F+upper body(화보 프레이밍) /
 * H=F+네거티브 팩(청보라 상쇄, maleNegative 노브 경유 — 프로덕션 주입 경로 그대로) — 각 × seed 2 = 10장.
 */
class MaleIllustMatrixPoc {

    // ── 잡 13(서이한) Stage0 실산출의 블록 분해 — 문제 축만 교체, 나머지 동결 ──
    private static final String HEAD = "adult, bishounen, handsome";

    /** setB 상태의 헤어(유령 태그 포함 — E의 격리 대조군). */
    private static final String HAIR_OLD = String.join(", ",
        "black hair", "soft hair", "medium hair", "layered hair", "straight hair", "bangs",
        "long bangs", "hair between eyes", "sidelocks");

    /**
     * 커튼뱅·가르마 K-pop 정석 — 전 태그 danbooru 실존 검증(parted bangs 305k·curtained hair 47k·
     * wavy hair 146k). curtained hair는 parted bangs와만 정합(swept/crossed 혼용 금지),
     * '앞머리가 눈을 살짝 덮는' 컨셉은 long bangs+hair between eyes로 유지.
     */
    private static final String HAIR_NEW = String.join(", ",
        "black hair", "medium hair", "parted bangs", "curtained hair", "long bangs",
        "hair between eyes", "sidelocks", "wavy hair");

    private static final String EYES_OLD = "brown eyes, tareme, straight eyebrows, eyelashes";

    /**
     * 갈색 유지 + gradient eyes(12.8k)로 저조도 발색 보정. tareme는 1boy:1girl=13:87 여캐 편향이라
     * 대표컷에서 제외(감정 컷에서 재검토), sparkling eyes(18k)는 유일한 실존 생기 태그.
     */
    private static final String EYES_NEW = "brown eyes, gradient eyes, sparkling eyes, eyelashes";

    private static final String SKIN_OLD = "pale skin";
    /** pale skin 제거=Illustrious 기본 라이트스킨 복귀('창백 미인' 인상 유지) + light blush(121k) 혈색. */
    private static final String SKIN_NEW = "light blush";

    private static final String BODY = String.join(", ",
        "slender", "slim", "narrow waist", "narrow hips", "broad shoulders", "long legs",
        "tall male", "toned");

    private static final String CLOTHING = String.join(", ",
        "collared shirt", "white shirt", "dress shirt", "black jacket", "blazer", "open jacket",
        "necktie", "loosened necktie", "black pants", "belt", "black belt", "wristwatch",
        "earrings", "single earring", "ear piercing", "silver earrings", "necklace",
        "chain necklace", "black footwear", "dress shoes", "rolled-up sleeves", "shirt tucked in",
        "in-ear monitor");

    private static final String PERSONA =
        "perfectionist, charismatic, disciplined, aloof, secretly lonely, gentle";

    /** setB 상태의 씬(유령 조명 포함 — D의 격리 대조군). */
    private static final String SCENE_OLD = String.join(", ",
        "recording studio", "vocal booth", "studio microphone", "headphones", "night",
        "city lights", "window", "warm lighting", "soft rim lighting", "cowboy shot",
        "three-quarter view", "depth of field");

    /**
     * 심야 녹음실 무드 유지 + 실존 태그 재앵커: recording studio(193)·vocal booth·studio microphone(421)은
     * 미학습이라 indoors(360k)+microphone(51k)으로 교체, 형용사 조명 대신 물리 광원 lamp(23k)가
     * 텅스텐 웜 앰비언트를 만들고 backlighting(45k)이 창밖 야경 역광으로 윤곽 분리(sidelighting과 혼용 금지).
     */
    private static String sceneNew(String framing) {
        return String.join(", ",
            "indoors", "microphone", "headphones", "night", "city lights", "window",
            "lamp", "backlighting", "light particles", framing, "depth of field");
    }

    /** setB 상태의 표정 꼬리(유령 태그 포함 — 격리 대조군). */
    private static final String EXPR_OLD =
        "looking at viewer, slight smile, bright eyes, detailed eyes, sparkling eyes, eye highlights";
    /** slight smile은 비실존 — light smile(107k)이 정본. 생기 태그는 EYES_NEW의 sparkling eyes가 담당. */
    private static final String EXPR_NEW = "looking at viewer, light smile";

    /** 청보라 통계 상쇄 네거티브 팩(H 전용) — 전 태그 실존(blue theme 27k·greyscale 501k·monochrome 631k). */
    private static final String NEG_PACK = "blue theme, purple theme, greyscale, monochrome, pale skin";

    private static String assemble(String hair, String eyes, String skin, String scene, String expr) {
        return "masterpiece, best quality, newest, absurdres, 1boy, male focus, solo, "
            + String.join(", ", HEAD, hair, eyes, skin, BODY, CLOTHING, PERSONA, scene, expr);
    }

    // ━━━━━━ Phase 4 (2026-08-04 밤): 헤어 정형화 + LoRA 트리거 매트릭스 ━━━━━━
    // 리서치 확정: Male_Type LoRA(civitai 1782159, 마화 미남 스타일)의 트리거 "maleT"가
    // 프롬프트에 전무 — strength_clip 1.0인데 발동 토큰이 없어 LoRA 반쪽 사용 상태.
    // 한국식 헤어 전용 태그(comma hair·two block·dandy cut)는 danbooru 비실존 —
    // 근접 핸들: choppy bangs(남캐 42%·시스루 댄디), parted bangs/hair(가르마).
    // curtained hair는 위키 정의부터 90s 아치 커튼(촌스러움 원인 후보) → P5 대조군.
    /** 잡 15 실산출 기반 고정 몸통 — 헤어 블록만 변인. */
    private static final String P4_FIXED = String.join(", ",
        "adult", "tall male", "slender", "slim", "toned", "broad shoulders", "narrow waist",
        "bishounen", "brown eyes", "gradient eyes", "thick eyebrows", "light blush",
        "collarbone", "adam's apple",
        "black jacket", "track jacket", "open jacket", "long sleeves", "sleeves rolled up",
        "white shirt", "t-shirt", "crew neck", "black pants", "skinny pants", "black belt",
        "necklace", "chain necklace", "earrings", "hoop earrings", "wristwatch",
        "perfectionist", "charismatic", "disciplined", "aloof", "gentle",
        "indoors", "recording studio", "microphone", "pop filter", "headphones around neck",
        "lamp", "sidelighting", "night", "depth of field", "blurry background",
        "looking at viewer", "light smile", "cowboy shot", "standing", "hand in pocket");

    private static String p4(String triggerToken, String hair) {
        return "masterpiece, best quality, newest, absurdres, "
            + (triggerToken == null ? "" : triggerToken + ", ")
            + "1boy, male focus, solo, " + hair + ", " + P4_FIXED;
    }

    private static final String HAIR_MINIMAL = "black hair, short hair";
    private static final String HAIR_CHOPPY = "black hair, short hair, choppy bangs, messy hair";
    private static final String HAIR_PARTED = "black hair, short hair, parted bangs, parted hair, swept bangs";

    // ━━━━━━ Phase 5 (2026-08-04 심야): 품질×한국풍 스위트스팟 ━━━━━━
    // Phase 4 판정(종원): P2(maleT 풀)가 헤어는 한국풍인데 전반 품질은 P0(트리거 무)가 우위 —
    // 원인 = 트리거로 LoRA 유효 영향력 급증(0.9는 트리거 없던 시절 확정값 → 과적용).
    // 다이얼 3종 탐색: 트리거 어텐션 (maleT:0.6) / strength 0.7 재탐 / 무트리거+검증 헤어 핸들.
    private record TagSetV2(String name, String positive, String negative, double strength) {}

    // ━━━━━━ Phase 7 (2026-08-05): 카일 vs 아셀 대조군 — 표정 강제·동질화 실측 ━━━━━━
    // 종원 관찰 2건: ① 냉철(카일)·까칠(아셀) 컨셉도 전부 웃는 원화만 나옴(브리프의 표정 강제
    // 의심) ② 복장·헤어는 다양한데 얼굴·체형이 비슷비슷(브리프 ④ 동일 체형 스택 강제 +
    // LoRA 아키타입 수렴 의심). 축: 표정(미소/냉철/까칠) × 체형(표준 앵커/컨셉 개방) × 강도(0.9/0.7).
    // k0 vs a0(동일 체형 스택·미소)가 동질화 증명 대조군.
    private static final String SCENE_P7 = String.join(", ",
        "indoors", "window", "sunlight", "upper body", "standing",
        "looking at viewer", "depth of field");
    private static final String BODY_STD = // 현행 브리프 ④ 앵커 스택 재현
        "adult, tall male, slender, toned, broad shoulders, narrow waist, bishounen, light blush";
    private static final String BODY_KNIGHT = // 카일 컨셉 개방: 단련된 검사
        "adult, tall male, muscular, broad shoulders, narrow waist, bishounen, light blush";
    private static final String BODY_WAIF = // 아셀 컨셉 개방: 마른 미소년
        "adult, slender, narrow shoulders, bishounen, light blush";
    private static final String KYLE = String.join(", ",
        "grey hair", "short hair", "hair slicked back", "hair between eyes",
        "grey eyes", "gradient eyes",
        "military uniform", "black jacket", "high collar", "white gloves", "belt");
    private static final String ASEL = String.join(", ",
        "blonde hair", "short hair", "messy hair", "hair between eyes",
        "red eyes", "gradient eyes",
        "school uniform", "black jacket", "white shirt", "necktie");
    private static final String COLD_KYLE = "expressionless, serious";
    private static final String COLD_ASEL = "smug, half-closed eyes";

    private static String p7(String identity, String body, String expr) {
        return "masterpiece, best quality, newest, absurdres, 1boy, male focus, solo, "
            + identity + ", " + body + ", " + SCENE_P7 + ", " + expr;
    }

    private static final TagSetV2[] TAG_SETS = {
        new TagSetV2("k0_kyle_smile", p7(KYLE, BODY_STD, "light smile"), NEG_PACK, 0.9),
        new TagSetV2("k1_kyle_cold", p7(KYLE, BODY_STD, COLD_KYLE), NEG_PACK, 0.9),
        new TagSetV2("k2_kyle_knight", p7(KYLE, BODY_KNIGHT, COLD_KYLE), NEG_PACK, 0.9),
        new TagSetV2("k3_kyle_cold_s07", p7(KYLE, BODY_STD, COLD_KYLE), NEG_PACK, 0.7),
        new TagSetV2("a0_asel_smile", p7(ASEL, BODY_STD, "light smile"), NEG_PACK, 0.9),
        new TagSetV2("a1_asel_smug", p7(ASEL, BODY_STD, COLD_ASEL), NEG_PACK, 0.9),
        new TagSetV2("a2_asel_waif", p7(ASEL, BODY_WAIF, COLD_ASEL), NEG_PACK, 0.9),
        new TagSetV2("a3_asel_smug_s07", p7(ASEL, BODY_STD, COLD_ASEL), NEG_PACK, 0.7),
    };
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

        record Combo(String name, String positive, String negative, double strength, long seed) {}
        List<Combo> combos = new ArrayList<>();
        for (TagSetV2 set : TAG_SETS) {
            for (long seed : SEEDS) {
                combos.add(new Combo(set.name() + "_seed" + (seed % 1000),
                    set.positive(), set.negative(), set.strength(), seed));
            }
        }

        // ── 제출 (전 조합) ──
        record Submitted(Combo combo, String jobId) {}
        List<Submitted> submitted = new ArrayList<>();
        for (Combo c : combos) {
            // maleNegative 노브로 네거티브 팩 주입 — 프로덕션 appendMaleNegative 경로 그대로
            UgcPipelineProperties props = new UgcPipelineProperties(null, null, null, null,
                new UgcPipelineProperties.Generation(1, null, null,
                    c.strength() == 0.0 ? null : c.strength(), c.negative()), null, null, null);
            UgcWorkflowFactory factory = new UgcWorkflowFactory(mapper, props);
            factory.loadTemplates();
            // strength 0 = male=false 빌드 → 그래프에 LoRA 미주입(프롬프트는 동일) — 순수 격리
            ObjectNode wf = factory.buildGoldenShot(c.positive(), "poc_" + c.name(),
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
        System.out.println("[POC] 완료 — poc/out/ Phase 3 10장 비교 "
            + "(setD헤어/setE생기/setF통합/setG상반신/setH네거 × seed 2, 기준=setB_vital)");
    }
}
