package com.spring.aichat.security;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방 소유권 검증 가드
 *
 * [Phase 3 Redis 캐싱]
 * - 기존: 매 요청마다 chatRoomRepository.findById() → PESSIMISTIC_WRITE lock 발생
 * - 개선: Redis에 roomId → username 매핑을 영구 캐싱
 *   → DB 쿼리 + 행 잠금 완전 제거
 *   → 방 소유자는 생성 후 변경되지 않으므로 TTL 불필요
 *
 * Cache Miss 시에만 DB를 조회하고 결과를 Redis에 저장한다 (Cache-Aside 패턴).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AuthGuard {

    private final ChatRoomRepository chatRoomRepository;
    private final RedisCacheService cacheService;

    /**
     * 해당 채팅방의 소유자인지 검증
     * @param roomId 채팅방 ID
     * @param username 현재 로그인한 사용자명 (Principal)
     */
    public boolean checkRoomOwnership(Long roomId, String username) {
        // 1. Redis 캐시 조회 (Cache Hit → DB 접근 없음)
        String cachedOwner = cacheService.getRoomOwner(roomId).orElse(null);

        if (cachedOwner != null) {
            return cachedOwner.equals(username);
        }

        // 2. Cache Miss → DB 조회
        //    ⚠️ 기존 findById에 PESSIMISTIC_WRITE lock이 걸려있으므로,
        //    소유권 검증용으로는 EntityGraph 조회를 사용한다 (읽기 전용, lock 없음).
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "채팅방을 찾을 수 없습니다."));

        String ownerUsername = room.getUser().getUsername();

        // 3. Redis에 캐싱 (영구 — 방 소유자는 변하지 않음)
        cacheService.cacheRoomOwner(roomId, ownerUsername);
        log.debug("🔑 [CACHE] Room ownership cached: roomId={} → owner={}", roomId, ownerUsername);

        return ownerUsername.equals(username);
    }

    @Transactional
    public Long getCurrentUserId(Long roomId) {
        // 1. Redis에서 방 소유자 조회
        String cachedOwner = cacheService.getRoomOwner(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "채팅방을 찾을 수 없습니다."));

        // 2. DB에서 사용자 ID 조회 (캐시에는 username만 저장되어 있으므로)
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        return chatRoom.getUser().getId();
    }
}