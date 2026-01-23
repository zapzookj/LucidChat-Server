package com.spring.aichat.domain.character;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "characters")
/**
 * AI 캐릭터 메타정보 엔티티
 * - baseSystemPrompt는 캐릭터의 불변 성격/금기사항(핵심)
 */
public class Character {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "character_id")
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Lob
    @Column(name = "base_system_prompt", nullable = false)
    private String baseSystemPrompt;

    @Column(name = "llm_model_name", nullable = false, length = 100)
    private String llmModelName;

    @Column(name = "tts_voice_id", length = 100)
    private String ttsVoiceId;

    @Column(name = "default_image_url", length = 500)
    private String defaultImageUrl;

    public Character(String name, String baseSystemPrompt, String llmModelName) {
        this.name = name;
        this.baseSystemPrompt = baseSystemPrompt;
        this.llmModelName = llmModelName;
    }
}
