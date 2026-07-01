package com.spring.aichat.service.notification;

import com.spring.aichat.domain.notification.Notification;
import com.spring.aichat.domain.notification.NotificationRepository;
import com.spring.aichat.dto.notification.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 유저 인앱 알림 서비스 (Phase 6 · 폴링). 티켓 답변/공지/시스템 알림을 user-keyed 로 적재하고,
 * 클라이언트가 미읽음 카운트/목록을 폴링한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** 알림 생성 — 다른 서비스(티켓 답변 등)의 트랜잭션에 참여한다. */
    @Transactional
    public Notification notify(Long userId, String type, String title, String body, String linkType, String linkId) {
        return notificationRepository.save(Notification.of(userId, type, title, body, linkType, linkId));
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(Long userId, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByIdDesc(userId, pageable).map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(Long userId, Long id) {
        notificationRepository.findByIdAndUserId(id, userId).ifPresent(Notification::markRead);
    }

    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.markAllRead(userId, LocalDateTime.now());
    }
}
