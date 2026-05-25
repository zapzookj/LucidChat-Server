package com.spring.aichat.service.story;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.character.CharacterRoutine;
import com.spring.aichat.domain.character.CharacterRoutineRepository;
import com.spring.aichat.domain.enums.DayPart;
import com.spring.aichat.domain.heroine.CharacterPresence;
import com.spring.aichat.domain.heroine.CharacterPresenceRepository;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * [V2 Story] 위치 기반 화자 라우팅 서비스
 *
 * <p>V2의 핵심 메커니즘 중 하나. 매 턴 유저 입력에 대해 *어느 캐릭터가 화자가 될지*
 * 결정. 결과는 {@link RoutingResult}에 담겨 {@code ChatStreamService}가
 * {@code StoryDirectorPromptAssemblerV2.assemble(..., currentSpeakerId)}에 전달.
 *
 * <p>[라우팅 결정 트리]
 * <pre>
 *   1. user_location_key 조회 → currentUserLocationKey
 *   2. chars_at(currentUserLocationKey) → 같은 공간 캐릭터 목록
 *   3. case chars_here.size:
 *        0 → AMBIENT (currentSpeakerId = null)
 *        1 → 단일 화자
 *        >=2 → 4. 호명 매칭 시도 (detectAddressedCharacter)
 *           match 1 → 호명된 화자
 *           no match → 5. 디폴트: 호감도 1위 (동률이면 lastSpokenAt 최신)
 * </pre>
 *
 * <p>[추가 책임]
 * - 디렉터 응답의 {@code character_movements} 반영
 * - 시간대 전환 시 ({@link CharacterRoutine} 기반) 캐릭터 위치 일괄 재추정
 *
 * <p>[자유도 보존 원칙]
 * 라우팅은 *디폴트 결정*일 뿐. 디렉터가 응답 안에서 *다른 캐릭터로 화자 전환*을
 * 자연스럽게 묘사하는 것은 제한하지 않는다. 다음 턴의 라우팅이 다시 작동할 뿐.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorldRoutingService {

    private final ChatRoomHeroineRepository heroineRepository;
    private final CharacterPresenceRepository presenceRepository;
    private final CharacterRoutineRepository routineRepository;

    /**
     * 라우팅 결과.
     *
     * @param currentSpeakerId  화자 캐릭터 ID. null이면 AMBIENT 모드 (시스템 화자).
     * @param charsHere         같은 공간에 있는 캐릭터들 (화자 포함). 디버그/로깅용.
     * @param matchedByAddress  호명 매칭으로 화자 결정됐는지 (true=명시 호명, false=디폴트).
     */
    public record RoutingResult(Long currentSpeakerId, List<Long> charsHere, boolean matchedByAddress) {
        public boolean isAmbient() { return currentSpeakerId == null; }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  라우팅 핵심 — 매 턴 호출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 매 유저 메시지마다 호출. 화자 결정.
     *
     * @param room        STORY V2 ChatRoom (currentUserLocationKey 사용)
     * @param userMessage 유저 입력 텍스트. null 또는 빈 문자열 허용 (액션 메시지 등).
     */
    public RoutingResult route(ChatRoom room, String userMessage) {
        String userLocationKey = room.getCurrentUserLocationKey();
        List<ChatRoomHeroine> heroines = heroineRepository.findByChatRoom_Id(room.getId());
        List<CharacterPresence> presences = presenceRepository
            .findByChatRoom_IdAndCurrentLocationKey(room.getId(), userLocationKey);

        // 같은 공간 캐릭터 ID 목록
        Set<Long> charsHereIds = presences.stream()
            .map(CharacterPresence::getCharacterId)
            .collect(Collectors.toSet());

        // heroines 중 같은 공간만 필터
        List<ChatRoomHeroine> charsHere = heroines.stream()
            .filter(h -> charsHereIds.contains(h.getCharacter().getId()))
            .toList();

        List<Long> charsHereIdList = charsHere.stream()
            .map(h -> h.getCharacter().getId())
            .toList();

        // case 0: AMBIENT
        if (charsHere.isEmpty()) {
            log.debug("🎯 [ROUTING] AMBIENT mode (no chars at location): roomId={}, location={}",
                room.getId(), userLocationKey);
            return new RoutingResult(null, charsHereIdList, false);
        }

        // case 1: 단일 화자
        if (charsHere.size() == 1) {
            Long speakerId = charsHere.get(0).getCharacter().getId();
            log.debug("🎯 [ROUTING] Single speaker: roomId={}, speakerId={}", room.getId(), speakerId);
            return new RoutingResult(speakerId, charsHereIdList, false);
        }

        // case >=2: 호명 매칭 시도
        Long addressed = detectAddressedCharacter(userMessage, charsHere);
        if (addressed != null) {
            log.debug("🎯 [ROUTING] Addressed: roomId={}, speakerId={}", room.getId(), addressed);
            return new RoutingResult(addressed, charsHereIdList, true);
        }

        // case >=2 디폴트: 호감도 1위 (동률 시 lastSpokenAt 최신)
        ChatRoomHeroine defaultSpeaker = charsHere.stream()
            .max(Comparator
                .comparingInt(ChatRoomHeroine::getStatAffection)
                .thenComparing(h -> h.getLastSpokenAt() == null ? LocalDateTime.MIN : h.getLastSpokenAt()))
            .orElseThrow();

        Long speakerId = defaultSpeaker.getCharacter().getId();
        log.debug("🎯 [ROUTING] Default (affection 1st): roomId={}, speakerId={}, affection={}",
            room.getId(), speakerId, defaultSpeaker.getStatAffection());
        return new RoutingResult(speakerId, charsHereIdList, false);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  호명 매칭 — 단순 String 포함 검사
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 메시지에서 캐릭터 이름이 직접 언급됐는지 검사.
     *
     * <p>[단순화된 매칭 — MVP]
     * - 캐릭터 이름이 메시지에 *그대로* 포함되어 있는가
     * - 대소문자 무관 (한글은 사실상 무관)
     * - 여러 캐릭터 매칭 시: 메시지 *앞쪽*에 먼저 나오는 캐릭터 우선 (자연스러운 호명 패턴)
     *
     * <p>[향후 확장 — Phase 7+]
     * - 별명/애칭 매칭 (Character에 alias 필드 추가)
     * - 대명사("그녀에게", "너") 처리 (대화 컨텍스트 분석 필요)
     * - LLM 분류기 (비용 발생 — 단순 매칭 정확도 80% 미만일 때만 도입)
     *
     * @return 매칭된 캐릭터 ID, 없으면 null
     */
    public Long detectAddressedCharacter(String userMessage, List<ChatRoomHeroine> charsHere) {
        if (userMessage == null || userMessage.isBlank()) return null;
        if (charsHere == null || charsHere.isEmpty()) return null;

        // 메시지에 가장 먼저 나오는 캐릭터 이름 우선
        Long bestMatchId = null;
        int bestPos = Integer.MAX_VALUE;

        for (ChatRoomHeroine h : charsHere) {
            String name = h.getCharacter().getName();
            if (name == null || name.isBlank()) continue;
            int pos = userMessage.indexOf(name);
            if (pos >= 0 && pos < bestPos) {
                bestPos = pos;
                bestMatchId = h.getCharacter().getId();
            }
        }
        return bestMatchId;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  캐릭터 위치 갱신 — 디렉터 응답 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 디렉터 응답의 {@code character_movements} 일괄 반영.
     * 각 movement는 캐릭터의 *오프스크린 이동* 또는 *같은 공간 진입* 모두 포함.
     */
    @Transactional
    public void applyCharacterMovements(ChatRoom room, List<Movement> movements) {
        if (movements == null || movements.isEmpty()) return;

        for (Movement m : movements) {
            if (m.characterId() == null || m.locationKey() == null) continue;

            CharacterPresence p = presenceRepository
                .findByChatRoom_IdAndCharacterId(room.getId(), m.characterId())
                .orElse(null);

            if (p == null) {
                // 신규 — 캐릭터가 아직 presence row 없으면 생성
                p = CharacterPresence.create(room, m.characterId(), m.locationKey());
                presenceRepository.save(p);
                log.debug("📍 [MOVEMENT] Created presence: roomId={}, charId={}, location={}",
                    room.getId(), m.characterId(), m.locationKey());
            } else {
                p.moveTo(m.locationKey());
                log.debug("📍 [MOVEMENT] Moved: roomId={}, charId={}, → {}",
                    room.getId(), m.characterId(), m.locationKey());
            }
        }
    }

    public record Movement(Long characterId, String locationKey) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  시간대 전환 시 위치 재추정 — CharacterRoutine 기반
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 디렉터가 시간을 새 DayPart로 전환했을 때 호출.
     * 모든 히로인의 위치를 {@link CharacterRoutine} 확률 기반으로 재추정.
     *
     * <p>[처리 방침]
     * - 같은 공간에 *현재 있는* 캐릭터는 *건드리지 않음* (디렉터가 의도적으로 두었을 수 있음)
     * - *오프스크린*인 캐릭터만 루틴 기반 위치 재추정
     * - 캐릭터에 해당 시간대 루틴 데이터가 없으면 *그대로* (위치 유지)
     *
     * @param newDayPart 전환 후 시간대
     */
    @Transactional
    public void recomputePresencesFromRoutine(ChatRoom room, DayPart newDayPart) {
        if (newDayPart == null) return;

        String userLocationKey = room.getCurrentUserLocationKey();
        List<CharacterPresence> presences = presenceRepository.findByChatRoom_Id(room.getId());

        List<Long> offscreenIds = presences.stream()
            .filter(p -> !p.isAt(userLocationKey))
            .map(CharacterPresence::getCharacterId)
            .toList();

        if (offscreenIds.isEmpty()) return;

        // 일괄 루틴 조회 (단일 쿼리)
        List<CharacterRoutine> routines = routineRepository.findByCharacterIdInAndTimeOfDay(offscreenIds, newDayPart);

        // 캐릭터별로 그룹핑
        Map<Long, List<CharacterRoutine>> routinesByChar = routines.stream()
            .collect(Collectors.groupingBy(CharacterRoutine::getCharacterId));

        Random rnd = new Random();
        for (CharacterPresence p : presences) {
            if (p.isAt(userLocationKey)) continue;  // 같은 공간 - 유지
            List<CharacterRoutine> candidates = routinesByChar.get(p.getCharacterId());
            if (candidates == null || candidates.isEmpty()) continue;

            // 확률 가중 선택
            int totalWeight = candidates.stream().mapToInt(CharacterRoutine::getProbability).sum();
            if (totalWeight <= 0) continue;
            int roll = rnd.nextInt(totalWeight);
            int acc = 0;
            for (CharacterRoutine r : candidates) {
                acc += r.getProbability();
                if (roll < acc) {
                    p.moveTo(r.getLocationKey());
                    log.debug("⏰ [ROUTINE] Repositioned: roomId={}, charId={}, time={}, → {}",
                        room.getId(), p.getCharacterId(), newDayPart, r.getLocationKey());
                    break;
                }
            }
        }
    }

    /**
     * StoryCreateFlow 진입 시 — 선택된 히로인들의 *초기 위치*를 시작 시간대 루틴 기반 결정.
     * 시작 시간대에 *유저 위치*에 있을 확률이 높은 히로인은 그 위치, 아니면 루틴 기반 다른 위치.
     */
    @Transactional
    public void initializePresences(ChatRoom room, List<Long> heroineCharIds, DayPart startDayPart) {
        if (heroineCharIds == null || heroineCharIds.isEmpty()) return;
        String userLocationKey = room.getCurrentUserLocationKey();

        List<CharacterRoutine> routines = routineRepository
            .findByCharacterIdInAndTimeOfDay(heroineCharIds, startDayPart);
        Map<Long, List<CharacterRoutine>> routinesByChar = routines.stream()
            .collect(Collectors.groupingBy(CharacterRoutine::getCharacterId));

        Random rnd = new Random();
        for (Long charId : heroineCharIds) {
            List<CharacterRoutine> candidates = routinesByChar.get(charId);
            String initialLocation;
            if (candidates == null || candidates.isEmpty()) {
                // 루틴 데이터 없음 → 유저 위치로 (어색하면 디렉터가 자율 묘사로 처리)
                initialLocation = userLocationKey;
            } else {
                int totalWeight = candidates.stream().mapToInt(CharacterRoutine::getProbability).sum();
                if (totalWeight <= 0) {
                    initialLocation = userLocationKey;
                } else {
                    int roll = rnd.nextInt(totalWeight);
                    int acc = 0;
                    String pick = userLocationKey;
                    for (CharacterRoutine r : candidates) {
                        acc += r.getProbability();
                        if (roll < acc) { pick = r.getLocationKey(); break; }
                    }
                    initialLocation = pick;
                }
            }

            CharacterPresence p = CharacterPresence.create(room, charId, initialLocation);
            presenceRepository.save(p);
        }
    }
}