package com.spring.aichat.service.story;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import com.spring.aichat.domain.notification.OffscreenNotification;
import com.spring.aichat.domain.notification.OffscreenNotificationRepository;
import com.spring.aichat.dto.story.StoryV2Responses.NotificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * [V2 Story] 오프스크린 캐릭터 알림 서비스
 *
 * <p>책임:
 * 1. 디렉터 응답의 {@code incoming_messages} 처리 — 가드 통과 시 저장.
 * 2. 트리거 가드 — 친밀도/쿨다운/시간 경과 검증.
 * 3. UI 상태 마킹 — 읽음/응답.
 * 4. 만료 처리 — 스케줄러로 24h 경과 미응답 알림 폐기 + 친밀도 -1 페널티.
 *
 * <p>[가드 정책 — 결정 §2.3]
 * <pre>
 *   친밀도 (statIntimacy) ≥ 30
 *   같은 캐릭터 마지막 알림 후 24h 쿨다운
 *   World 시간 경과 (마지막 만남 후 4시간) — V2 ChatRoom.currentDay/DayPart 기반
 * </pre>
 *
 * <p>[conversation_weight 가드 보류]
 * MVP에서는 conversation_weight 시그널 도입 보류 결정에 따라, 본 가드에서 제외.
 * 베타 운영 중 *깊은 대화 깨짐* 사례 관찰되면 디렉터 prompt에 직접 자제 가이드 추가
 * 또는 weight 시그널 도입 검토.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OffscreenNotificationService {

    private final OffscreenNotificationRepository notificationRepository;
    private final ChatRoomHeroineRepository heroineRepository;
    private final CharacterRepository characterRepository;

    /** 친밀도 임계 — 이 이상에서만 알림 발신 가능 */
    private static final int INTIMACY_THRESHOLD = 30;
    /** 쿨다운 — 같은 캐릭터 알림 간격 (시간 단위) */
    private static final long COOLDOWN_HOURS = 24;
    /** 만료 — 미응답 알림 폐기 + 페널티 (시간 단위, OffscreenNotification 엔티티가 24h로 고정) */
    private static final int EXPIRY_PENALTY_AFFECTION = -1;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  생성 — 디렉터 응답 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 디렉터 응답의 {@code incoming_messages} 처리.
     * 각 메시지는 가드 통과 시 저장, 실패 시 silent drop (로그만).
     *
     * @param incoming 디렉터 출력 — List<{characterId, content}>
     */
    @Transactional
    public void processDirectorOutput(ChatRoom room, List<IncomingMessage> incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        if (!room.isStoryMode()) return;  // STORY V2 전용

        for (IncomingMessage msg : incoming) {
            if (msg.fromCharacterId() == null || msg.content() == null || msg.content().isBlank()) {
                continue;
            }

            if (!passesGuards(room, msg.fromCharacterId())) {
                log.debug("📮 [NOTIFICATION] Guard rejected: roomId={}, fromChar={}",
                    room.getId(), msg.fromCharacterId());
                continue;
            }

            OffscreenNotification n = OffscreenNotification.create(
                room,
                msg.fromCharacterId(),
                msg.content(),
                room.getCurrentDay() != null ? room.getCurrentDay() : 1,
                room.getCurrentDayPart() != null ? room.getCurrentDayPart().name() : "EVENING"
            );
            notificationRepository.save(n);

            log.info("📮 [NOTIFICATION] Created: roomId={}, fromChar={}, content='{}'",
                room.getId(), msg.fromCharacterId(),
                msg.content().substring(0, Math.min(50, msg.content().length())));
        }
    }

    public record IncomingMessage(Long fromCharacterId, String content) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  트리거 가드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 알림 발신 가드 — 친밀도 + 쿨다운.
     * (디렉터가 알아서 자제해야 하지만, 백엔드도 이중 가드로 안전망.)
     */
    public boolean passesGuards(ChatRoom room, Long fromCharacterId) {
        // 가드 1: 친밀도 임계
        ChatRoomHeroine heroine = heroineRepository
            .findByChatRoom_IdAndCharacter_Id(room.getId(), fromCharacterId)
            .orElse(null);
        if (heroine == null) {
            log.debug("📮 Guard: heroine row not found, roomId={}, charId={}", room.getId(), fromCharacterId);
            return false;
        }
        if (heroine.getStatIntimacy() < INTIMACY_THRESHOLD) {
            log.debug("📮 Guard: intimacy {} < threshold {}", heroine.getStatIntimacy(), INTIMACY_THRESHOLD);
            return false;
        }

        // 가드 2: 같은 캐릭터 쿨다운
        Optional<OffscreenNotification> lastOpt = notificationRepository
            .findTopByChatRoom_IdAndFromCharacterIdOrderBySentAtDesc(room.getId(), fromCharacterId);
        if (lastOpt.isPresent()) {
            long hoursSince = Duration.between(lastOpt.get().getSentAt(), LocalDateTime.now()).toHours();
            if (hoursSince < COOLDOWN_HOURS) {
                log.debug("📮 Guard: cooldown active — {}h since last (need {}h)", hoursSince, COOLDOWN_HOURS);
                return false;
            }
        }
        return true;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  UI 상태 마킹
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 활성 미응답 알림 조회 (디렉터 prompt 인젝션용). */
    public List<OffscreenNotification> findPendingForPrompt(Long roomId) {
        return notificationRepository
            .findByChatRoom_IdAndRespondedAtIsNullAndExpiresAtAfterOrderBySentAtAsc(
                roomId, LocalDateTime.now());
    }

    /**
     * 미확인 알림 조회 (UI 토스트 노출). 응답 DTO로 직접 변환 — Controller 단순화.
     * 가장 최근 알림이 배열 앞.
     */
    @Transactional(readOnly = true)
    public List<NotificationResponse> findUnreadForToast(Long roomId) {
        List<OffscreenNotification> rows = notificationRepository
            .findByChatRoom_IdAndReadAtIsNullOrderBySentAtDesc(roomId);
        if (rows.isEmpty()) return List.of();
        Map<Long, String> nameById = resolveCharacterNames(rows);
        return rows.stream()
            .map(n -> new NotificationResponse(
                n.getId(),
                n.getFromCharacterId(),
                nameById.getOrDefault(n.getFromCharacterId(), "(이름 미상)"),
                n.getContent(),
                n.getWorldDay(),
                n.getWorldDayPart(),
                n.getSentAt(),
                n.getReadAt() != null,
                n.getRespondedAt() != null
            ))
            .toList();
    }

    /** 알림들의 fromCharacterId → Character.name 일괄 조회. */
    private Map<Long, String> resolveCharacterNames(List<OffscreenNotification> notifications) {
        Set<Long> ids = notifications.stream()
            .map(OffscreenNotification::getFromCharacterId)
            .collect(Collectors.toSet());
        Map<Long, String> result = new HashMap<>();
        characterRepository.findAllById(ids)
            .forEach(c -> result.put(c.getId(), c.getName()));
        return result;
    }

    /** 미확인 알림 카운트만 필요한 경우 — getRoomDetail 등에서 사용. */
    @Transactional(readOnly = true)
    public int countUnread(Long roomId) {
        return notificationRepository.findByChatRoom_IdAndReadAtIsNullOrderBySentAtDesc(roomId).size();
    }

    @Transactional
    public void markRead(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(OffscreenNotification::markRead);
    }

    /**
     * 디렉터 prompt가 미응답 알림을 *소비*했을 때 호출 — 같은 캐릭터가 화자가 되거나
     * 유저가 그 캐릭터와 같은 공간이 된 직후, 디렉터 응답에서 자연스럽게 화제로 꺼냈을 때.
     *
     * <p>호출 위치: ChatStreamService — 디렉터 응답 처리 시, 그 응답의 화자가 미응답 알림의
     * 발신자와 일치하면 그 알림을 응답 처리.
     */
    @Transactional
    public void markResponded(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(OffscreenNotification::markResponded);
    }

    @Transactional
    public void markRespondedByCharacter(Long roomId, Long fromCharacterId) {
        List<OffscreenNotification> pending = notificationRepository
            .findByChatRoom_IdAndRespondedAtIsNullAndExpiresAtAfterOrderBySentAtAsc(
                roomId, LocalDateTime.now());
        for (OffscreenNotification n : pending) {
            if (fromCharacterId.equals(n.getFromCharacterId())) {
                n.markResponded();
                log.debug("📮 [NOTIFICATION] Marked responded (by character): id={}, char={}",
                    n.getId(), fromCharacterId);
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  만료 처리 — @Scheduled
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 매 1시간 — 24h 경과 미응답 알림에 친밀도 -1 페널티 + 응답 마킹(이력 보존)
     */
    @Scheduled(fixedRate = 60 * 60 * 1000)  // 1시간
    @Transactional
    public void expireOverdueNotifications() {
        LocalDateTime now = LocalDateTime.now();
        List<OffscreenNotification> overdue = notificationRepository
            .findByExpiresAtBeforeAndRespondedAtIsNull(now);
        if (overdue.isEmpty()) return;

        int penaltyCount = 0;
        for (OffscreenNotification n : overdue) {
            // 친밀도 -1 페널티
            Long roomId = n.getChatRoom().getId();
            ChatRoomHeroine h = heroineRepository
                .findByChatRoom_IdAndCharacter_Id(roomId, n.getFromCharacterId())
                .orElse(null);
            if (h != null) {
                h.applyNormalStatChanges(EXPIRY_PENALTY_AFFECTION, 0, 0, 0, 0);
                penaltyCount++;
            }
            // *Responded* 마킹 — 이력 보존 (delete X)
            n.markResponded();
        }

        log.info("⏰ [NOTIFICATION-EXPIRY] Processed {} overdue (penalty applied to {})",
            overdue.size(), penaltyCount);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스토리 초기화 시 일괄 삭제
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void clearNotificationsForRoom(Long roomId) {
        notificationRepository.deleteByChatRoom_Id(roomId);
        log.info("🗑️ [NOTIFICATION] Cleared room notifications: roomId={}", roomId);
    }
}