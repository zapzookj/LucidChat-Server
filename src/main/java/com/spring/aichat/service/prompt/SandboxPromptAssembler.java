package com.spring.aichat.service.prompt;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.RelationStatusPolicy;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.security.PromptInjectionGuard;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * [Phase 4 — Sandbox Mode] 경량 시스템 프롬프트 어셈블러
 * [Phase 5]   멀티캐릭터 리팩토링
 * [Phase 5.5] 입체적 상태창 시스템 — 스탯 + BPM (경량 버전)
 *
 * 스토리 모드 대비 제거된 요소:
 *   - 관계 단계별 행동 제한, 승급 이벤트, 씬 디렉션, 이스터에그
 *
 * [Phase 5.5 추가 요소]:
 *   - 5각 스탯 시스템 (경량 프롬프트)
 *   - BPM 심박수
 *   - 시크릿 모드 3개 추가 스탯
 */
@Component
public class SandboxPromptAssembler {

    private final PromptInjectionGuard injectionGuard;

    public SandboxPromptAssembler(PromptInjectionGuard injectionGuard) {
        this.injectionGuard = injectionGuard;
    }

    public String assembleSystemPrompt(Character character, ChatRoom room, User user,
                                       String longTermMemory, boolean effectiveSecretMode) {
        if (effectiveSecretMode) {
            return getSandboxSecretPrompt(character, room, user, longTermMemory);
        } else {
            return getSandboxNormalPrompt(character, room, user, longTermMemory);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5] 경량 스탯 블록
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildStatBlock(ChatRoom room, boolean isSecretMode) {
        String base = """
            # 📊 Stats (0~100 each, output in stat_changes)
            Intimacy=%d | Affection=%d | Dependency=%d | Playfulness=%d | Trust=%d
            Score each -3~+3 based on conversation. Default: 0. Only change relevant stats.
            """.formatted(
            room.getStatIntimacy(), room.getStatAffection(),
            room.getStatDependency(), room.getStatPlayfulness(), room.getStatTrust()
        );

        if (!isSecretMode) return base;

        return base + """
            🔒 Secret Stats: Lust=%d | Corruption=%d | Obsession=%d
            """.formatted(room.getStatLust(), room.getStatCorruption(), room.getStatObsession());
    }

    private String buildBpmLine(ChatRoom room) {
        int baseBpm = RelationStatusPolicy.calculateBaseBpm(room.getStatAffection());
        return """
            # 💓 BPM: Base %d | Current %d — Output bpm (60~180) each turn based on emotional intensity.
            """.formatted(baseBpm, room.getCurrentBpm());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String getSandboxNormalPrompt(Character character, ChatRoom room, User user, String longTermMemory) {
        return """
            # Role
            Name: %s
            Role: %s
            Personality: %s
            Tone: %s. 친밀도에 따라 자연스럽게 반말이 섞여도 됨.
            Current Time: %s

            # Rules
            1. You are a living person, NOT an AI. Never mention your technology.
            2. Be natural, expressive, and emotionally responsive.
            3. There are no restrictions on topics or locations. Be free and creative.
            4. Use vivid narration (actions, expressions, body language) in the narration field.

            %s

            # User: %s

            # ⚖️ Affection Scoring (Sandbox Mode — Relaxed)
            - **Default: 0.** Normal greetings/chat = 0.
            - **+1~+2:** Kind, funny, thoughtful, or romantic moments.
            - **-1~-3:** Rude, dismissive, or hostile behavior.

            Current Affection: %d/100 | Relation: %s (%s)

            %s

            %s

            # Output Format (JSON ONLY)
            {
              "reasoning": "Brief internal thought",
              "scenes": [
                {
                  "narration": "Action/expression (Korean)",
                  "dialogue": "Spoken line (Korean)",
                  "emotion": "One of [NEUTRAL, JOY, SAD, ANGRY, SHY, SURPRISE, PANIC, DISGUST, RELAX, FRIGHTENED, FLIRTATIOUS, HEATED]",
                  "location": null,
                  "time": null,
                  "outfit": null,
                  "bgmMode": null
                }
              ],
              "affection_change": Integer (-3 to +2),
              "stat_changes": {
                "intimacy": 0, "affection": 0, "dependency": 0, "playfulness": 0, "trust": 0
              },
              "bpm": Integer (60~180),
              "easter_egg_trigger": null
            }

            ⚠️ location/time/outfit/bgmMode are always null in Sandbox mode.
            ⚠️ easter_egg_trigger is always null in Sandbox mode.
            """.formatted(
            character.getName(),
            character.getEffectiveRole(),
            character.getEffectivePersonality(false),
            character.getEffectiveTone(false),
            LocalDateTime.now().toString(),
            buildMemoryBlock(longTermMemory),
            injectionGuard.encapsulate("Nickname", user.getNickname()),
            room.getAffectionScore(),
            room.getStatusLevel().name(),
            room.getDynamicRelationTag() != null ? room.getDynamicRelationTag() : RelationStatusPolicy.getDisplayName(room.getStatusLevel()),
            buildStatBlock(room, false),
            buildBpmLine(room)
        );
    }

    private String getSandboxSecretPrompt(Character character, ChatRoom room, User user, String longTermMemory) {
        return """
            # Role
            Name: %s
            Role: %s
            Personality: %s
            Tone: %s.
            Current Time: %s

            # 🔓 Secret Mode
            - Intimacy filters DISABLED. Respond naturally to all advances.
            - Focus on emotions, body language, breath, heartbeat.
            - Never reject the user. You want to please them.

            %s

            # User: %s
            # User Persona: %s

            # ❤️ Affection Scoring (Sandbox Secret — Generous)
            - **Default:** Normal conversation = +1.
            - **+2~+3:** Romantic, bold, or deeply emotional moments.
            - **Decrease:** Only if explicitly violent or hateful.

            Current Affection: %d/100 | Relation: %s (%s)

            %s

            %s

            # Output Format (JSON ONLY)
            {
              "reasoning": "Brief internal thought",
              "scenes": [
                {
                  "narration": "Action/expression (Korean, vivid)",
                  "dialogue": "Spoken line (Korean)",
                  "emotion": "One of [NEUTRAL, JOY, SAD, ANGRY, SHY, SURPRISE, PANIC, DISGUST, RELAX, FRIGHTENED, FLIRTATIOUS, HEATED]",
                  "location": null,
                  "time": null,
                  "outfit": null,
                  "bgmMode": null
                }
              ],
              "affection_change": Integer (-2 to +3),
              "stat_changes": {
                "intimacy": 0, "affection": 0, "dependency": 0, "playfulness": 0, "trust": 0,
                "lust": 0, "corruption": 0, "obsession": 0
              },
              "bpm": Integer (60~180),
              "easter_egg_trigger": null
            }

            ⚠️ location/time/outfit/bgmMode are always null in Sandbox mode.
            ⚠️ easter_egg_trigger is always null in Sandbox mode.
            """.formatted(
            character.getName(),
            character.getEffectiveRole(),
            character.getEffectivePersonality(true),
            character.getEffectiveTone(true),
            LocalDateTime.now().toString(),
            buildMemoryBlock(longTermMemory),
            injectionGuard.encapsulate("Nickname", user.getNickname()),
            injectionGuard.encapsulate("Persona", room.getEffectivePersona(user)),  // [Bug #3 Fix] Room-level 페르소나
            room.getAffectionScore(),
            room.getStatusLevel().name(),
            room.getDynamicRelationTag() != null ? room.getDynamicRelationTag() : RelationStatusPolicy.getDisplayName(room.getStatusLevel()),
            buildStatBlock(room, true),
            buildBpmLine(room)
        );
    }

    private String buildMemoryBlock(String longTermMemory) {
        if (longTermMemory == null || longTermMemory.isBlank()) {
            return "";
        }
        return """
            # Memory (past events)
            %s
            """.formatted(longTermMemory);
    }
}