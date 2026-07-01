package com.spring.aichat.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.core.JsonParser;
import com.spring.aichat.domain.enums.RelationStatus;

import java.io.IOException;

import java.util.List;
import java.util.Map;

/**
 * [V2 Story] 디렉터 응답 JSON 스키마.
 *
 * <p>V1 {@link AiJsonOutput}과 *별도* 작성 (구조 차이 큼). 단 {@link AiJsonOutput.StatChanges}는
 * 8축 스탯 그대로라 V2에서도 *재활용*.
 *
 * <p>[SceneStreamExtractor 호환]
 * {@code scenes} 배열 형태 유지 — V2는 4~5 씬. V1의 SceneStreamExtractor가
 * {@code "scenes"} 키 + 첫 객체 추출 패턴이라 *무수정 작동*.
 *
 * <p>[V2 스키마 핵심]
 * <pre>
 *   scenes          — 4~5 원소 배열 (최소 3, 최대 5). V1 outfit 필드 없음. V2 신규 필드 추가.
 *   system_updates  — V1의 flat 구조를 nested로 그룹화. stat_changes는 캐릭터별 Map.
 *                     *응답 전체 단위* — 씬별로 분리 안 함.
 *   memory_delta    — World-level + 캐릭터별 메모리 1줄 누적 (*응답 전체 단위*)
 *   incoming_messages — 오프스크린 알림 (디렉터 자율)
 *   dialogue_options  — 디렉터 자율 선택지 (유저 피로도 완화)
 * </pre>
 */
