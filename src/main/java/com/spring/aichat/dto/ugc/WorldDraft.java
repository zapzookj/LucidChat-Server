package com.spring.aichat.dto.ugc;

import java.util.List;

/**
 * [UGC 세계관 빌더] W1 편집 드래프트 — 잡의 {@code draft_world_json} 저장·API 노출 공용 모델.
 *
 * <p>EDIT_WAIT 진입 시 {@link StructuredWorld}에서 시딩되고, 유저 PATCH가 갱신하며,
 * 일러(W2)와 확정(W3)은 이 드래프트만 읽는다. API 계약은 camelCase(UgcDtos 관례).
 */
public record WorldDraft(
    String name,
    String intro,
    String lore,
    /** 콤마 결합 전 리스트 형태 유지 — 확정 시 결합 저장. */
    List<String> moodTags,
    /** W0 산출 썸네일 프롬프트 (유저 비노출·비편집 — 리롤 재현용). */
    String thumbnailPrompt,
    List<DraftLocation> locations
) {

    /**
     * @param backgroundPrompt LLM 제안 장소는 W0에서 채워짐. 유저 직접 추가 장소는 null —
     *                         일러 시작 시 LLM이 일괄 프롬프트화한다(유저 텍스트 직결 금지 원칙).
     */
    public record DraftLocation(
        String locationKey,
        String displayName,
        String description,
        String backgroundPrompt
    ) {}
}
