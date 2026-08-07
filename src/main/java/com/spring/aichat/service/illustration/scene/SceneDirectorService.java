package com.spring.aichat.service.illustration.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.SceneIllustrationProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.util.LlmOutputParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * [2026-07-31 에픽 B] 씬 디렉터 — 유저 수동 요청 시 대화 맥락을 분석해 씬 일러 스펙
 * ({@link AiJsonOutput.SceneIllustrationSpec})을 작성하는 전용 프롬프트 라이터.
 *
 * <p>설계 배경(종원 확정): 인밴드 매턴 방식은 채팅 LLM의 어텐션이 대사 작성과 태그 작성으로
 * 갈라지고, 프롬프트에 실을 수 있는 연출 규약도 압축본뿐이었다. 전용 호출은 디오라마 실측
 * 검증된 L1 규약 전문(docs/09 §A-1)을 온전히 싣고, 채팅 스트림과 직교라 V1(SANDBOX)/
 * V2(STORY) 어느 쪽에서든 동일하게 동작한다.
 *
 * <p>모델: {@code illustration.scene.director.model} — 미지정 시 {@code openai.model}
 * (gemini-3-flash) 폴백으로 시작해 튜닝. 구조화 태스크 비사고 모델 규율(docs/09 §B-3).
 */
@Slf4j
@Service
public class SceneDirectorService {

    /** 라이터에게 주는 로그 1건당 본문 상한 — 장문 나레이션의 토큰 폭주 방지. */
    private static final int LOG_SNIPPET_MAX = 400;

    private final OpenRouterClient openRouterClient;
    private final ObjectMapper objectMapper;
    private final SceneIllustrationProperties props;
    private final String fallbackModel;

    public SceneDirectorService(OpenRouterClient openRouterClient, ObjectMapper objectMapper,
                                SceneIllustrationProperties props,
                                @Value("${openai.model}") String fallbackModel) {
        this.openRouterClient = openRouterClient;
        this.objectMapper = objectMapper;
        this.props = props;
        this.fallbackModel = fallbackModel;
    }

    /**
     * 대화 맥락 → 씬 일러 스펙. 실패(호출/파싱)는 예외로 던진다 — 호출측(SceneRequestService)이
     * 에너지 환불 후 사용자 에러로 변환.
     *
     * @param recentLogs   최신순 로그(호출측이 상한 적용) — 내부에서 시간순으로 뒤집어 사용
     * @param cast         현재 씬에 등장 가능한 히로인(같은 공간) — 빈 리스트면 배경 전용 씬
     * @param locationText 현재 장소 표시명/키 (nullable)
     * @param sfw          비시크릿 방이면 true — 수위 지시(강제 자체는 ScenePromptAssembler의 게이트가 담당)
     */
    public AiJsonOutput.SceneIllustrationSpec composeSpec(List<ChatLogDocument> recentLogs,
                                                          List<Character> cast,
                                                          String locationText, boolean sfw) {
        return composeSpec(recentLogs, cast, locationText, sfw, true);
    }

    /** [2026-08-04 페르소나] userMale — 방 페르소나 스냅샷 성별(기본 남성 폴백 유지). */
    public AiJsonOutput.SceneIllustrationSpec composeSpec(List<ChatLogDocument> recentLogs,
                                                          List<Character> cast,
                                                          String locationText, boolean sfw,
                                                          boolean userMale) {
        String model = props.director().modelOrDefault(fallbackModel);
        String system = buildSystemPrompt(cast, sfw, userMale);
        String user = buildContextBlock(recentLogs, locationText);

        String raw = openRouterClient.completeJson(
            model, system, user, props.director().maxTokensOrDefault(), 0.4);
        return parseSpec(raw, cast);
    }

    /** LLM 원문 → 스펙 파싱 + cast 검증 (순수 함수 — 단위 테스트 대상). */
    AiJsonOutput.SceneIllustrationSpec parseSpec(String raw, List<Character> cast) {
        try {
            String clean = LlmOutputParser.extractJson(raw);
            AiJsonOutput.SceneIllustrationSpec spec =
                objectMapper.readValue(clean, AiJsonOutput.SceneIllustrationSpec.class);
            return sanitize(spec, cast);
        } catch (Exception e) {
            log.warn("[SCENE-DIRECTOR] 스펙 파싱 실패: {}", e.getMessage());
            throw new IllegalStateException("씬 스펙 생성 실패", e);
        }
    }

