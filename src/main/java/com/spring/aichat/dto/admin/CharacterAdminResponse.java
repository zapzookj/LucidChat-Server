package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.character.Character;

public record CharacterAdminResponse(
    Long id,
    String name,
    String slug,
    String worldId,
    boolean storyAvailable,
    boolean theaterAvailable,
    boolean hidden,
    /**
     * [E-6.4] OFFICIAL | UGC. 어드민 SPA가 '공개 철회' 액션의 대상 여부를 판정하는 데 쓴다 —
     * {@code POST /admin/characters/ugc/{id}/unpublish}는 UGC 전용이다.
     */
    String source,
    /**
     * [E-6.4] PUBLIC | PENDING_PUBLIC | PRIVATE.
     * 이 두 필드가 없어서 어드민 SPA에 공개 철회 호출처가 <b>0건</b>이었다 — 서버에는 엔드포인트가
     * 완비돼 있는데(감사로그·소유자 알림 포함) 화면이 대상을 가려낼 수 없어 부적절 공개 UGC를
     * UI로 내릴 수 없었다. 응답 필드 추가는 순수 가산이라 구 SPA와 호환된다.
     */
    String visibility
) {
    public static CharacterAdminResponse from(Character c) {
        return new CharacterAdminResponse(
            c.getId(), c.getName(), c.getSlug(),
            c.getWorldId() != null ? c.getWorldId().name() : null,
            c.isStoryAvailable(), c.isTheaterAvailable(), c.isHidden(),
            c.getSource() != null ? c.getSource().name() : null,
            c.getVisibility() != null ? c.getVisibility().name() : null);
    }
}
