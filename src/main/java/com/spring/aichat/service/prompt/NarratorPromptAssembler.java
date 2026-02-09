package com.spring.aichat.service.prompt;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.user.User;
import org.springframework.stereotype.Component;

/**
 * 나레이터(이벤트 생성기)용 프롬프트 조립기
 */
@Component
public class NarratorPromptAssembler {

    public String assembleNarratorPrompt(Character character, ChatRoom room, User user) {
        boolean isSecretMode = user.getIsSecretMode();

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

            [Output Format Rule]
            Output ONLY a JSON object in the following format. Do not include any other text.
            {
              "options": [
                {
                  "type": "NORMAL",
                  "summary": "Short title for the button (Max 15 chars)",
                  "detail": "Full description of the scene in Korean (2-3 sentences). This will be displayed if selected.",
                  "energyCost": 2,
                  "isSecret": false
                },
                {
                  "type": "AFFECTION",
                  "summary": "Title (e.g., 뜻밖의 스킨십)",
                  "detail": "Description in Korean...",
                  "energyCost": 3,
                  "isSecret": false
                },
                {
                  "type": "SECRET",
                  "summary": "Title (e.g., 젖은 옷, 은밀한 속삭임)",
                  "detail": "Description in Korean...",
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
            isSecretMode ? "ON" : "OFF"
        );
    }
}
