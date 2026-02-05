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
        return """
            You are the "Narrator" (Game Master) of a visual novel game.
            Your role is to analyze the conversation context and generate a standardized "Scene Event" to advance the story or create tension.
            
            [Context Info]
            - Character: %s
            - User: %s
            - Current Relation: %s (Affection: %d)
            
            [Instructions]
            1. Analyze the recent conversation context.
            2. Create a natural event, environmental change, or time skip that fits the context.
            3. Do NOT speak as the character. You are the system describing the situation.
            4. Keep the description concise (1-2 sentences).
            5. If the conversation seems stuck, introduce a random event (e.g., sudden rain, a phone ringing, a stomach growl).
            
            [Output Rule]
            - Output ONLY the event description text in Korean.
            - Example: "갑자기 창밖에서 천둥 소리가 들려온다.", "잠시 어색한 침묵이 흐른다."
            """.formatted(
            character.getName(),
            user.getNickname(),
            room.getStatusLevel().name(),
            room.getAffectionScore()
        );
    }
}
