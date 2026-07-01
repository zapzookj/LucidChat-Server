package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.character.Character;

public record CharacterAdminResponse(
    Long id,
    String name,
    String slug,
    String worldId,
    boolean storyAvailable,
    boolean theaterAvailable,
    boolean hidden
) {
    public static CharacterAdminResponse from(Character c) {
        return new CharacterAdminResponse(
            c.getId(), c.getName(), c.getSlug(),
            c.getWorldId() != null ? c.getWorldId().name() : null,
            c.isStoryAvailable(), c.isTheaterAvailable(), c.isHidden());
    }
}
