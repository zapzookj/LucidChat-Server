package com.spring.aichat.controller;

import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.notification.NotificationResponse;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 유저 인앱 알림 (폴링). 인증된 유저 본인 것만. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @GetMapping
    public Page<NotificationResponse> list(Authentication auth,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return notificationService.list(uid(auth), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Authentication auth) {
        return Map.of("count", notificationService.unreadCount(uid(auth)));
    }

    @PostMapping("/{id}/read")
    public void read(@PathVariable Long id, Authentication auth) {
        notificationService.markRead(uid(auth), id);
    }

    @PostMapping("/read-all")
    public void readAll(Authentication auth) {
        notificationService.markAllRead(uid(auth));
    }

    private Long uid(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "유저를 찾을 수 없습니다."))
            .getId();
    }
}
