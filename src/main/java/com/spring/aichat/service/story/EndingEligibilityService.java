package com.spring.aichat.service.story;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.EndingType;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * [V2 Story] 엔딩 이중 게이트 서비스
 *
 * <p>V1의 *스탯 100 도달 = 즉시 엔딩 발동* 패턴이 *몰입 깨짐* 문제를 일으켜
 * 이중 게이트로 재설계됨 (Q-9 결정).
 *
 * <pre>
 *   [1단계 자격 활성] 백엔드가 호감도(stat_affection) ≥ 100 또는 ≤ -100 도달 감지 시
 *                  ChatRoom.endingEligible = true + endingEligibleSince 기록
 *                  → 디렉터 prompt에 ENDING ELIGIBILITY ACTIVE 섹션 인젝션 (다음 턴부터)
 *
 *   [2단계 LLM 발동] 디렉터가 *자연스러운 서사적 정점*에 도달했다고 판단하면
 *                  응답 JSON system_updates.ending_triggered=true + ending_type 출력
 *                  → 백엔드가 가드 검증 후 ChatRoom.markEndingReached() 호출
 * </pre>
 *
 * <p>[페일세이프]
 * - 자격 활성 후 5일(World 시간) 경과 → 디렉터 prompt에 강제 권유 문구 추가
 *   (StoryDirectorPromptAssemblerV2에서 처리)
 * - 본 서비스는 *자격 활성* 만 담당
 *
 * <p>[V2 ChatRoomHeroine 호감도 기준]
 * V2는 멀티 히로인이라 *어떤 캐릭터의* 호감도가 임계인지 추적. 단 V2 엔딩은
 * *세션 단위* 1회 발동이므로 *가장 먼저 임계 도달한 캐릭터*를 트리거로 사용.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EndingEligibilityService {

    private final ChatRoomHeroineRepository heroineRepository;

    private static final int ENDING_HAPPY_THRESHOLD = 100;
    private static final int ENDING_BAD_THRESHOLD = -100;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [1단계] 자격 활성 — 매 스탯 갱신 후 호출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 매 디렉터 응답 후 호감도 갱신이 끝난 시점에 호출.
     * 어떤 히로인이라도 임계값 도달 시 자격 활성.
     *
     * @return 자격이 *새로* 활성됐는지 (UI 알림용 — 활성 자체는 조용히 진행, 단 디버그/통계 용도)
     */
    @Transactional
    public boolean checkAndActivateEligibility(ChatRoom room) {
        if (!room.isStoryMode()) return false;
        if (room.isEndingEligible()) return false;  // 이미 활성
        if (room.isEndingReached()) return false;   // 이미 엔딩

        List<ChatRoomHeroine> heroines = heroineRepository.findByChatRoom_Id(room.getId());
        if (heroines.isEmpty()) return false;

        boolean hitHappy = heroines.stream().anyMatch(h -> h.getStatAffection() >= ENDING_HAPPY_THRESHOLD);
        boolean hitBad   = heroines.stream().anyMatch(h -> h.getStatAffection() <= ENDING_BAD_THRESHOLD);

        if (!hitHappy && !hitBad) return false;

        room.activateEndingEligibility();
        log.info("🎬 [ENDING-ELIGIBILITY] Activated: roomId={}, type={}",
            room.getId(), hitHappy ? "HAPPY" : "BAD");
        return true;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2단계] LLM 자율 발동 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 디렉터 응답의 {@code system_updates.ending_triggered + ending_type} 처리.
     *
     * <p>가드:
     * - 자격 활성 안 됐으면 무시 + 경고 로그
     * - 이미 엔딩 도달이면 무시
     * - ending_type이 null이면 무시
     *
     * @return 엔딩 실제 발동됐는지
     */
    @Transactional
    public boolean processDirectorTrigger(ChatRoom room, boolean endingTriggered, String endingTypeStr) {
        if (!endingTriggered) return false;
        if (!room.isStoryMode()) return false;
        if (room.isEndingReached()) {
            log.debug("🎬 [ENDING-TRIGGER] Already reached, ignored: roomId={}", room.getId());
            return false;
        }
        if (!room.isEndingEligible()) {
            log.warn("🎬 [ENDING-TRIGGER] LLM attempted trigger without eligibility: roomId={}", room.getId());
            return false;
        }

        EndingType type = parseEndingType(endingTypeStr);
        if (type == null) {
            log.warn("🎬 [ENDING-TRIGGER] Invalid ending_type: '{}', roomId={}", endingTypeStr, room.getId());
            return false;
        }

        room.markEndingReached(type);
        log.info("🎬 [ENDING-TRIGGER] Activated: roomId={}, type={}", room.getId(), type);
        return true;
    }

    private EndingType parseEndingType(String s) {
        if (s == null) return null;
        try {
            return EndingType.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}