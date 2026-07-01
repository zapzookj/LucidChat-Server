package com.spring.aichat.config;

import com.spring.aichat.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자 계정 부트스트랩.
 *
 * app.admin.bootstrap-usernames (콤마 구분) 에 지정된 유저에게 기동 시 ROLE_ADMIN 을 보장한다.
 * 미설정 시 no-op. ROLE_ADMIN 은 user_roles 조인테이블의 문자열 값이라 스키마 변경이 필요 없다.
 *
 * 셀프-사인업으로는 ADMIN이 될 수 없으므로(가입 시 ROLE_USER만 부여), 운영에서는 이 프로퍼티로
 * 최초 관리자를 승격시킨 뒤 백오피스에서 추가 관리자를 관리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrapRunner implements ApplicationRunner {

    private final UserRepository userRepository;

    @Value("${app.admin.bootstrap-usernames:}")
    private List<String> bootstrapUsernames;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapUsernames == null || bootstrapUsernames.isEmpty()) return;

        for (String raw : bootstrapUsernames) {
            if (raw == null || raw.isBlank()) continue;
            String username = raw.trim();
            userRepository.findByUsername(username).ifPresentOrElse(
                user -> {
                    if (user.getRoles().add("ROLE_ADMIN")) {
                        log.info("[ADMIN-BOOTSTRAP] Granted ROLE_ADMIN to '{}'", username);
                    } else {
                        log.debug("[ADMIN-BOOTSTRAP] '{}' already has ROLE_ADMIN", username);
                    }
                },
                () -> log.warn("[ADMIN-BOOTSTRAP] User '{}' not found — cannot grant ROLE_ADMIN", username)
            );
        }
    }
}
