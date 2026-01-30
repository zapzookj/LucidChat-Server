package com.spring.aichat.domain.character;

import com.spring.aichat.config.DefaultCharacterProperties;
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

    @Column(name = "base_system_prompt", nullable = false, columnDefinition = "TEXT")
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

    /**
     * 기본 캐릭터 시드 값으로 동기화(업데이트)
     */
    public void applySeed(DefaultCharacterProperties props) {
        this.name = props.name();
        this.baseSystemPrompt = props.baseSystemPrompt();
        this.llmModelName = props.llmModelName();
        this.ttsVoiceId = props.ttsVoiceId();
        this.defaultImageUrl = props.defaultImageUrl();
    }
}
