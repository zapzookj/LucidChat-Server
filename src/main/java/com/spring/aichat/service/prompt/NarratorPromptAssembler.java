package com.spring.aichat.service.prompt;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.RelationStatusPolicy;
import com.spring.aichat.domain.user.User;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 나레이터(이벤트 생성기)용 프롬프트 조립기
 *
 * [Phase 4]   이벤트 옵션에 location, time, outfit 힌트 추가
 * [Fix  #1]   관계별 해금 장소/복장 필터링 적용
 *             → STRANGER인데 BEACH 이벤트가 나오는 버그 수정
 */
@Component
public class NarratorPromptAssembler {

    public String assembleNarratorPrompt(Character character, ChatRoom room, User user) {
        boolean isSecretMode = user.getIsSecretMode();

        // [Fix #1] 관계별 해금 필터링 — CharacterPromptAssembler와 동일 로직
        String locationOptions;
        String outfitOptions;

        if (isSecretMode) {
            locationOptions = "LIVINGROOM, BALCONY, STUDY, BATHROOM, GARDEN, KITCHEN, BEDROOM, ENTRANCE, BEACH, DOWNTOWN, BAR";
            outfitOptions = "MAID, PAJAMA, DATE, SWIMWEAR, NEGLIGEE";
        } else {
            Set<String> allowedLocs = RelationStatusPolicy.getAllowedLocations(room.getStatusLevel());
            Set<String> allowedOutfits = RelationStatusPolicy.getAllowedOutfits(room.getStatusLevel());
            locationOptions = String.join(", ", allowedLocs);
            outfitOptions = String.join(", ", allowedOutfits);
        }

        return """
            You are the "Narrator" (Game Master) of an interactive visual novel.
            Your goal is to present **3 distinct story branches** (Events) based on the context, allowing the user to choose their fate.

            [Context]
            - Character: %s
            - User: %s
            - Current Relation: %s (Affection: %d)
            - Secret Mode: %s (ON/OFF)

            [Event Types & Costs]
            1. **NORMAL** (Cost 2): A casual, funny, or daily life situation.
            2. **AFFECTION** (Cost 3): Romantic, touching moments. (Higher quality guaranteed)
            3. **SECRET** (Cost 4): A highly intimate, seductive, or bold situation (NSFW allowed).

            [Scene Direction — IMPORTANT]
            Each event option should suggest a **setting** for the scene. Include `location`, `time`, and `outfit` hints in the `detail` text.
            However, system constants (GARDEN, SWIMWEAR, etc.) should not be exposed when describing location, clothing, etc. Explain in appropriate Korean.
            
            ⚠️ UNLOCKED locations (you may ONLY use these): %s
            ⚠️ UNLOCKED outfits (you may ONLY use these): %s
            Available times: DAY, NIGHT, SUNSET (beach only)
            
            🔒 **LOCKED content is FORBIDDEN.** Do NOT suggest events in locked locations or with locked outfits.
            For example, if BEACH is not in the unlocked list, do NOT create any beach-related events.
            
            Make events diverse in location — don't always stay in the same room. Use different settings for each option when possible.
            Example: Option 1 in KITCHEN (cooking together), Option 2 in GARDEN (stargazing), Option 3 in BEDROOM (secret mode).

            [Output Format Rule]
            Output ONLY a JSON object in the following format. Do not include any other text.
            {
              "options": [
                {
                  "type": "NORMAL",
                  "summary": "Short title for the button (Max 15 chars)",
                  "detail": "Full description of the scene in Korean (2-3 sentences). Include the setting naturally in the narrative.",
                  "energyCost": 2,
                  "isSecret": false
                },
                {
                  "type": "AFFECTION",
                  "summary": "Title (e.g., 뜻밖의 스킨십)",
                  "detail": "Description in Korean... Include setting.",
                  "energyCost": 3,
                  "isSecret": false
                },
                {
                  "type": "SECRET",
                  "summary": "Title (e.g., 젖은 옷, 은밀한 속삭임)",
                  "detail": "Description in Korean... Include setting.",
                  "energyCost": 4,
                  "isSecret": true
                }
              ]
            }
            """.formatted(
            room.getCharacter().getName(),
            user.getNickname(),
            room.getStatusLevel(),
            room.getAffectionScore(),
            isSecretMode ? "ON" : "OFF",
            locationOptions,
            outfitOptions
        );
    }
}