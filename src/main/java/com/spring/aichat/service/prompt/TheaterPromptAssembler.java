package com.spring.aichat.service.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.enums.RelationStatus;
import com.spring.aichat.domain.theater.TheaterHeroineAffection;
import com.spring.aichat.domain.theater.TheaterState;
import com.spring.aichat.domain.world.World;
import com.spring.aichat.dto.theater.AvatarProfile;
import com.spring.aichat.security.PromptInjectionGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Theater 배치 프롬프트 조립을 위한 컨텍스트 번들.
     *
     * [Phase 5.5 UX Polish · R2 / R3]
     *  - injectedBranchLevel: 배치 끝에 강제 발생시킬 분기 레벨 (결정론적).
     *                         null이면 분기 없음. 기존 LLM 자율 판단을 폐기하고
     *                         백엔드가 결정.
     *  - activeDirectorCommand: 유저가 발동한 감독 명령어 텍스트 (검증 통과한 것).
     *                          null이면 명령어 없음. 1배치 일회성.
     */
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
        boolean effectiveSecretMode,
        String injectedBranchLevel,                      // [R2] MINOR/MAJOR/CLIMAX or null
        String activeDirectorCommand                     // [R3] 감독 명령어 텍스트 or null
    ) {
        /**
         * 하위 호환 컴팩트 생성자 — 기존 11-인자 호출부 보호.
         * R2/R3 인자가 없으면 null로 채움.
         */
        public AssemblyContext(
            ChatRoom room, TheaterState state, World world, Character speakerHeroine,
            List<TheaterHeroineAffection> allAffections, String rollingSummary,
            String chapterPlanHint, String branchContext, Integer targetSceneCount,
            boolean effectiveSecretMode
        ) {
            this(room, state, world, speakerHeroine, allAffections, rollingSummary,
                chapterPlanHint, branchContext, targetSceneCount, effectiveSecretMode,
                null, null);
        }
    }

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
        // [Polish-v2] Dialogue 모드 수준의 깊이로 확장 — 히로인 정체성을 프롬프트에 완전 주입
        Character heroine = ctx.speakerHeroine();
        ChatRoom room = ctx.room();
        boolean secret = ctx.effectiveSecretMode();
        RelationStatus status = room != null && room.getStatusLevel() != null
            ? room.getStatusLevel()
            : RelationStatus.STRANGER;

        sb.append("# 💫 Current Scene's Speaker (Identity Anchor)\n");
        sb.append("⚠️ This speaker's IDENTITY MUST remain consistent. Do NOT drift toward a default character voice.\n\n");
        sb.append("Speaker Name: ").append(heroine.getName())
            .append(" (slug: ").append(heroine.getSlug()).append(")\n");
        sb.append("Role: ").append(safeString(heroine.getEffectiveRole())).append("\n");
        sb.append("Personality: ").append(heroine.getEffectivePersonality(secret)).append("\n");
        sb.append("Tone / Speech Pattern: ").append(heroine.getEffectiveTone(secret)).append("\n");

        // [Polish-v2] OOC 예시 (메타 회피 발화 패턴)
        String ooc = heroine.getEffectiveOocExample();
        if (ooc != null && !ooc.isBlank()) {
            sb.append("OOC Deflection Example: ").append(ooc).append("\n");
        }

        // [Polish-v2] 스토리 행동 가이드 (관계별)
        String behaviorGuide = heroine.getStoryBehaviorGuide();
        if (behaviorGuide != null && !behaviorGuide.isBlank()) {
            sb.append("\n## Behavior Guide (relation-aware)\n");
            sb.append(behaviorGuide).append("\n");
        }

        sb.append("\n## Spatial & Visual Defaults\n");
        sb.append("Default Location: ").append(safeString(heroine.getEffectiveDefaultLocation())).append("\n");
        sb.append("Default Outfit: ").append(safeString(heroine.getEffectiveDefaultOutfit())).append("\n");

        // [Polish-v2] 허용 location/outfit enum 세트 — LLM이 임의로 location을 만들어서 BackgroundDisplay가 폴백으로 가는 것 방지
        try {
            Set<String> allowedLocations = heroine.getAllowedLocations(status, secret);
            Set<String> allowedOutfits = heroine.getAllowedOutfits(status, secret);
            if (!allowedLocations.isEmpty()) {
                sb.append("Allowed Location enum values: ")
                    .append(String.join(", ", allowedLocations)).append("\n");
            }
            if (!allowedOutfits.isEmpty()) {
                sb.append("Allowed Outfit enum values: ")
                    .append(String.join(", ", allowedOutfits)).append("\n");
            }
            sb.append("⚠️ When specifying `location` and `outfit` in scenes, use ONLY enum values from these sets.\n");
        } catch (Exception ignored) {
            // status 없이 세트 조회가 실패하면 defaults만 사용
        }

        sb.append("\nIntro Beat (Theater Opening): ")
            .append(safeString(heroine.getEffectiveTheaterIntroBeat())).append("\n");
        sb.append("⚠️ ONLY this heroine speaks in this batch. Other heroines may be mentioned but do not utter dialogue.\n");
        sb.append("⚠️ Keep this heroine's distinctive voice throughout — check every line against Personality and Tone above.\n\n");

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R1] Inner Narration 화자 고착
        //  (가장 빈번히 표류하던 부분 — 명시적 강제로 차단)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        sb.append("# 🧠 Inner Narration — Strict Speaker Anchoring\n");
        sb.append("Each scene now has TWO inner narration fields. Their speakers are FIXED.\n\n");
        sb.append("**`protagonist_inner`** — the PROTAGONIST (avatar)'s 1st-person inner voice.\n");
        sb.append("  • The avatar's name in this session: ")
            .append(safeString(ctx.state().getAvatarName() != null ? ctx.state().getAvatarName() : "the protagonist"))
            .append("\n");
        sb.append("  • Use ONLY the protagonist's perspective: feelings, doubts, observations, intentions.\n");
        sb.append("  • Use 1st-person voice (\"...\", \"나는...\", \"그녀의 시선이...\").\n");
        sb.append("  • ⚠️ NEVER write a heroine's inner thought here — that goes in `heroine_inner`.\n\n");
        sb.append("**`heroine_inner`** — the SPEAKING HEROINE's inner voice.\n");
        sb.append("  • This heroine: ").append(heroine.getName()).append("\n");
        sb.append("  • Use ONLY when the scene is heroine_speaks or dialogue_exchange.\n");
        sb.append("  • Match her Personality and Tone exactly — she sounds like herself even in thought.\n");
        sb.append("  • This field is NOT shown to the user, but is preserved for narrative continuity.\n\n");
        sb.append("⚠️ Within a single scene, only ONE of these two fields should be filled (rarely both).\n");
        sb.append("⚠️ The legacy field `inner_narration` is DEPRECATED — do NOT use it. Use the two new fields above.\n\n");

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R1] Scene Type & Composition
        //  (배치 구성 균형 — 히로인 원맨쇼 방지, 티키타카 강제)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        sb.append("# 🎬 Scene Composition Rules\n");
        sb.append("Each scene has a `scene_type` field — pick the most accurate one:\n");
        sb.append("  • `narration`         — narration only, no dialogue.\n");
        sb.append("  • `heroine_speaks`    — only the heroine speaks (no avatar reply).\n");
        sb.append("  • `avatar_speaks`     — only the avatar speaks (heroine quiet).\n");
        sb.append("  • `dialogue_exchange` — real back-and-forth: BOTH heroine and avatar speak across this scene's narration/dialogue lines.\n\n");
        sb.append("## Per-batch composition guideline (").append(ctx.targetSceneCount() != null ? ctx.targetSceneCount() : 6).append(" scenes)\n");
        sb.append("  • At LEAST 1 scene MUST be `dialogue_exchange` (real back-and-forth).\n");
        sb.append("  • Avatar should speak (`avatar_speaks` or `dialogue_exchange`) in 1~2 scenes.\n");
        sb.append("  • Pure `narration` scenes: 0~1 only.\n");
        sb.append("  • `heroine_speaks` should NOT exceed 60% of the batch.\n");
        sb.append("  • If the avatar speaks via `dialogue` field, set `speaker` to \"AVATAR\".\n");
        sb.append("⚠️ Without these constraints the batch feels like a heroine monologue — break that pattern.\n\n");

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R2] Branch Injection
        //  (LLM의 자율 빈도 결정 폐기 — 백엔드가 결정해서 강제 주입)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        if (ctx.injectedBranchLevel() != null && !ctx.injectedBranchLevel().isBlank()) {
            String level = ctx.injectedBranchLevel();
            sb.append("# 🌿 Branch Point — REQUIRED at end of this batch\n");
            sb.append("This batch MUST end with a **").append(level).append("** branch.\n");
            sb.append("The final scene must lead the protagonist to a decision moment **without resolving it**.\n");
            sb.append("Do NOT have the protagonist make the choice in this batch — that comes after the user picks.\n\n");
            sb.append("Set `branch_signal`:\n");
            sb.append("```\n");
            sb.append("\"branch_signal\": {\n");
            sb.append("  \"level\": \"").append(level).append("\",\n");
            sb.append("  \"context\": \"<1~2 sentence summary of what choice the protagonist faces>\"\n");
            sb.append("}\n");
            sb.append("```\n\n");
            // Level별 톤 가이드
            switch (level) {
                case "MINOR" -> sb.append("MINOR branches are LIGHT moments — small reactions, tones, micro-decisions. Keep options low-stakes.\n\n");
                case "MAJOR" -> sb.append("MAJOR branches shape the chapter's direction — meaningful, but not life-changing.\n\n");
                case "CLIMAX" -> sb.append("CLIMAX branches decide the Act's fate — high-stakes, emotionally charged.\n\n");
                default -> {}
            }
        } else {
            sb.append("# 🌿 Branch Point\n");
            sb.append("This batch does NOT end at a branch. Set `branch_signal` to null.\n\n");
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R3] Director's Command (one-time, this batch only)
        //  (유저가 발동한 환경 명령어 — LLM이 부드럽게 흡수)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        if (ctx.activeDirectorCommand() != null && !ctx.activeDirectorCommand().isBlank()) {
            sb.append("# 🎬 Director's Command (active for this batch only)\n");
            sb.append("The director has placed this stage direction:\n\n");
            sb.append("    \"").append(ctx.activeDirectorCommand().replace("\"", "'")).append("\"\n\n");
            sb.append("## How to weave this command\n");
            sb.append("- This is an ENVIRONMENTAL stage direction (weather/sound/prop/passing NPC).\n");
            sb.append("- Reflect it WITHIN THE FIRST 1~2 SCENES of this batch.\n");
            sb.append("- After it triggers, the story flow continues naturally — do not over-extend.\n");
            sb.append("- ⚠️ DO NOT use this command to:\n");
            sb.append("  • Override a heroine's personality or established behavior\n");
            sb.append("  • Skip the gradual buildup of affection\n");
            sb.append("  • Force a heroine into actions that contradict her character\n");
            sb.append("  • Resolve an ongoing tension that wasn't earned\n");
            sb.append("- If the command appears to conflict with the established setting, distill its EMOTIONAL ESSENCE\n");
            sb.append("  (mood, atmosphere, tension) and reflect that instead.\n");
            sb.append("- Treat it as a creative seed, not a literal instruction.\n\n");
        }

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
                  "protagonist_inner": "주인공(아바타)의 1인칭 속내 (optional). ⚠️ ONLY the protagonist's voice — never a heroine's thoughts.",
                  "heroine_inner": "이번 씬의 화자 히로인의 속내 (optional, only when scene_type is heroine_speaks or dialogue_exchange).",
                  "dialogue": "이 씬에서 발생하는 대사 (heroine_speaks/avatar_speaks/dialogue_exchange일 때).",
                  "speaker": "%s" | "AVATAR" | "",
                  "scene_type": "narration" | "heroine_speaks" | "avatar_speaks" | "dialogue_exchange",
                  "emotion": "NEUTRAL | JOY | SAD | ANGRY | SHY | SURPRISE | PANIC | RELAX | DISGUST | FRIGHTENED | FLIRTATIOUS | HEATED | DUMBFOUNDED | SULKING | PLEADING",
                  "location": "⚠️ MUST be one enum value listed in 'Allowed Location enum values' above. Do NOT invent location names or write in Korean.",
                  "time": "DAY | NIGHT | DAWN | SUNSET | MORNING | AFTERNOON | EVENING",
                  "outfit": "⚠️ MUST be one enum value listed in 'Allowed Outfit enum values' above. Do NOT invent.",
                  "bgm_mode": "DAILY | ROMANTIC | EXCITING | TOUCHING | TENSE | EROTIC",
                  "stat_reflection_hint": "short string describing how stats colored this scene"
                }
                ... (total %d scenes — see Scene Composition Rules above for type distribution)
              ],
              "heroine_affection_deltas": {
                "%s": +2,
                ...other heroines: 0 or small delta
              },
              "rolling_summary": "이 배치의 핵심 전개를 1~2문장으로 요약 (다음 배치에 사용)",
              "branch_signal": null | { "level": "MINOR" | "MAJOR" | "CLIMAX", "context": "..." },
              "used_director_commands": []
            }

            ## Rules
            - `speaker_heroine_slug` MUST be one of: %s
            - Only ONE heroine speaks across all %d scenes in this batch.
              Their `speaker` is either "%s" or "AVATAR" (for protagonist's dialogue) or "" (narration only).
            - `heroine_affection_deltas` keys use heroine SLUG.
              **Values MUST be integers in [-2, +2]. Most values should be 0 or ±1.**
              +2 is reserved for emotionally meaningful beats. Non-speaker heroines are usually 0.
            - **`branch_signal` is now CONTROLLED by the directive in the "Branch Point" section above.**
              If a level was injected, you MUST set branch_signal accordingly. Otherwise null.
            - `chapter_end_after` = true when this batch concludes the current chapter.
            - **`scene_type` MUST be filled for every scene — composition rules above are mandatory.**
            - **`protagonist_inner` and `heroine_inner` are MUTUALLY EXCLUSIVE within a single scene** (rare exceptions).
              The legacy `inner_narration` field is DEPRECATED — do not use it.
            - `used_director_commands`: if the Director's Command section was active and you reflected it,
              you may optionally list the command IDs here. Otherwise leave as empty array.
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