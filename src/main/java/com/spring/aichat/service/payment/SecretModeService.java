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
 * [Phase 7-V2 Story · BM 피벗] 시크릿 모드 BM 통합 — 캐릭터별 → user-global
 *   - 정통 path는 *1-arg* user-global 메서드 (canAccessSecretMode(User), getStatus(User))
 *   - V1 2-arg 메서드는 그대로 유지하되 내부적으로 1-arg에 위임 + @Deprecated
 *   - V1 호출처(ChatService L202, ChatStreamService L1148, EndingService L80, UserService L115)는
 *     시그니처 무변경 — 동작만 user-global로 자연 전환됨 (BM 가치 상승 / regression 없음)
 *   - SecretModeStatus DTO 필드명은 그대로 유지 (`hasPermanentUnlock` / `has24hPass`의
 *     의미가 "any permanent" / "any active pass"로 자연 확장됨)
 *
 * [사용처]
 * - ChatService: 시크릿 프롬프트 조립 시 권한 판정 (매 요청, 1-arg 정통 path 권장)
 * - PaymentService: 시크릿 상품 구매 시 지급 (캐릭터별 레코드는 *지급 트래킹용*으로만 보존)
 * - UserController: 시크릿 모드 토글 시 권한 검증 (1-arg 사용)
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  접근 권한 판정 — V1/V2 통합 (BM 피벗)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [V1 호환 / BM 피벗 후 user-global] 시크릿 모드 접근 권한.
     *
     * <p>BM 피벗 이후 characterId는 *무시*된다. 1-arg user-global 메서드에 위임.
     * V1 호출처(ChatService/ChatStreamService/EndingService/UserService)는 시그니처를
     * 그대로 사용하되, 동작은 user-global로 전환됨 (BM 가치 상승).
     *
     * @deprecated user-global 1-arg {@link #canAccessSecretMode(User)} 사용 권장.
     *             기존 호출처는 BM 피벗으로 자연 마이그레이션됨.
     */
    @Deprecated
    public boolean canAccessSecretMode(User user, Long characterId) {
        return canAccessSecretMode(user);
    }

    /**
     * [V2 Story · Q-10 통합 / BM 피벗 정통 path] User-global 시크릿 모드 접근 게이트.
     *
     * <p>판정 순서:
     * <ol>
     *   <li>성인 인증 (user.isAdult)</li>
     *   <li>자정 패스 (hasMidnightPass) — 캐릭터 무관 user 단위</li>
     *   <li>*어떤 캐릭터에라도* 영구 해금 있음 (Q-10 통합 — 1캐릭터 해금 = user 전체 해금)</li>
     *   <li>*어떤 캐릭터에라도* 활성 24h 패스 있음 (Q-10 통합)</li>
     * </ol>
     */
    public boolean canAccessSecretMode(User user) {
        if (!Boolean.TRUE.equals(user.getIsAdult())) {
            return false;
        }
        if (hasMidnightPass(user.getId())) {
            return true;
        }
        if (hasAnyPermanentUnlock(user.getId())) {
            return true;
        }
        return hasAnyActive24hPass(user.getId());
    }

    /** [V2 · Q-10] 유저가 *어떤 캐릭터에라도* 영구 해금을 보유하면 true. */
    public boolean hasAnyPermanentUnlock(Long userId) {
        return !secretUnlockRepository.findByUser_Id(userId).isEmpty();
    }

    /** [V2 · Q-10] 유저가 *어떤 캐릭터에라도* 활성 24h 패스를 보유하면 true. */
    public boolean hasAnyActive24hPass(Long userId) {
        return secretPassRepository.existsAnyActivePassByUserId(userId, LocalDateTime.now());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  24시간 패스 (RDB 영속화 + Redis 캐싱)
    //  — 결제 시점에는 여전히 캐릭터 단위 레코드를 *지급 트래킹용*으로 생성한다.
    //    BM 피벗은 접근 게이트(canAccess/getStatus)에서만 user-global로 적용됨.
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 24시간 시크릿 패스 활성화
     *
     * [Phase 5 Fix] RDB 먼저 저장 → Redis에 캐싱
     *
     * @param user        유저 엔티티 (FK 참조용)
     * @param characterId 대상 캐릭터 ID (지급 트래킹용. 접근 게이트는 user-global)
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
     * 24시간 패스 보유 여부 확인 — *캐릭터별*.
     *
     * <p>BM 피벗 이후 접근 게이트는 {@link #hasAnyActive24hPass(Long)}를 사용한다.
     * 본 메서드는 호환성 및 *지급 트래킹*용 — 특정 캐릭터에 지급된 패스 조회 시.
     *
     * [Phase 5 Fix] Redis Read-Through 패턴
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
    //  영구 해금 (기존 로직 유지 — 지급 트래킹용)
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
     * 영구 해금 보유 여부 — *캐릭터별*.
     *
     * <p>BM 피벗 이후 접근 게이트는 {@link #hasAnyPermanentUnlock(Long)}를 사용한다.
     * 본 메서드는 *지급 트래킹*용 (특정 캐릭터에 영구 해금 기록 존재 여부, 중복 결제 차단 등).
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
    //  상태 조회 (프론트엔드 표시용) — V1/V2 통합 (BM 피벗)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [V1 호환 / BM 피벗 후 user-global] 시크릿 모드 상태 요약.
     *
     * <p>BM 피벗 이후 characterId는 *무시*된다. 1-arg user-global 메서드에 위임.
     *
     * @deprecated user-global 1-arg {@link #getStatus(User)} 사용 권장.
     */
    @Deprecated
    public SecretModeStatus getStatus(User user, Long characterId) {
        return getStatus(user);
    }

    /**
     * [V2 Story · BM 피벗 정통 path] 유저의 시크릿 모드 접근 상태 요약 (user-global).
     *
     * <p>SecretModeStatus DTO 필드 의미 재해석:
     * <ul>
     *   <li>{@code hasPermanentUnlock} → *어떤 캐릭터에라도* 영구 해금 보유 여부</li>
     *   <li>{@code has24hPass} → *어떤 캐릭터에라도* 활성 24h 패스 보유 여부</li>
     * </ul>
     * <p>DTO 필드명은 V1 호환을 위해 그대로 유지 (프론트엔드 SecretModeFlow는
     * {@code canAccess()} computed accessor만 사용 — 의미 재해석 영향 없음).
     */
    public SecretModeStatus getStatus(User user) {
        if (!Boolean.TRUE.equals(user.getIsAdult())) {
            return new SecretModeStatus(false, false, false, false, "NEED_ADULT_VERIFY");
        }

        boolean midnightPass = hasMidnightPass(user.getId());
        boolean anyPermanentUnlock = hasAnyPermanentUnlock(user.getId());
        boolean anyActive24hPass = hasAnyActive24hPass(user.getId());
        boolean canAccess = midnightPass || anyPermanentUnlock || anyActive24hPass;

        String reason = canAccess ? "GRANTED" : "NEED_PURCHASE";
        return new SecretModeStatus(true, midnightPass, anyPermanentUnlock, anyActive24hPass, reason);
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