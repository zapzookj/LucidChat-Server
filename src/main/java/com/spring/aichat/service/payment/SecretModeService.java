package com.spring.aichat.service.payment;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.domain.payment.UserSecretPass;
import com.spring.aichat.domain.payment.UserSecretPassRepository;
import com.spring.aichat.domain.payment.UserSecretUnlock;
import com.spring.aichat.domain.payment.UserSecretUnlockRepository;
import com.spring.aichat.domain.payment.UserSubscription;
import com.spring.aichat.domain.payment.UserSubscriptionRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 시크릿 모드 접근 제어 서비스
 *
 * [접근 권한 판정]
 * 성인 인증 AND (미드나잇 패스 구독 OR 영구 해금 OR 24h 패스)
 *
 * [Phase 5 Fix] 2가지 결함 수정
 *
 * Fix 1: 24h 패스 RDB 영속화
 *   - 기존: Redis TTL에만 저장 → Redis 재시작 시 유료 권한 소멸
 *   - 수정: RDB(UserSecretPass 테이블)를 Source of Truth로, Redis는 Read-Through 캐시
 *   - 흐름: 결제 → RDB INSERT + Redis SET(TTL)
 *           조회 → Redis GET → miss이면 RDB 조회 → 활성이면 Redis 재캐싱
 *
 * Fix 2: 런타임 접근 검증 강화
 *   - 기존: User.isSecretMode 플래그만으로 시크릿 프롬프트 결정
 *   - 수정: ChatService에서 매 요청마다 canAccessSecretMode() 호출
 *
 * [사용처]
 * - ChatService: 시크릿 프롬프트 조립 시 권한 판정 (매 요청)
 * - PaymentService: 시크릿 상품 구매 시 지급
 * - UserController: 시크릿 모드 토글 시 권한 검증
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SecretModeService {

    private final UserSubscriptionRepository subscriptionRepository;
    private final UserSecretUnlockRepository secretUnlockRepository;
    private final UserSecretPassRepository secretPassRepository;   // [Fix 2] 24h 패스 RDB
    private final CharacterRepository characterRepository;
    private final RedisCacheService cacheService;

    private static final String SECRET_PASS_PREFIX = "secret_pass:";

    /**
     * 특정 캐릭터에 대한 시크릿 모드 접근 가능 여부
     *
     * [Phase 5 Fix] 이 메서드가 유일한 권한 판정 게이트
     * ChatService, UserService 모두 이 메서드를 통해 검증해야 한다.
     */
    public boolean canAccessSecretMode(User user, Long characterId) {
        if (!Boolean.TRUE.equals(user.getIsAdult())) {
            return false;
        }
        if (hasMidnightPass(user.getId())) {
            return true;
        }
        if (hasPermanentUnlock(user.getId(), characterId)) {
            return true;
        }
        return has24hPass(user.getId(), characterId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  24시간 패스 (RDB 영속화 + Redis 캐싱)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 24시간 시크릿 패스 활성화
     *
     * [Phase 5 Fix] RDB 먼저 저장 → Redis에 캐싱
     *
     * @param user        유저 엔티티 (FK 참조용)
     * @param characterId 대상 캐릭터 ID
     * @param merchantUid 결제 추적용 주문번호
     */
    public void activate24hPass(User user, Long characterId, String merchantUid) {
        Character character = characterRepository.findById(characterId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Character not found: " + characterId));

        // 1. RDB 영속화 (Source of Truth)
        UserSecretPass pass = UserSecretPass.create24h(user, character, merchantUid);
        secretPassRepository.save(pass);

        // 2. Redis 캐싱 (Read-Through 가속용)
        String cacheKey = buildPassCacheKey(user.getId(), characterId);
        cacheService.setWithTTL(cacheKey, "active", 24 * 60 * 60);

        log.info("[SECRET] 24h pass activated (RDB+Redis): userId={}, charId={}, expiresAt={}, merchantUid={}",
            user.getId(), characterId, pass.getExpiresAt(), merchantUid);
    }

    /**
     * 24시간 패스 보유 여부 확인
     *
     * [Phase 5 Fix] Redis Read-Through 패턴
     * Redis hit → true
     * Redis miss → RDB 조회 → 활성이면 Redis 재캐싱 + true
     */
    public boolean has24hPass(Long userId, Long characterId) {
        String cacheKey = buildPassCacheKey(userId, characterId);

        // 1. Redis 캐시 체크 (99%의 경우 여기서 응답)
        if (cacheService.getString(cacheKey).isPresent()) {
            return true;
        }

        // 2. Redis miss → RDB 폴백 (Redis 재시작/장애 복구)
        return secretPassRepository.findActivePass(userId, characterId, LocalDateTime.now())
            .map(pass -> {
                // 활성 패스 발견 → Redis 재캐싱 (남은 TTL로)
                long remainingTtl = pass.remainingTtlSeconds();
                if (remainingTtl > 0) {
                    cacheService.setWithTTL(cacheKey, "active", remainingTtl);
                    log.info("[SECRET] 24h pass re-cached from RDB: userId={}, charId={}, remainingTtl={}s",
                        userId, characterId, remainingTtl);
                }
                return true;
            })
            .orElse(false);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  영구 해금 (기존 로직 유지)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 영구 해금 레코드 생성
     */
    public void createPermanentUnlock(User user, Long characterId, String merchantUid) {
        if (hasPermanentUnlock(user.getId(), characterId)) {
            log.warn("[SECRET] Already unlocked: userId={}, charId={}", user.getId(), characterId);
            return; // 멱등성
        }

        Character character = characterRepository.findById(characterId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Character not found: " + characterId));

        UserSecretUnlock unlock = UserSecretUnlock.create(user, character, merchantUid);
        secretUnlockRepository.save(unlock);
        log.info("[SECRET] Permanent unlock created: userId={}, charId={}", user.getId(), characterId);
    }

    /**
     * 영구 해금 보유 여부
     */
    public boolean hasPermanentUnlock(Long userId, Long characterId) {
        return secretUnlockRepository.existsByUser_IdAndCharacter_Id(userId, characterId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  구독 확인 (기존 로직 유지)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 미드나잇 패스 구독 여부
     */
    private boolean hasMidnightPass(Long userId) {
        return subscriptionRepository.findByUser_IdAndActiveTrue(userId)
            .map(sub -> sub.getType() == SubscriptionType.LUCID_MIDNIGHT_PASS && !sub.isExpired())
            .orElse(false);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  상태 조회 (프론트엔드 표시용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 유저의 시크릿 모드 접근 상태 요약
     */
    public SecretModeStatus getStatus(User user, Long characterId) {
        if (!Boolean.TRUE.equals(user.getIsAdult())) {
            return new SecretModeStatus(false, false, false, false, "NEED_ADULT_VERIFY");
        }

        boolean midnightPass = hasMidnightPass(user.getId());
        boolean permanentUnlock = hasPermanentUnlock(user.getId(), characterId);
        boolean pass24h = has24hPass(user.getId(), characterId);
        boolean canAccess = midnightPass || permanentUnlock || pass24h;

        String reason = canAccess ? "GRANTED" : "NEED_PURCHASE";
        return new SecretModeStatus(true, midnightPass, permanentUnlock, pass24h, reason);
    }

    public record SecretModeStatus(
        boolean isAdult,
        boolean hasMidnightPass,
        boolean hasPermanentUnlock,
        boolean has24hPass,
        String accessReason
    ) {
        public boolean canAccess() {
            return isAdult && (hasMidnightPass || hasPermanentUnlock || has24hPass);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Internal
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildPassCacheKey(Long userId, Long characterId) {
        return SECRET_PASS_PREFIX + userId + ":" + characterId;
    }
}