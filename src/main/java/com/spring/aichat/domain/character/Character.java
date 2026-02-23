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
 *
 * - baseSystemPrompt: 캐릭터의 불변 성격/금기사항(핵심)
 *
 * [Phase 4.5] 로비 표시용 필드 추가
 * - tagline:       한 줄 요약 (예: "상냥한 저택의 메이드")
 * - thumbnailUrl:  카루셀 카드용 캐릭터 썸네일 이미지
 * - description:   캐릭터 상세 설명 (모드 선택 화면에서 표시)
 * - storyAvailable: 스토리 모드 지원 여부 (프롬프트/에셋 준비 완료 시 true)
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4.5] 로비 표시용 필드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "tagline", length = 100)
    private String tagline;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "story_available", nullable = false)
    private boolean storyAvailable = true;

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
        // [Phase 4.5] 로비 필드 시드 — null이 아닌 경우에만 덮어쓰기
        if (props.tagline() != null) this.tagline = props.tagline();
        if (props.thumbnailUrl() != null) this.thumbnailUrl = props.thumbnailUrl();
        if (props.description() != null) this.description = props.description();
    }
}