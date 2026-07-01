package com.spring.aichat.service.moderation;

import com.spring.aichat.domain.moderation.InjectionEvent;
import com.spring.aichat.domain.moderation.InjectionEventRepository;
import com.spring.aichat.domain.moderation.ModerationEvent;
import com.spring.aichat.domain.moderation.ModerationEventRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.admin.InjectionEventResponse;
import com.spring.aichat.dto.admin.ModerationEventResponse;
import com.spring.aichat.dto.admin.OffenderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 모더레이션/인젝션 이벤트 영속화 + 관리자 리뷰 큐 + 반복위반 집계 (Phase 6).
 *
 * 기록은 라이브 채팅 스트림 경로에서 호출되므로 절대 스트림을 깨서는 안 된다 → best-effort(스왈로우).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModerationEventService {

    private static final int MSG_MAX = 2000;

    private final ModerationEventRepository moderationRepository;
    private final InjectionEventRepository injectionRepository;
    private final UserRepository userRepository;

    @Transactional
    public void recordModeration(Long userId, Long roomId, String source, int blockedAtStep,
                                 String category, long latencyMs, String message) {
        try {
            moderationRepository.save(ModerationEvent.of(
                userId, roomId, source, blockedAtStep, category, truncate(message), latencyMs));
        } catch (Exception e) {
            log.error("[MOD-EVENT] persist failed userId={} room={}", userId, roomId, e);
        }
    }

    @Transactional
    public void recordInjection(Long userId, String username, Long roomId, String source,
                                String severity, String matchedPattern, String message) {
        try {
            injectionRepository.save(InjectionEvent.of(
                userId, username, roomId, source, severity, truncate(matchedPattern, 500), truncate(message)));
        } catch (Exception e) {
            log.error("[INJ-EVENT] persist failed userId={} room={}", userId, roomId, e);
        }
    }

    // ─────────────── 관리자 조회 ───────────────

    @Transactional(readOnly = true)
    public Page<ModerationEventResponse> listModeration(Pageable pageable) {
        return moderationRepository.findAllByOrderByIdDesc(pageable).map(ModerationEventResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<InjectionEventResponse> listInjection(Pageable pageable) {
        return injectionRepository.findAllByOrderByIdDesc(pageable).map(InjectionEventResponse::from);
    }

    /** 반복 위반자 상위 N — 모더레이션 + 인젝션 합산 후 username 해석. */
    @Transactional(readOnly = true)
    public List<OffenderResponse> topOffenders(int limit) {
        Map<Long, long[]> counts = new HashMap<>(); // userId -> [mod, inj]
        for (Object[] row : moderationRepository.countByUser()) {
            counts.computeIfAbsent((Long) row[0], k -> new long[2])[0] = ((Number) row[1]).longValue();
        }
        for (Object[] row : injectionRepository.countByUser()) {
            counts.computeIfAbsent((Long) row[0], k -> new long[2])[1] = ((Number) row[1]).longValue();
        }

        List<Map.Entry<Long, long[]>> sorted = counts.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]))
            .limit(Math.max(1, limit))
            .toList();

        Map<Long, User> users = userRepository.findAllById(sorted.stream().map(Map.Entry::getKey).toList())
            .stream().collect(Collectors.toMap(User::getId, u -> u));

        return sorted.stream().map(e -> {
            User u = users.get(e.getKey());
            long mod = e.getValue()[0], inj = e.getValue()[1];
            return new OffenderResponse(
                e.getKey(),
                u != null ? u.getUsername() : null,
                u != null ? u.getNickname() : null,
                mod, inj, mod + inj);
        }).toList();
    }

    private String truncate(String s) { return truncate(s, MSG_MAX); }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
