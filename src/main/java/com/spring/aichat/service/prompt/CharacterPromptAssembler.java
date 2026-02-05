package com.spring.aichat.service.prompt;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.user.User;
import org.springframework.stereotype.Component;

/**
 * 시스템 프롬프트(동적) 조립기
 * - base_system_prompt + 관계/호감도 상태 + 출력 규칙(지문 괄호)
 */
@Component
public class CharacterPromptAssembler {

    public String assembleSystemPrompt(Character character, ChatRoom room, User member) {

        // 명세 핵심: base_system_prompt + affection_score/status_level 주입 :contentReference[oaicite:10]{index=10}
        // 명세 핵심: "지문은 반드시 소괄호() 안에 넣어라" :contentReference[oaicite:11]{index=11}
        return """
                %s
                
                [User Profile]
                - userNickname: %s
                
                [Current State]
                - User Affection: %d/100
                - Relation: %s
                
                [Output Format Rules]
                You MUST output the response in the following JSON format ONLY. Do not output any other text.
                The response should be a JSON Object containing a "scenes" list and an "affection_change" integer.
                
                {
                  "scenes": [
                    {
                      "narration": "Write the character's action or facial expression here (e.g., 부드럽게 미소 지으며 고개를 끄덕인다).",
                      "dialogue": "Write the character's spoken line here.",
                      "emotion": "Choose one from [NEUTRAL, JOY, SAD, ANGRY, SHY, SURPRISE, PANIC, DISGUST, RELAX]"
                    }
                  ],
                  "affection_change": Integer value between -5 and 5 based on how much the user's message pleased or annoyed the character.
                }
                
                [Instruction for Scenes]
                - Split the response into multiple scenes if the character does multiple actions or has a long pause.
                - Ensure 'emotion' matches the dialogue and narration.
                """.formatted(
            character.getBaseSystemPrompt(),
            member.getNickname(),
            room.getAffectionScore(),
            room.getStatusLevel().name()
        );
    }
}
