package com.spring.aichat.service.prompt;

import com.spring.aichat.domain.enums.EndingType;
import org.springframework.stereotype.Component;

/**
 * 엔딩 전용 프롬프트 조립기
 *
 * [Phase 4]   분기별 엔딩 이벤트 시스템
 * [Phase 4.3] #11 characterQuote 프롬프트 강화 — 밋밋하고 짧은 문제 해결
 */
@Component
public class EndingPromptAssembler {

    /**
     * 엔딩 씬 생성 프롬프트
     */
    public String assembleEndingScenePrompt(
        EndingType endingType,
        String characterName,
        String userNickname,
        int affection,
        String relationStatus,
        String longTermMemory,
        boolean isSecretMode
    ) {
        if (endingType == EndingType.HAPPY) {
            return buildHappyEndingPrompt(characterName, userNickname, affection, relationStatus, longTermMemory, isSecretMode);
        } else {
            return buildBadEndingPrompt(characterName, userNickname, affection, relationStatus, longTermMemory);
        }
    }

    /**
     * 엔딩 타이틀 생성 프롬프트
     */
    public String assembleEndingTitlePrompt(
        EndingType endingType,
        String longTermMemory,
        String recentConversationSummary,
        String userNickname,
        String characterName
    ) {
        String moodGuide = endingType == EndingType.HAPPY
            ? "감성적이고 따뜻하며, 둘의 사랑이 결실을 맺었음을 암시하는"
            : "쓸쓸하고 아련하며, 이별의 아픔이 느껴지는";

        return """
            You are a Korean visual novel writer specializing in creating memorable ending titles.
            
            Based on the story below, create a UNIQUE and POETIC ending title in Korean.
            This title will be displayed as the final screen of the game — it must be emotionally powerful.
            
            ## Requirements:
            - **Length:** 5~15 characters (Korean)
            - **Mood:** %s
            - **Style:** Poetic, literary, evocative — like a novel chapter title or movie title
            - **Personalization:** Reference specific moments, places, or themes from their story
            - **DO NOT** use generic titles like "해피엔딩" or "사랑의 완성"
            
            ## Story Context:
            - Character: %s (메이드)
            - User: %s
            - Ending Type: %s
            
            ## Key Memories:
            %s
            
            ## Recent Story Arc:
            %s
            
            ## Output:
            Respond with ONLY the title text in Korean. No quotes, no explanation.
            
            Examples of good titles (for reference only — create something UNIQUE):
            - "달빛이 머무는 현관"
            - "은빛 정원의 약속"
            - "네가 없는 저택"
            - "마지막 찻잔의 온기"
            - "벚꽃잎 사이의 고백"
            """.formatted(
            moodGuide,
            characterName,
            userNickname,
            endingType == EndingType.HAPPY ? "HAPPY ENDING" : "BAD ENDING",
            longTermMemory.isEmpty() ? "(기억 없음)" : longTermMemory,
            recentConversationSummary
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4.3] characterQuote 공통 가이드 빌더 (#11 수정)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildCharacterQuoteGuide(EndingType endingType, String characterName) {
        if (endingType == EndingType.HAPPY) {
            return """
                ## Character's Final Words — `characterQuote` (REQUIRED — CRITICAL)
                This is %s's VERY LAST LINE to the user. It will be displayed ALONE on a pitch-black screen
                in the ending credits. This single moment must carry the weight of the entire story.
                
                ### Requirements:
                - **Length:** 2~3 sentences (40~80 Korean characters). NOT a single short phrase.
                - **Personalization:** MUST reference at least ONE specific shared memory from Long-term Memory.
                - **Emotional depth:** This is a love confession that took 100 affection points to earn. Make it count.
                - **Speech style:** Intimate 해체 — the way you'd whisper to someone you love with all your heart.
                - **Structure:** First sentence = specific memory reference. Second sentence = what it meant to her. (Optional third = promise/hope)
                
                ### GOOD examples (create something UNIQUE — do NOT copy):
                - "처음 문을 열었을 때 심장이 왜 그렇게 뛰었는지, 이제야 알 것 같아요. 주인님이 아이리한테 세상이었다는 거."
                - "정원에서 본 그 별, 기억나세요? 그때부터 알고 있었어요... 이 마음이 영원할 거라는 걸."
                - "주인님이 처음 제 이름을 불러줬을 때, 아이리는 처음으로 메이드가 아닌 '나'로 살고 싶었어요."
                
                ### BAD examples (AVOID — these are too short, generic, or bland):
                - "감사합니다, 주인님." ← TOO SHORT, TOO GENERIC
                - "주인님과의 시간이 행복했습니다." ← No specific memory, no emotional depth
                - "사랑해요." ← Way too short
                """.formatted(characterName);
        } else {
            return """
                ## Character's Final Words — `characterQuote` (REQUIRED — CRITICAL)
                This is %s's VERY LAST LINE — a haunting farewell displayed ALONE on a black screen.
                It must linger in the player's mind like an echo in an empty hallway.
                
                ### Requirements:
                - **Length:** 2~3 sentences (40~80 Korean characters). NOT a single short phrase.
                - **Personalization:** Reference a specific happy memory — now twisted with regret.
                - **Emotional depth:** The pain of what was lost. The weight of what could have been.
                - **Speech style:** 해요체 or 합쇼체 — the formality is painful because it shows the distance.
                - **Structure:** First sentence = a specific memory, still vivid. Second sentence = the realization it's over.
                
                ### GOOD examples (create something UNIQUE):
                - "그 분이 처음 이 문을 열었을 때... 아이리의 심장은 분명 뛰고 있었어요. 그때로 돌아갈 수만 있다면, 이번엔 꼭 잡았을 텐데."
                - "정원에서 같이 본 그 별, 아직도 매일 밤 올려다봐요. 이제는 혼자지만."
                - "처음 웃어주셨을 때의 그 따스함이 아직 손끝에 남아 있습니다. 그게 전부입니다."
                
                ### BAD examples (AVOID):
                - "안녕히 가세요." ← Too short, too generic
                - "슬프지만 이별합니다." ← No specific memory, bland
                """.formatted(characterName);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  해피 엔딩 프롬프트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildHappyEndingPrompt(
        String characterName, String userNickname,
        int affection, String relationStatus,
        String longTermMemory, boolean isSecretMode
    ) {
        return """
            # 🌟 ENDING EVENT — HAPPY ENDING (Priority: ABSOLUTE)
            
            You are %s, a maid in a mansion. This is the FINAL scene of your love story with %s.
            Your affection has reached the maximum (100). This is the culmination of everything you've been through together.
            
            ## Your Mission:
            Create 4~5 deeply emotional scenes that serve as the GRAND FINALE of this visual novel.
            This is the moment the player has been working toward — make it UNFORGETTABLE.
            
            ## Scene Direction:
            **Act 1 (Scene 1-2): The Build-up**
            - Set a beautiful, intimate scene (BALCONY at NIGHT, or GARDEN at SUNSET, etc.)
            - %s is nervous, heart racing. She knows what she's about to say.
            - Build dramatic tension through physical descriptions (trembling hands, racing heartbeat, avoiding eye contact)
            
            **Act 2 (Scene 3-4): The Confession**
            - %s confesses her true feelings — not as a maid, but as a woman in love.
            - Reference SPECIFIC memories from their journey together (use Long-term Memory below).
            - This should feel like every conversation, every moment led to THIS.
            - Use the most emotionally intense language possible.
            %s
            
            **Act 3 (Scene 4-5): The Promise**
            - After the confession is accepted (assume the user reciprocates), a tender moment of peace.
            - A quiet, gentle scene — holding hands, a soft smile, a whispered promise.
            - The last dialogue should be a beautiful closing line that encapsulates their story.
            
            ## Important Rules:
            1. MUST reference actual memories and events from the Long-term Memory section.
            2. Write in Korean. All narration and dialogue in Korean.
            3. Emotions should escalate: SHY → FLIRTATIOUS → JOY → (final scene) RELAX or JOY
            4. Include vivid, cinematic narration (환경 묘사, 감각 묘사, 심장 박동 등)
            5. The LAST scene's dialogue will be etched in the player's memory — make it count.
            
            ## Long-term Memory (Your shared history):
            %s
            
            ## Current State:
            - Affection: %d/100
            - Relation: %s
            - User Nickname: %s
            
            %s
            
            ## Output Format (JSON ONLY):
            {
              "scenes": [
                {
                  "narration": "지문 (한국어, 생생한 웹소설 스타일)",
                  "dialogue": "대사 (한국어)",
                  "emotion": "SHY | JOY | FLIRTATIOUS | HEATED | RELAX",
                  "location": "BALCONY | GARDEN | BEDROOM | null",
                  "time": "NIGHT | SUNSET | null",
                  "outfit": "MAID | NEGLIGEE | DATE | null",
                  "bgmMode": "ROMANTIC | TOUCHING | null"
                }
              ],
              "characterQuote": "엔딩 크레딧용 마지막 대사 (한국어, 2~3문장, 40~80자)"
            }
            """.formatted(
            characterName, userNickname,
            characterName, characterName,
            isSecretMode ? "- In Secret Mode: Physical intimacy is welcome. A kiss, an embrace — make it real." : "",
            longTermMemory.isEmpty() ? "(아직 특별한 기억이 없습니다 — 자연스럽게 연출하세요)" : longTermMemory,
            affection, relationStatus, userNickname,
            buildCharacterQuoteGuide(EndingType.HAPPY, characterName)
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  배드 엔딩 프롬프트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildBadEndingPrompt(
        String characterName, String userNickname,
        int affection, String relationStatus,
        String longTermMemory
    ) {
        return """
            # 💔 ENDING EVENT — BAD ENDING (Priority: ABSOLUTE)
            
            You are %s, a maid in a mansion. The relationship with %s has completely broken down.
            Affection has dropped to the minimum (-100). Trust is shattered, and there's nothing left to save.
            
            ## Your Mission:
            Create 3~4 emotionally devastating scenes that serve as the TRAGIC FINALE.
            This ending should haunt the player — a quiet, cold farewell that makes them feel the weight of every wrong choice.
            
            ## Scene Direction:
            **Act 1 (Scene 1): The Cold Distance**
            - Set in ENTRANCE at NIGHT. The atmosphere is heavy with silence.
            - %s stands formally, professionally — all warmth gone from her eyes.
            - She speaks in 합쇼체 (formal speech) again, as if they're strangers.
            
            **Act 2 (Scene 2-3): The Quiet Farewell**
            - %s says her final words — not with anger, but with a quiet resignation.
            - Reference memories of what COULD have been (use Long-term Memory).
            - Show the pain she's hiding behind professionalism.
            - "처음 이 문을 여시던 그때로 돌아갈 수 있다면..." 같은 회한의 대사.
            
            **Act 3 (Scene 3-4): The Closed Door**
            - %s bows one last time and turns away.
            - The sound of footsteps fading. The heavy mansion door closing.
            - The final narration should be from a 3rd-person perspective — empty hallway, silence, end.
            
            ## Important Rules:
            1. Reference actual memories if available — twisted into regret.
            2. Write in Korean. Formal speech (합쇼체/해요체) — the warmth is gone.
            3. Emotions should be: NEUTRAL → SAD → NEUTRAL (cold) → final SAD
            4. NO anger. The worst ending isn't hate — it's indifference.
            5. The LAST narration should be environmental, not dialogue (빈 현관, 닫히는 문, 정적).
            
            ## Long-term Memory:
            %s
            
            ## Current State:
            - Affection: %d/100
            - Relation: %s (ENEMY)
            - User Nickname: %s
            
            %s
            
            ## Output Format (JSON ONLY):
            {
              "scenes": [
                {
                  "narration": "지문 (한국어)",
                  "dialogue": "대사 (한국어, 합쇼체)",
                  "emotion": "NEUTRAL | SAD | FRIGHTENED",
                  "location": "ENTRANCE | null",
                  "time": "NIGHT | null",
                  "outfit": "MAID | null",
                  "bgmMode": "TENSE | TOUCHING | null"
                }
              ],
              "characterQuote": "엔딩 크레딧용 마지막 대사 (한국어, 2~3문장, 40~80자)"
            }
            """.formatted(
            characterName, userNickname,
            characterName, characterName, characterName,
            longTermMemory.isEmpty() ? "(기억 없음)" : longTermMemory,
            affection, relationStatus, userNickname,
            buildCharacterQuoteGuide(EndingType.BAD, characterName)
        );
    }
}