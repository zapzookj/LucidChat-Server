package com.spring.aichat.service.user;

import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.user.UpdateUserRequest;
import com.spring.aichat.dto.user.UserResponse;
import com.spring.aichat.service.cache.RedisCacheService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 유저 프로필 서비스
 *
 * [Phase 3 Redis 캐싱]
 * - getMyInfo(): Redis에서 먼저 조회 → Cache Miss 시에만 DB 조회 후 캐싱
 * - updateMyInfo(): DB 업데이트 후 캐시 evict → 다음 조회 시 최신 데이터로 갱신
 * - TTL: 30분 (프로필 변경은 드물지만, 에너지 등 주기적 변경에 대비)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RedisCacheService cacheService;

    public UserResponse getMyInfo(String username) {
        // 1. Redis 캐시 조회
        return cacheService.getUserProfile(username, UserResponse.class)
            .orElseGet(() -> {
                // 2. Cache Miss → DB 조회
                User currentUser = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found"));

                UserResponse response = new UserResponse(
                    currentUser.getId(),
                    currentUser.getUsername(),
                    currentUser.getNickname(),
                    currentUser.getEmail(),
                    currentUser.getProfileDescription(),
                    currentUser.getIsSecretMode()
                );

                // 3. Redis에 캐싱 (TTL 30분)
                cacheService.cacheUserProfile(username, response);
                log.debug("👤 [CACHE] User profile cached: {}", username);

                return response;
            });
    }

    @Transactional
    public void updateMyInfo(UpdateUserRequest request, String username) {
        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (request.nickname() != null) {
            currentUser.updateNickName(request.nickname());
        }
        if (request.profileDescription() != null) {
            currentUser.updateProfileDescription(request.profileDescription());
        }

        currentUser.updateIsSecretMode(request.isSecretMode() != null ? request.isSecretMode() : false);

        userRepository.save(currentUser);

        // 캐시 무효화 → 다음 getMyInfo() 호출 시 DB에서 최신 데이터 로드
        cacheService.evictUserProfile(username);
        log.debug("👤 [CACHE] User profile evicted: {}", username);
    }
}