package com.spring.aichat.service.story;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.RelationPromotionEligibility;
import com.spring.aichat.domain.chat.RelationPromotionEligibilityRepository;
import com.spring.aichat.domain.enums.RelationStatus;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import com.spring.aichat.domain.chat.RelationStatusPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * [V2 Story] 관계 승급 LLM 자율 발동 서비스 (Q-9 패턴 응용)
 *
 * <p>V1의 *시스템 강제 임무* (promotion turn count + mood score 누적 게임) 패턴을 폐기.
 * 이중 게이트로 재설계:
 *
 * <pre>
 *   [1단계 자격 활성] 백엔드가 호감도 임계값 도달 감지 시 RelationPromotionEligibility 생성
 *                  → 디렉터 prompt에 RELATION PROMOTION ELIGIBILITY ACTIVE 인젝션
 *
 *   [2단계 LLM 발동] 디렉터가 *자연스러운 순간*에 응답 JSON system_updates.relation_transition
 *                  필드로 발동 → ChatRoomHeroine.promoteStatusLevel + eligibility.markTriggered
 *
 *   [페일세이프] 자격 활성 후 매 턴 deferredTurnCount++ → 30턴 초과 시 디렉터에 강제 권유
 *               (페일세이프 임계는 StoryDirectorPromptAssemblerV2에서 prompt에 추가)
 * </pre>
 *
 * <p>[임계값 — V1 RelationStatusPolicy 재활용]
 * - STRANGER → ACQUAINTANCE: affection ≥ 20
 * - ACQUAINTANCE → FRIEND:   affection ≥ 40
 * - FRIEND → LOVER:          affection ≥ 80
 *
 * <p>[멀티 히로인 처리]
 * 각 캐릭터별로 독립적인 자격 + 발동. 한 응답에 여러 캐릭터의 transition은 *불가* —
 * 응답 JSON의 relation_transition은 단일 객체 (멀티 히로인 동시 승급은 비현실적).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RelationPromotionService {

    private final RelationPromotionEligibilityRepository eligibilityRepository;
    private final ChatRoomHeroineRepository heroineRepository;

    // 임계값 — V1 RelationStatusPolicy 정렬 (운영 데이터로 조정 가능)
    private static final int THRESHOLD_TO_ACQUAINTANCE = 20;
    private static final int THRESHOLD_TO_FRIEND       = 40;
    private static final int THRESHOLD_TO_LOVER        = 80;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [1단계] 자격 활성 — 매 스탯 갱신 후 호출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 매 디렉터 응답 후 호감도 갱신 완료 시점에 호출.
     * 캐릭터별로 임계값 도달 여부 체크 → 신규 자격 생성.
     *
     * @return 새로 활성된 자격 수 (디버그/통계용)
     */
    @Transactional
    public int checkAndActivateEligibility(ChatRoom room) {
        if (!room.isStoryMode()) return 0;

        List<ChatRoomHeroine> heroines = heroineRepository.findByChatRoom_Id(room.getId());
        int activated = 0;

        for (ChatRoomHeroine h : heroines) {
            RelationStatus nextLevel = computeNextLevelIfThresholdHit(h);
            if (nextLevel == null) continue;

            // 이미 같은 자격 있는지 체크 (unique constraint이지만 중복 INSERT 회피)
            Optional<RelationPromotionEligibility> existing = eligibilityRepository
                .findByChatRoomIdAndCharacterIdAndNextLevel(room.getId(), h.getCharacter().getId(), nextLevel);
            if (existing.isPresent()) continue;

            RelationPromotionEligibility e = RelationPromotionEligibility
                .activate(room.getId(), h.getCharacter().getId(), nextLevel);
            eligibilityRepository.save(e);
            activated++;

            log.info("💗 [PROMOTION-ELIGIBILITY] Activated: roomId={}, charId={} ({} → {})",
                room.getId(), h.getCharacter().getId(), h.getStatusLevel(), nextLevel);
        }
        return activated;
    }

    /**
     * 캐릭터의 현재 단계와 호감도를 비교해 *다음 도달 가능 단계*를 반환.
     * 임계 미달이면 null.
     */
    private RelationStatus computeNextLevelIfThresholdHit(ChatRoomHeroine h) {
        int affection = h.getStatAffection();
        RelationStatus current = h.getStatusLevel();

        return switch (current) {
            case STRANGER ->
                affection >= THRESHOLD_TO_ACQUAINTANCE ? RelationStatus.ACQUAINTANCE : null;
            case ACQUAINTANCE ->
                affection >= THRESHOLD_TO_FRIEND ? RelationStatus.FRIEND : null;
            case FRIEND ->
                affection >= THRESHOLD_TO_LOVER ? RelationStatus.LOVER : null;
            case LOVER, ENEMY -> null;
        };
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2단계] LLM 자율 발동 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 디렉터 응답의 {@code system_updates.relation_transition} 처리.
     *
     * <p>가드:
     * - 자격 활성 안 됐으면 무시 + 경고 로그
     * - nextLevel mismatch (한 단계씩만 진전 허용)
     * - 이미 발동된 자격은 무시
     *
     * @param transition 디렉터 출력 — {characterId, from, to}
     * @return 발동 결과 — 성공 시 변경된 히로인 정보, 실패 시 null
     */
    @Transactional
    public PromotionResult processDirectorTrigger(ChatRoom room, RelationTransition transition) {
        if (transition == null) return null;
        if (!room.isStoryMode()) return null;

        Long charId = transition.characterId();
        RelationStatus toLevel = parseStatus(transition.to());

        // [Bug-Fix] LLM이 relation_transition을 문자열로 주거나 character_id를 누락해도 크래시 없이 복원.
        //   도메인상 방당 활성(미발동) 자격은 최대 1건이므로, 단일 자격이면 그걸로 characterId/toLevel을 채운다.
        if (charId == null || toLevel == null) {
            List<RelationPromotionEligibility> pending = eligibilityRepository
                .findByChatRoomIdAndTriggeredFalse(room.getId());
            if (pending.size() == 1) {
                RelationPromotionEligibility only = pending.get(0);
                if (charId == null) charId = only.getCharacterId();
                if (toLevel == null) toLevel = only.getNextLevel();
            }
        }
        if (charId == null || toLevel == null) {
            log.warn("💗 [PROMOTION] Unresolvable transition: roomId={}, raw={}", room.getId(), transition);
            return null;
        }

        // 활성 자격 조회
        Optional<RelationPromotionEligibility> eOpt = eligibilityRepository
            .findByChatRoomIdAndCharacterIdAndTriggeredFalse(room.getId(), charId);
        if (eOpt.isEmpty()) {
            log.warn("💗 [PROMOTION] LLM attempted trigger without eligibility: roomId={}, charId={}",
                room.getId(), charId);
            return null;
        }

        RelationPromotionEligibility eligibility = eOpt.get();
        if (eligibility.getNextLevel() != toLevel) {
            log.warn("💗 [PROMOTION] Level mismatch: expected={}, got={}, roomId={}",
                eligibility.getNextLevel(), toLevel, room.getId());
            return null;
        }

        // 히로인 단계 갱신 + 자격 발동 마킹
        ChatRoomHeroine h = heroineRepository
            .findByChatRoom_IdAndCharacter_Id(room.getId(), charId)
            .orElse(null);
        if (h == null) {
            log.warn("💗 [PROMOTION] Heroine row not found: roomId={}, charId={}",
                room.getId(), charId);
            return null;
        }

        RelationStatus from = h.getStatusLevel();
        h.promoteStatusLevel(toLevel);
        h.updateDynamicRelationTag(RelationStatusPolicy.getDisplayName(toLevel));
        eligibility.markTriggered();

        log.info("💗 [PROMOTION-TRIGGER] Activated: roomId={}, charId={} ({} → {})",
            room.getId(), charId, from, toLevel);

        return new PromotionResult(charId, from, toLevel, h.getCharacter().getName());
    }

    public record RelationTransition(Long characterId, String from, String to) {}
    public record PromotionResult(Long characterId, RelationStatus from, RelationStatus to, String characterName) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  매 턴 페일세이프 카운터 증가
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 매 디렉터 응답 종료 시 호출 — 활성 자격들의 deferred 카운트 증가.
     * 디렉터가 발동을 미루는 만큼 카운트 증가 → 30턴 초과 시 페일세이프 prompt 발동.
     */
    @Transactional
    public void incrementDeferredCounters(Long roomId) {
        List<RelationPromotionEligibility> pending = eligibilityRepository
            .findByChatRoomIdAndTriggeredFalse(roomId);
        pending.forEach(RelationPromotionEligibility::incrementDeferred);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스토리 초기화 시 일괄 삭제
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void clearEligibilitiesForRoom(Long roomId) {
        eligibilityRepository.deleteByChatRoomId(roomId);
        log.info("🗑️ [PROMOTION] Cleared room eligibilities: roomId={}", roomId);
    }

    private RelationStatus parseStatus(String s) {
        if (s == null) return null;
        try {
            return RelationStatus.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}