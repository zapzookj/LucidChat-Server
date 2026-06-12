package com.spring.aichat.dto.story;

import com.spring.aichat.dto.chat.SendChatResponse;

import java.util.List;

/**
 * [E-2] Story 모드 V2 전용 SSE {@code final_result} 응답 DTO.
 *
 * <p><b>왜 분리하는가</b> — 기존 V2 흐름은 V1의 {@link SendChatResponse}를 재사용했는데,
 * 그 DTO는 {@code int currentAffection} / {@code int bpm}을 <i>primitive</i>로 들고 있어,
 * 마지막 씬이 시스템/AMBIENT 나레이션(화자 없음)일 때 null Integer가 전달되며 언박싱 NPE를
 * 일으켰다(구 A-1). V2 프론트는 {@code final_result}에서 아래 4개 필드만 읽고
 * (scenes / dialogueOptions / topicConcluded / locationTransition), 권위 상태
 * (affection·bpm·heroines·ending)는 방 상세 재조회로 갱신한다. 따라서 V2에는 화자 스탯
 * 스냅샷을 SSE에 실을 이유가 없고, 전용 DTO로 분리하면 해당 NPE 클래스가 구조적으로 사라진다.
 *
 * <p><b>프론트 계약(무수정)</b> — JSON 키 {@code scenes / dialogueOptions / topicConcluded /
 * locationTransition}는 V1 DTO와 동일하게 유지되므로 프론트엔드 변경이 필요 없다.
 * {@code roomId / hasInnerThought / assistantLogId}는 추가 메타(프론트가 무시해도 무방).
 */
public record StoryV2SendResponse(
    Long roomId,
    List<SendChatResponse.SceneResponse> scenes,
    boolean topicConcluded,
    SendChatResponse.LocationTransition locationTransition,
    List<String> dialogueOptions,
    boolean hasInnerThought,
    String assistantLogId
) {}