    /**
     * cast ref 검증 — 명단 밖 이름은 제거(planRender에서 외형 태그 null로 흘러
     * 무명 인물이 렌더되는 것 방지). user는 항상 허용. 전원 무효면 빈 cast(배경 전용)로 강등.
     */
    private AiJsonOutput.SceneIllustrationSpec sanitize(AiJsonOutput.SceneIllustrationSpec spec,
                                                        List<Character> cast) {
        if (spec == null) {
            throw new IllegalStateException("씬 스펙이 비어 있음");
        }
        if (spec.cast() == null || spec.cast().isEmpty()) return spec;
        Set<String> known = cast.stream()
            .map(c -> c.getName().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        List<AiJsonOutput.SceneCast> valid = spec.cast().stream()
            .filter(c -> c.isUser()
                || (c.ref() != null && known.contains(c.ref().toLowerCase(Locale.ROOT))))
            .toList();
        if (valid.size() == spec.cast().size()) return spec;
        log.info("[SCENE-DIRECTOR] 명단 밖 cast {}건 제거", spec.cast().size() - valid.size());
        return new AiJsonOutput.SceneIllustrationSpec(
            spec.locationDescription(), spec.actionDescription(), valid);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  프롬프트 — L1 다중인물 규약 전문 (docs/09 §A-1 디오라마 실측 확정)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSystemPrompt(List<Character> cast, boolean sfw, boolean userMale) {
        // [2026-08-04 남캐] 성별 힌트 동봉 — 디렉터가 cast.gender를 정확히 산출하게
        String heroineNames = cast.isEmpty() ? "(none — background-only scene)"
            : cast.stream()
            .map(c -> c.getName() + (c.getGenderOrDefault().isMale() ? " (male)" : " (female)"))
            .collect(Collectors.joining(", "));
        // 수위: 비시크릿은 안전 씬 지시(최종 강제는 ScenePromptAssembler sfw 게이트가 이중으로 담당)
        String rating = sfw
            ? "- This is a SAFE-FOR-WORK scene. No suggestive, revealing, or adult content of any kind."
            : "- Suggestive/adult framing is permitted for this room. Still no minors-adjacent content, ever.";
        return """
            You are a scene director for an anime visual novel. Analyze the recent conversation and
            compose ONE illustration spec for the current dramatic moment, as danbooru-style english tags.

            Output ONLY this JSON (no markdown, no commentary):
            {
              "location_description": "place tags (e.g. \\"cafe interior, window seat, afternoon sunlight\\") — NO character appearance here",
              "action_description": "SHARED interaction tags only (e.g. \\"sitting across table, holding hands\\")",
              "cast": [ { "ref": "<heroine name exactly as listed below, or \\"user\\">",
                          "kind": "heroine" | "user",
                          "gender": "female" | "male",
                          "emotion": "facial expression as lowercase danbooru tags (e.g. \\"smile, blush\\")",
                          "pose": "this person's own pose/action tags" } ]
            }

            Heroines present in this scene (ref MUST match one of these exactly): %s

            ## Layer rules (STRICT — violations ruin the render)
            - action_description is a SHARED layer: every tag applies to EVERYONE on screen.
              NEVER put one person's pose, state, or expression there — per-person tags go in cast[].pose / cast[].emotion.
            - emotion must be lowercase danbooru tags, NEVER emotion enum words (JOY, SHY, NEUTRAL...).
            - location_description carries place/lighting/time tags only — no people, no appearance.

            ## Cast rules (STRICT)
            - HARD LIMIT: at most 2 people total on screen. NEVER 3 or more.
            - Default is heroine alone (cast size 1). Include "user" ONLY when physical interaction
              between user and heroine is the visual core of the moment.
            - The user is an ADULT %s — never a child or teenager. The user is faceless: never describe
              the user's face; give the user poses where the face is naturally out of frame
              (from behind, head out of frame). NEVER give anyone camera or phone props.
            - "pov" is a FRAMING tag, not a pose. For first-person framing (viewer = user), put "pov"
              in action_description and DO NOT include "user" in cast — the viewer IS the camera and
              must never be drawn as a person holding one.
            - Choose camera/angle freely per scene — no fixed rule. Exception: for moments of physical
              interaction with the user, PREFER pov framing (action_description "pov", user omitted
              from cast) unless the moment clearly demands a third-person angle.

            ## Tag craft
            - For rare/complex actions, scaffold with common co-occurring tags
              (arm wrestling → "table, elbows on table, clasped hands, face-to-face").
            - Framing tags (close-up, upper body...) only when 2 or fewer people are on screen.
            - Pick the single most illustration-worthy moment from the LATEST exchanges — the "now" of the scene.
            %s
            """.formatted(heroineNames, userMale ? "male" : "female", rating);
    }

    private String buildContextBlock(List<ChatLogDocument> recentLogs, String locationText) {
        StringBuilder sb = new StringBuilder();
        if (locationText != null && !locationText.isBlank()) {
            sb.append("Current location: ").append(locationText).append("\n\n");
        }
        sb.append("Recent conversation (oldest → newest):\n");
        // 최신순 입력을 시간순으로 뒤집어 "마지막 줄 = 현재 순간"이 되게 한다
        for (int i = recentLogs.size() - 1; i >= 0; i--) {
            ChatLogDocument logDoc = recentLogs.get(i);
            String body = logDoc.getCleanContent();
            if (body == null || body.isBlank()) continue;
            if (body.length() > LOG_SNIPPET_MAX) body = body.substring(0, LOG_SNIPPET_MAX) + "…";
            sb.append('[').append(logDoc.getRole()).append("] ").append(body).append('\n');
        }
        sb.append("\nCompose the illustration spec for the latest moment.");
        return sb.toString();
    }
}
