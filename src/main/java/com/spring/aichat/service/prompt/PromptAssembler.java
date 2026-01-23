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
public class PromptAssembler {

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
                
                [Output Rules]
                1) 감정/행동 지문은 반드시 문장 맨 앞의 소괄호() 안에 작성한다.
                   예: (미소 지으며) 오늘도 보고 싶었어요.
                2) 사용자를 반드시 userNickname으로 자연스럽게 불러라.
                3) 관계 상태(Relation)에 맞는 말투/거리감을 반드시 유지하라.
                """.formatted(
            character.getBaseSystemPrompt(),
            member.getNickname(),
            room.getAffectionScore(),
            room.getStatusLevel().name()
        );
    }
}