public record AiJsonOutputV2(
    List<SceneV2> scenes,
    @JsonProperty("system_updates") SystemUpdates systemUpdates,
    @JsonProperty("memory_delta") MemoryDelta memoryDelta,
    @JsonProperty("incoming_messages") List<IncomingMessage> incomingMessages,
    @JsonProperty("dialogue_options") List<String> dialogueOptions,
    @JsonProperty("narrative_threads") List<NarrativeThread> narrativeThreads
) {

    /** V2는 4~5 씬 배열. 첫 씬은 SSE first_scene 발사용. */
    public SceneV2 firstScene() {
        return (scenes != null && !scenes.isEmpty()) ? scenes.get(0) : null;
    }

    /** 마지막 씬 — V1 패턴(lastBgm/lastEmotion 추출)과 V2의 *현재 응답 화자* 결정 기준. */
    public SceneV2 lastScene() {
        return (scenes != null && !scenes.isEmpty()) ? scenes.get(scenes.size() - 1) : null;
    }

    public int sceneCount() {
        return scenes == null ? 0 : scenes.size();
    }

    public boolean hasIncomingMessages() {
        return incomingMessages != null && !incomingMessages.isEmpty();
    }

    public boolean hasDialogueOptions() {
        return dialogueOptions != null && !dialogueOptions.isEmpty();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Scene (V2 — outfit 없음, 동적 장소·일러스트 hint 포함)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record SceneV2(
        String speaker,
        String narration,
        String dialogue,
        String emotion,
        @JsonProperty("inner_thought") String innerThought,
        /** 유저 위치 변경 시 새 location_key (WorldLocation 참조). null이면 위치 변경 없음. */
        @JsonProperty("location_change") String locationChange,
        @JsonProperty("new_dynamic_location") NewDynamicLocation newDynamicLocation,
        @JsonProperty("illustration_scene_hint") String illustrationSceneHint
    ) {
        public boolean hasInnerThought() {
            return innerThought != null && !innerThought.isBlank();
        }

        public boolean hasLocationChange() {
            return locationChange != null && !locationChange.isBlank();
        }

        public boolean hasNewDynamicLocation() {
            return newDynamicLocation != null && newDynamicLocation.name() != null
                && !newDynamicLocation.name().isBlank();
        }

        public boolean hasIllustrationSceneHint() {
            return illustrationSceneHint != null && !illustrationSceneHint.isBlank();
        }
    }

    public record NewDynamicLocation(
        String name,
        @JsonProperty("canonical_key") String canonicalKey,
        String description
    ) {
        public boolean hasCanonicalKey() {
            return canonicalKey != null && !canonicalKey.isBlank();
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  System Updates
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record SystemUpdates(
        @JsonProperty("topic_concluded") Boolean topicConcluded,
        /**
         * 캐릭터별 스탯 변화 — key는 character_id를 String으로.
         * V1 {@link AiJsonOutput.StatChanges} record 그대로 재활용.
         */
        @JsonProperty("stat_changes") Map<String, AiJsonOutput.StatChanges> statChanges,
        @JsonProperty("character_movements") List<CharacterMovement> characterMovements,
        @JsonProperty("time_advance") TimeAdvance timeAdvance,
        @JsonProperty("bgm_mode") String bgmMode,
        @JsonProperty("ending_triggered") Boolean endingTriggered,
        @JsonProperty("ending_type") String endingType,
        @JsonProperty("relation_transition") RelationTransition relationTransition,
        /**
         * [UX3] 유저에 대한 캐릭터의 *누적 인상* (5~10턴 주기, LLM 자율 갱신).
         * scenes[].inner_thought(매 응답, 그 순간의 숨은 속마음)와 성격이 다름 — 상태창 INNER THOUGHT의 단일 소스.
         */
        @JsonProperty("user_impressions") List<UserImpression> userImpressions
    ) {
        public boolean isTopicConcluded() {
            return Boolean.TRUE.equals(topicConcluded);
        }

        public boolean isEndingTriggered() {
            return Boolean.TRUE.equals(endingTriggered);
        }

        public boolean hasTimeAdvance() {
            return timeAdvance != null && (timeAdvance.days() != null && timeAdvance.days() > 0
                || (timeAdvance.dayPart() != null && !timeAdvance.dayPart().isBlank()));
        }

        public boolean hasCharacterMovements() {
            return characterMovements != null && !characterMovements.isEmpty();
        }

        public boolean hasUserImpressions() {
            return userImpressions != null && !userImpressions.isEmpty();
        }
    }

    public record CharacterMovement(
        @JsonProperty("character_id") @JsonDeserialize(using = LenientLongDeserializer.class) Long characterId,
        @JsonProperty("location_key") String locationKey
    ) {}

    public record TimeAdvance(
        Integer days,
        @JsonProperty("day_part") String dayPart
    ) {}

    public record RelationTransition(
        @JsonProperty("character_id") @JsonDeserialize(using = LenientLongDeserializer.class) Long characterId,
        String from,
        String to
    ) {}

    /** [UX3] 유저에 대한 누적 인상 — 캐릭터 상태창 INNER THOUGHT 소스. */
    public record UserImpression(
        @JsonProperty("character_id") @JsonDeserialize(using = LenientLongDeserializer.class) Long characterId,
        String impression
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Memory Delta
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record MemoryDelta(
        String world,
        @JsonProperty("by_character") Map<String, String> byCharacter
    ) {
        public boolean hasWorld() {
            return world != null && !world.isBlank();
        }

        public boolean hasCharacterMemories() {
            return byCharacter != null && !byCharacter.isEmpty();
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Incoming Messages (오프스크린 알림)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record IncomingMessage(
        @JsonProperty("from_character_id") @JsonDeserialize(using = LenientLongDeserializer.class) Long fromCharacterId,
        String content
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [D-5] Narrative Threads (서사 나침반)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 디렉터가 보고하는 서사 떡밥(thread) 델타. id로 upsert.
     * status: OPEN(새로 열림/열림 유지) | ADVANCED(진행됨) | RESOLVED(해소).
     * label은 한 줄 설명, note는 선택적 디렉터 메모.
     */
    public record NarrativeThread(
        String id,
        String label,
        String status,
        String note
    ) {}

    /**
     * [Bug-Fix] LLM이 character_id 필드에 숫자 대신 이름("로제타")을 넣어도
     * 응답 전체 파싱이 깨지지 않도록 — 숫자면 Long, 그 외(이름 등)는 null(해당 항목 스킵).
     */
    static class LenientLongDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
            String v = parser.getValueAsString();
            if (v == null || v.isBlank()) return null;
            try { return Long.parseLong(v.trim()); }
            catch (NumberFormatException e) { return null; }
        }
    }
}