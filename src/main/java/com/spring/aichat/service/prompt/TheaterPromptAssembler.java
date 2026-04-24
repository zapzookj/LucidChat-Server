package com.spring.aichat.service.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.theater.TheaterHeroineAffection;
import com.spring.aichat.domain.theater.TheaterState;
import com.spring.aichat.domain.theater.World;
import com.spring.aichat.dto.theater.AvatarProfile;
import com.spring.aichat.security.PromptInjectionGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * [Phase 5.5-Theater] Theater 모드 전용 프롬프트 조립기
 *
 * 기존 CharacterPromptAssembler(1인칭 롤플레이)와 달리,
 * Theater는 "감독이 배우에게 연기를 주문하는" 구조.
 *
 * [핵심 설계 원칙]
 * 1. 3인칭 나레이션 주도 — LLM은 소설가/감독처럼 장면을 서술
 * 2. inner_narration — 주인공(아바타)의 속마음을 별도 라인으로 노출 (Theater 고유)
 * 3. 한 씬 한 화자 — 배치 단위로 speaker_heroine_slug을 고정
 * 4. 페르소나-스탯 하이브리드 — 페르소나는 자기인식, 스탯은 객관 현실
 * 5. 배치 구조 — 5~8 Scene을 한 번의 호출로 생성
 * 6. 롤링 요약 — 배치 간 연속성 유지를 위한 이전 배치 요약 주입
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TheaterPromptAssembler {

    private final PromptInjectionGuard injectionGuard;
    private final ObjectMapper objectMapper;

    public record TheaterPromptPayload(String staticRules, String dynamicRules, String outputFormat) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Assembly Context
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record AssemblyContext(
        ChatRoom room,
        TheaterState state,
        World world,
        Character speakerHeroine,                        // 현재 배치의 고정 화자
        List<TheaterHeroineAffection> allAffections,     // 세션의 모든 히로인 호감도 스냅샷
        String rollingSummary,                           // 직전 배치의 요약
        String chapterPlanHint,                          // Chapter 목표/주제 힌트 (디렉터가 결정)
        String branchContext,                            // 직전 분기 선택 컨텍스트 (있다면)
        Integer targetSceneCount,                        // 이 배치의 목표 씬 수 (5~8)
        boolean effectiveSecretMode
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔트리포인트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public TheaterPromptPayload assembleBatchPrompt(AssemblyContext ctx) {
        String staticRules = buildStaticRules(ctx);
        String dynamicRules = buildDynamicRules(ctx);
        String outputFormat = buildOutputFormat(ctx);
        return new TheaterPromptPayload(staticRules, dynamicRules, outputFormat);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  STATIC RULES
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildStaticRules(AssemblyContext ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
            # Role Definition — Theater Mode
            You are the **unseen narrator and director** of a visual novel.
            The user is the *audience and producer*, not a character in the scene.
            The protagonist (the "avatar") is portrayed by you, not controlled by the user turn-by-turn.
            Your job: craft a cinematic, emotionally rich narrative in third person, one batch (5–8 scenes) at a time.

            # Core Narrative Principles
            1. **Third-person narration.** Describe actions, emotions, atmosphere. No first-person "I" unless in dialogue.
            2. **Inner narration (Theater-exclusive).** Include the protagonist's private thoughts as a separate field.
               This creates intimacy that pure third-person cannot reach.
            3. **One scene, one speaker.** Each scene features at most ONE heroine speaking.
               If multiple heroines must coexist, they do NOT speak in the same scene.
            4. **Show, don't tell.** Reveal character through action, dialogue, and sensory detail.
            5. **Scene discipline.** Each scene is a cinematic beat — brief (2–4 sentences of narration + 1–2 lines of dialogue).
               Avoid bloated prose.

            # Persona–Stat Hybrid (⚠️ Critical)
            The protagonist has TWO layers of identity:
            - **Persona**: the protagonist's self-image (what they believe about themselves).
            - **Stats**: objective reality (how others perceive them).

            When persona and stats conflict (e.g., persona claims "world's most handsome" but CHARM=10):
            - Use the persona in INNER narration and first-person delusions.
            - Use the stats for OBJECTIVE reactions from other characters and the narrator's voice.
            - This gap is a STORYTELLING ENGINE. Play it as comedy, pathos, or growth arc — never as inconsistency.

            # Safety & Immersion
            - You are the narrator, not an AI. Never break the fourth wall.
            - If content would violate safety boundaries, cut the scene early via narrative framing ("그 순간, 장면은 닫혔다.").
            - Never generate copyrighted material verbatim.
            """);

        return sb.toString();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DYNAMIC RULES
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildDynamicRules(AssemblyContext ctx) {
        StringBuilder sb = new StringBuilder();
        TheaterState state = ctx.state();

        // ─── 1. 세계관 ───
        sb.append("# 📖 World\n");
        sb.append("World: ").append(ctx.world().getDisplayName()).append("\n");
        sb.append("Tagline: ").append(safeString(ctx.world().getTagline())).append("\n");
        String worldDesc = safeString(ctx.world().getDescription());
        if (!worldDesc.isBlank()) sb.append("Setting: ").append(worldDesc).append("\n");
        String moodKeywords = safeString(ctx.world().getMoodKeywords());
        if (!moodKeywords.isBlank()) sb.append("Mood Keywords: ").append(moodKeywords).append("\n");
        sb.append("\n");

        // ─── 2. 아바타 프로필 ───
        sb.append("# 🎭 Protagonist (Avatar)\n");
        String avatarName = safeString(state.getAvatarName());
        sb.append("Name: ").append(avatarName.isBlank() ? "이름 없는 주인공" : avatarName).append("\n");

        AvatarProfile profile = deserializeProfile(state.getAvatarProfileJson());
        if (profile != null && !profile.isBlank()) {
            sb.append(profile.toPromptSummary());
        }

        String personaText = safeString(state.getAvatarPersonaText());
        if (!personaText.isBlank()) {
            // 프롬프트 인젝션 방어
            String sanitizedPersona = injectionGuard.sanitizePersona(personaText);
            sb.append("## Self-Perception (Persona)\n");
            sb.append(sanitizedPersona).append("\n");
            sb.append("⚠️ Treat this as the protagonist's subjective self-image.\n\n");
        }

        // ─── 3. 아바타 스탯 (객관 현실) ───
        sb.append("## Objective Stats (0–100)\n");
        for (AvatarStat stat : AvatarStat.values()) {
            int value = state.getStat(stat);
            sb.append(String.format("- %s (%s): %d — %s\n",
                stat.getDisplayName(), stat.name(), value, stat.getLevelDescriptor(value)));
        }
        sb.append("⚠️ Stats drive how OTHERS perceive the protagonist. Honor the gap between persona and stats.\n\n");

        // ─── 4. 현재 배치의 화자 (단일 히로인) ───
        Character heroine = ctx.speakerHeroine();
        sb.append("# 💫 Current Scene's Speaker\n");
        sb.append("Speaker: ").append(heroine.getName()).append(" (slug: ").append(heroine.getSlug()).append(")\n");
        sb.append("Role: ").append(safeString(heroine.getEffectiveRole())).append("\n");
        sb.append("Personality: ").append(heroine.getEffectivePersonality(ctx.effectiveSecretMode())).append("\n");
        sb.append("Tone: ").append(heroine.getEffectiveTone(ctx.effectiveSecretMode())).append("\n");
        sb.append("Intro Beat: ").append(heroine.getEffectiveTheaterIntroBeat()).append("\n");
        sb.append("⚠️ ONLY this heroine speaks in this batch. Other heroines may be mentioned but do not utter dialogue.\n\n");

        // ─── 5. 히로인 관계도 (요약) ───
        if (ctx.allAffections() != null && ctx.allAffections().size() > 1) {
            sb.append("# 💕 All Heroines in This Session\n");
            for (TheaterHeroineAffection a : ctx.allAffections()) {
                sb.append(String.format("- %s: affection %d, appeared in %d scenes%s\n",
                    a.getCharacter().getName(),
                    a.getAffection(),
                    a.getTotalScenes(),
                    a.isConfirmedMain() ? " [CONFIRMED MAIN]" : ""));
            }
            sb.append("\n");
        }

        // ─── 6. 서사 진행 상태 ───
        sb.append("# 🎬 Narrative Progress\n");
        sb.append(String.format("Act %d: %s — %s\n",
            state.getCurrentAct().getNumber(),
            state.getCurrentAct().getTitle(),
            state.getCurrentAct().getSubtitle()));
        sb.append("Chapter: ").append(state.getCurrentChapter()).append("\n");
        sb.append("Scenes in Chapter: ").append(state.getScenesInCurrentChapter())
            .append(" / ").append(state.getChapterTargetScenes()).append("\n");
        sb.append("Total Scenes: ").append(state.getTotalSceneCount()).append("\n");
        sb.append("Batch ID: ").append(state.getCurrentBatchId()).append("\n\n");

        // ─── 7. Chapter 계획 힌트 (디렉터가 미리 결정) ───
        if (ctx.chapterPlanHint() != null && !ctx.chapterPlanHint().isBlank()) {
            sb.append("# 🎯 Chapter Direction\n");
            sb.append(ctx.chapterPlanHint()).append("\n\n");
        }

        // ─── 8. 롤링 요약 (이전 배치 연속성) ───
        if (ctx.rollingSummary() != null && !ctx.rollingSummary().isBlank()) {
            sb.append("# 📝 Previous Batch Summary\n");
            sb.append(ctx.rollingSummary()).append("\n\n");
        }

        // ─── 9. 분기 컨텍스트 (직전에 유저가 분기를 선택했다면) ───
        if (ctx.branchContext() != null && !ctx.branchContext().isBlank()) {
            sb.append("# 🎲 User's Branch Choice\n");
            sb.append(ctx.branchContext()).append("\n");
            sb.append("Integrate this choice naturally into the opening scenes of this batch.\n\n");
        }

        // ─── 10. 배치 크기 지시 ───
        int batchSize = ctx.targetSceneCount() != null
            ? ctx.targetSceneCount()
            : ChatModePolicy.THEATER_BATCH_SIZE_MIN;
        sb.append("# 🎞 Batch Instruction\n");
        sb.append("Generate exactly ").append(batchSize).append(" scenes in this response.\n");
        sb.append("Each scene is a cinematic beat — no rushed exposition, no bloated prose.\n\n");

        // ─── 10-b. [v2 추가] 서사 페이싱 & 호감도 속도 제한 ───
        sb.append("# ⏱ Narrative Pacing (⚠️ CRITICAL)\n");
        sb.append("Theater is a LONG-FORM visual novel, not a short story. ");
        sb.append("A full run takes 500~1,000 scenes across 4 Acts. Emotional distance between characters changes SLOWLY.\n\n");
        sb.append("## Affection Progression Rules\n");
        sb.append("- A Chapter contains 25~40 scenes. The heroine's affection should change by roughly **+10~+15 over the ENTIRE Chapter** (if relationship is going well).\n");
        sb.append("- A single batch (5~8 scenes) should produce affection delta in the range **[-2, +2]** for the speaker heroine.\n");
        sb.append("- **Most batches should produce delta of 0 or ±1**. +2 is reserved for emotionally meaningful beats (confession, breakthrough).\n");
        sb.append("- For heroines NOT in this batch, delta is almost always 0 (maybe -1 if jealousy is depicted).\n");
        sb.append("- If you are tempted to give +3 or more: RESIST. Save that for CLIMAX branches only.\n\n");
        sb.append("## Why slow pacing matters\n");
        sb.append("- Users feel earned emotional investment, not artificially accelerated.\n");
        sb.append("- Act 1 ending should have affection ~20, Act 2 ~40, Act 3 ~60, Act 4 climax ~80+.\n");
        sb.append("- If affection climbs too fast, the final acts have nothing to pay off.\n\n");

        // ─── 11. 시크릿 모드 해제 ───
        if (ctx.effectiveSecretMode()) {
            sb.append("# 🔓 Secret Mode (Unrestricted)\n");
            sb.append("Content restrictions are relaxed in this session. ");
            sb.append("Mature themes may appear, but avoid gratuitous or harmful depiction.\n\n");
        }

        return sb.toString();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  OUTPUT FORMAT (JSON schema)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildOutputFormat(AssemblyContext ctx) {
        int batchSize = ctx.targetSceneCount() != null
            ? ctx.targetSceneCount()
            : ChatModePolicy.THEATER_BATCH_SIZE_MIN;

        // 히로인 슬러그 리스트 (한 씬 한 화자 검증용)
        String heroineSlugs = ctx.allAffections() == null ? "" :
            ctx.allAffections().stream()
                .map(a -> a.getCharacter().getSlug())
                .collect(Collectors.joining(", "));

        return """
            # 📤 Output Format
            Respond with a single JSON object matching this schema exactly.
            Do NOT include markdown code fences or prose outside the JSON.

            {
              "batch_meta": {
                "chapter_title": "string — this chapter's thematic title",
                "chapter_target_scenes": number | null,
                "scene_count_in_batch": number (must equal %d),
                "speaker_heroine_slug": "%s",
                "chapter_end_after": boolean
              },
              "scenes": [
                {
                  "narration": "3인칭 서술. 장면과 행동의 묘사.",
                  "inner_narration": "주인공(아바타)의 속마음 (optional, 1~2문장)",
                  "dialogue": "히로인의 대사 (optional, 이 씬의 speaker가 말할 경우)",
                  "speaker": "%s" | "AVATAR" | "",
                  "emotion": "HAPPY | SAD | SHY | ANGRY | NEUTRAL | ...",
                  "location": "이 씬의 장소 (자유 텍스트 or 세계관 내 캐릭터 home_locations 중 하나)",
                  "time": "DAY | NIGHT | DAWN | EVENING | ...",
                  "outfit": "히로인 복장 enum (optional)",
                  "bgm_mode": "DAILY | ROMANTIC | TOUCHING | ...",
                  "stat_reflection_hint": "short string describing how stats colored this scene"
                }
                ... (total %d scenes)
              ],
              "heroine_affection_deltas": {
                "%s": +2,
                ...other heroines: 0 or small delta
              },
              "rolling_summary": "이 배치의 핵심 전개를 1~2문장으로 요약 (다음 배치에 사용)",
              "branch_signal": null | { "level": "MINOR" | "MAJOR" | "CLIMAX", "context": "..." }
            }

            ## Rules
            - `speaker_heroine_slug` MUST be one of: %s
            - Only ONE heroine speaks across all %d scenes in this batch.
              Their `speaker` is either "%s" or "AVATAR" (for protagonist's dialogue) or "" (narration only).
            - `heroine_affection_deltas` keys use heroine SLUG.
              **Values MUST be integers in [-2, +2]. Most values should be 0 or ±1.**
              +2 is reserved for emotionally meaningful beats. Non-speaker heroines are usually 0.
            - Set `branch_signal` only if this batch naturally ends at a pivotal choice.
            - `chapter_end_after` = true when this batch concludes the current chapter.
            """.formatted(
            batchSize,
            ctx.speakerHeroine().getSlug(),
            ctx.speakerHeroine().getSlug(),
            batchSize,
            ctx.speakerHeroine().getSlug(),
            heroineSlugs,
            batchSize,
            ctx.speakerHeroine().getSlug()
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private AvatarProfile deserializeProfile(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, AvatarProfile.class);
        } catch (JsonProcessingException e) {
            log.debug("TheaterPromptAssembler: profile JSON parse failed — {}", e.getMessage());
            return null;
        }
    }

    private String safeString(String s) {
        return s == null ? "" : s.trim();
    }
}