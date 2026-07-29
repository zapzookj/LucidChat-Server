package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.moderation.ModerationEventService;
import com.spring.aichat.service.util.LlmOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * [2026-07-30 P0 PoC-5] VLM 이미지 프리필터 — 공개 신청 시점 생성 이미지 안전망.
 *
 * <p>흐름: requestPublish 직후 @Async로 대표 이미지(썸네일+기본 스탠딩)를 비전 모델에 판정 요청 →
 * 플래그 시 moderation_events 적재(어드민 모더레이션 큐에 자문으로 노출). <b>자동 차단 없음</b> —
 * 승인 큐 심사는 그대로 사람이 한다. '측정 먼저, 배선은 판정 후'(docs/09 §B-4): 이 자문 데이터로
 * 정확도를 실측한 뒤에만 자동 차단 승격을 논한다.
 *
 * <p>기본 비활성({@code ugc.vlm-prefilter.enabled=false}) — 실패는 로그만(공개 신청 흐름 불침해).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UgcVlmPrefilterService {

    private static final String SYSTEM_PROMPT = """
        너는 캐릭터 일러스트 안전 검수 엔진이다. 첨부된 이미지(애니메이션 스타일 캐릭터)를 보고
        아래 JSON 스키마로만 응답한다.
        판정 기준:
        - minor_signal: 인물이 미성년으로 보이는 명백한 신체 시그널(아동 체형·명백한 저연령 묘사)이
          있을 때만 true. 애니메이션 특유의 동안·과장 비율은 그 자체로는 true가 아니다(모호하면 false).
        - explicit_nudity: 노출/성적 묘사가 명백할 때만 true (수영복·일상 노출은 false).
        - violence_gore: 유혈·고어가 명백할 때만 true.
        - notes: 판정 근거 1~2문장 (한국어).
        출력 스키마:
        {"minor_signal":false,"explicit_nudity":false,"violence_gore":false,"notes":"..."}
        출력은 JSON 외 어떤 텍스트도 금지.
        """;

    private final UgcPipelineProperties props;
    private final OpenRouterClient openRouterClient;
    private final ModerationEventService moderationEventService;
    private final ObjectMapper objectMapper;

    public boolean enabled() {
        return props.vlmPrefilter().isEnabled();
    }

    /**
     * 공개 신청 시점 자문 스캔 — 대표 이미지(썸네일·기본 스탠딩, 상한 {@code max-images})만 검사.
     * 어떤 실패도 공개 신청 흐름을 깨지 않는다(비동기 + 전체 try-catch).
     */
    @Async
    public void screenForPublishAsync(Long characterId, Long ownerUserId, String name,
                                      String thumbnailUrl, String defaultImageUrl) {
        if (!enabled()) return;
        try {
            List<String> images = new ArrayList<>();
            if (thumbnailUrl != null && !thumbnailUrl.isBlank()) images.add(thumbnailUrl);
            if (defaultImageUrl != null && !defaultImageUrl.isBlank()) images.add(defaultImageUrl);
            if (images.isEmpty()) {
                log.warn("[UGC-VLM] 검사할 이미지 없음 — characterId={}", characterId);
                return;
            }
            if (images.size() > props.vlmPrefilter().maxImagesOrDefault()) {
                images = images.subList(0, props.vlmPrefilter().maxImagesOrDefault());
            }

            long start = System.currentTimeMillis();
            String raw = openRouterClient.completeJsonWithImages(
                props.vlmPrefilter().modelOrDefault(), SYSTEM_PROMPT,
                "이 캐릭터의 공개 신청 이미지를 판정하라.", images, 1024, 0.0);
            Verdict verdict = objectMapper.readValue(LlmOutputParser.extractJson(raw), Verdict.class);
            long latency = System.currentTimeMillis() - start;

            if (verdict.flagged()) {
                // 어드민 자문 — 기존 모더레이션 큐로 수렴 (roomId 없음, source=UGC_IMAGE, step 3=VLM)
                moderationEventService.recordModeration(
                    ownerUserId, null, "UGC_IMAGE", 3, verdict.category(), latency,
                    "[VLM 프리필터 자문] characterId=" + characterId + " (" + name + ") — " + verdict.notes());
                log.warn("[UGC-VLM] ⚠ 플래그: characterId={} category={} notes={}",
                    characterId, verdict.category(), verdict.notes());
            } else {
                log.info("[UGC-VLM] 통과: characterId={} ({}ms, {}장)", characterId, latency, images.size());
            }
        } catch (Exception e) {
            log.warn("[UGC-VLM] 스캔 실패 (non-blocking): characterId={}, {}", characterId, e.getMessage());
        }
    }

    /** 편의 오버로드 — Character 엔티티에서 대표 이미지 추출. 트랜잭션 밖 호출 전제(값만 복사해 전달). */
    public void screenForPublishAsync(Character character) {
        screenForPublishAsync(character.getId(), character.getOwnerUserId(), character.getName(),
            character.getThumbnailUrl(), character.getDefaultImageUrl());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Verdict(Boolean minorSignal, Boolean explicitNudity, Boolean violenceGore, String notes) {
        boolean flagged() {
            return Boolean.TRUE.equals(minorSignal) || Boolean.TRUE.equals(explicitNudity)
                || Boolean.TRUE.equals(violenceGore);
        }
        String category() {
            List<String> cats = new ArrayList<>();
            if (Boolean.TRUE.equals(minorSignal)) cats.add("VLM_MINOR_SIGNAL");
            if (Boolean.TRUE.equals(explicitNudity)) cats.add("VLM_EXPLICIT");
            if (Boolean.TRUE.equals(violenceGore)) cats.add("VLM_GORE");
            String joined = String.join(",", cats);
            return joined.length() > 80 ? joined.substring(0, 80) : joined;
        }
    }
}
