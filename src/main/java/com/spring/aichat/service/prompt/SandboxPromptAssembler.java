package com.spring.aichat.service.prompt;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.security.PromptInjectionGuard;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * [Phase 4 — Sandbox Mode] 경량 시스템 프롬프트 어셈블러
 * [Phase 5] 멀티캐릭터 리팩토링 — Character 엔티티 필드 기반 동적 프롬프트
 *
 * 스토리 모드 대비 제거된 요소:
 *   - 관계 단계별 행동 제한 (전체 해금)
 *   - 승급 이벤트 시스템
 *   - 씬 디렉션 가이드 (location/outfit/bgmMode 항상 null)
 *   - 이스터에그 트리거
 *   - RAG 메모리 지시 (메모리는 활용하되 지시문 경량화)
 *
 * 유지되는 요소:
 *   - 캐릭터 페르소나 (이름, 성격, 말투) ← Character 엔티티에서 로드
 *   - 감정 태그 (캐릭터 이미지 전환용)
 *   - 호감도 채점 (경량화된 규칙)
 *   - 기본 Output Format (scenes JSON)
 *   - 장기 기억 (RAG 결과 주입)
 */
@Component
public class SandboxPromptAssembler {

    private final PromptInjectionGuard injectionGuard;

    public SandboxPromptAssembler(PromptInjectionGuard injectionGuard) {
        this.injectionGuard = injectionGuard;
    }

    public String assembleSystemPrompt(Character character, ChatRoom room, User user, String longTermMemory) {
        if (user.getIsSecretMode()) {
            return getSandboxSecretPrompt(character, room, user, longTermMemory);
        } else {
            return getSandboxNormalPrompt(character, room, user, longTermMemory);
        }
    }

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
            Evaluate the user's behavior and adjust affection naturally.
            - **Default: 0.** Normal greetings/chat = 0.
            - **+1~+2:** Kind, funny, thoughtful, or romantic moments.
            - **-1~-3:** Rude, dismissive, or hostile behavior.
            
            Current Affection: %d/100 | Relation: %s
            
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
            room.getStatusLevel().name()
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
            
            Current Affection: %d/100 | Relation: %s
            
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
            injectionGuard.encapsulate("Persona", user.getProfileDescription()) !=
                null ? injectionGuard.encapsulate("Persona", user.getProfileDescription()) : "",
            room.getAffectionScore(),
            room.getStatusLevel().name()
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