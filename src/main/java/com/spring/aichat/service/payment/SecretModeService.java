package com.spring.aichat.service.payment;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.SubscriptionType;
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

/**
 * 시크릿 모드 접근 제어 서비스
 *
 * [접근 권한 판정]
 * 성인 인증 AND (미드나잇 패스 구독 OR 영구 해금 OR 24h 패스)
 *
 * [사용처]
 * - ChatService: 시크릿 프롬프트 조립 시 권한 판정
 * - PaymentService: 시크릿 상품 구매 시 지급
 * - UserController: 시크릿 모드 토글 시 권한 검증
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SecretModeService {

    private final UserSubscriptionRepository subscriptionRepository;
    private final UserSecretUnlockRepository secretUnlockRepository;
    private final CharacterRepository characterRepository;
    private final RedisCacheService cacheService;

    private static final String SECRET_PASS_PREFIX = "secret_pass:";

    /**
     * 특정 캐릭터에 대한 시크릿 모드 접근 가능 여부
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

    /**
     * 24시간 시크릿 패스 활성화 (Redis TTL)
     */
    public void activate24hPass(Long userId, Long characterId) {
        String key = SECRET_PASS_PREFIX + userId + ":" + characterId;
        cacheService.setWithTTL(key, "active", 24 * 60 * 60);
        log.info("[SECRET] 24h pass activated: userId={}, charId={}", userId, characterId);
    }

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

    /**
     * 24시간 패스 활성 여부
     */
    public boolean has24hPass(Long userId, Long characterId) {
        String key = SECRET_PASS_PREFIX + userId + ":" + characterId;
        return cacheService.getString(key).isPresent();
    }

    /**
     * 미드나잇 패스 구독 여부
     */
    private boolean hasMidnightPass(Long userId) {
        return subscriptionRepository.findByUser_IdAndActiveTrue(userId)
            .map(sub -> sub.getType() == SubscriptionType.LUCID_MIDNIGHT_PASS && !sub.isExpired())
            .orElse(false);
    }

    /**
     * 유저의 시크릿 모드 접근 상태 요약 (프론트엔드 표시용)
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
}