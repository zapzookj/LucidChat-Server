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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    // [블록 B] 페르소나 나이 하드 게이트 — 활성 프로필 age 판정 소스
    private final com.spring.aichat.service.persona.UserPersonaService userPersonaService;

    private static final String SECRET_PASS_PREFIX = "secret_pass:";

    /**
     * [docs/19_assets/decisions_confirmed.md §A #7 = (b) · 종원 확정] 시크릿 상품 노출 토글.
     *
     * <p>off(기본)면 시크릿 상품 3종(SECRET_PASS_24H · SECRET_UNLOCK_PERMANENT ·
     * LUCID_MIDNIGHT_PASS)의 <b>주문 생성이 서버측에서 400으로 거부</b>되고
     * ({@code PaymentService.prepareOrder}), FE는 이 값을 {@code /users/secret-status}의
     * {@code secretProductsEnabled}로 받아 상품 카드·탭을 숨긴다.
     *
     * <p><b>해제 시점 = '승인 PG의 성인 콘텐츠 정책 확인 이후'</b>. 심사 통과 후 몰래 켜는
     * 경로는 docs/14 §C-#3이 배제했다 — 성인 콘텐츠를 수용하는 PG를 먼저 확보하는 것이
     * 정면 전략이고, 이 토글은 그 확인까지의 대기 스위치다(docs/18 §1-D D2).
     *
     * <p>ⓘ 이 플래그는 <b>구매(진입)만</b> 막는다. 이미 권한을 보유한 계정의 콘텐츠 접근
     * ({@link #canAccessSecretMode(User)})은 건드리지 않는다 — 결제 완료 유저의 권한을
     * 사후에 회수하는 셈이 되기 때문이다. 심사 기간에는 신규 취득 경로가 닫히므로
     * 실질적으로 게이팅 상태가 유지된다(잔여 판단은 보고서 notes 참조).
     */
    @Value("${bm.secret-products-enabled:false}")
    private boolean secretProductsEnabled;

    /** [안건 7] 시크릿 상품 판매 허용 여부. FE 노출 판정·서버측 주문 거부의 단일 소스. */
    public boolean isSecretProductsEnabled() {
        return secretProductsEnabled;
    }

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
     * <p>[UGC v1] characterId가 주어지면 <b>캐릭터 레벨 게이트</b>가 추가된다 —
     * UGC 캐릭터는 승인({@code secretEligible=true}) 전 Secret 차단. 공식 캐릭터는 항상 eligible
     * (V9 마이그레이션 + applySeed 불변식)이라 기존 동작과 동일.
     *
     * @deprecated user-global 1-arg {@link #canAccessSecretMode(User)} 사용 권장 —
     *             단, <b>캐릭터 문맥이 있는 V1 경로는 이 2-arg를 유지</b>해야 UGC 게이트가 작동한다.
     */
    @Deprecated
    public boolean canAccessSecretMode(User user, Long characterId) {
        if (characterId != null && !isCharacterSecretEligible(characterId)) {
            return false;
        }
        return canAccessSecretMode(user);
    }

    /**
     * [UGC v1] 캐릭터 레벨 Secret 허용 여부 — 미존재 캐릭터는 차단.
     *
     * <p>[안건 9-A · docs/19_assets/decisions_confirmed.md §C] <b>캐릭터 나이 19+ 런타임 게이트</b>.
     * 어드민 승인(secretEligible)만으로는 부족하다 — 승인 판정과 무관하게 매 요청 재판정되어야
     * 승인 후 나이가 하향 수정되는 역방향도 다음 요청부터 자동 차단된다(블록 B 페르소나 게이트와 동형).
     *
     * <p><b>age == null은 유예(통과)한다.</b> 기존 UGC 캐릭터는 age가 배선된 적이 없어 전부 null이고
     * (안건 9-D가 이번에 배선했다), 여기서 null을 막으면 <b>배포 순간 기존 승인 캐릭터의 시크릿이
     * 전면 차단</b>되어 유료 해금 보유 유저가 산 것을 잃는다(decisions_confirmed §C-E).
     * 즉 이 게이트는 <b>신규 생성분부터</b> 강제된다. 백필 정책은 종원 미결 —
     * 백필이 끝나면 이 null 유예를 제거해야 게이트가 완결된다.
     *
     * <p>⚠ <b>적용 범위</b>: 이 함수는 V1 SANDBOX·엔딩·V1 씬일러 축만 덮는다.
     * V2 STORY 4곳(ChatStreamServiceV2:176·:1157 · StoryV2Service:847 · SceneRequestService:230)은
     * 1-arg canAccessSecretMode + world.secretAllowed만 보고 캐릭터 자격을 조회하지 않는다(안건 9-A′ 미착수).
     */
    public boolean isCharacterSecretEligible(Long characterId) {
        return characterRepository.findById(characterId)
            .map(c -> c.isSecretEligible() && isCharacterAgeAllowed(c))
            .orElse(false);
    }

    /** [안건 9-A] 나이 판정 — null은 유예(위 javadoc의 배포 함정), 값이 있으면 19+만 허용. */
    private boolean isCharacterAgeAllowed(Character c) {
        Integer age = c.getAge();
        return age == null || age >= com.spring.aichat.service.ugc.UgcModerationService.MIN_CHARACTER_AGE;
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
        // [블록 B 페르소나 — docs/14 #4 절대선] 라이브 활성 프로필 나이 19+ 하드 게이트.
        // 모든 활성 경로(유저/방 토글)와 매턴 재판정(V1/V2 resolveSecretMode)이 이 관문을
        // 지나므로, 활성 후 나이를 하향 수정하는 역방향도 다음 요청부터 자동 차단된다.
        if (!isPersonaAdult(user.getId())) {
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

    /** [블록 B] 페르소나 프로필 나이 19+ 여부 — 프로필 미존재·나이 미설정도 false(하드 게이트). */
    public boolean isPersonaAdult(Long userId) {
        return userPersonaService.isProfileAdult(userId);
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
    //  환불 회수 (Phase 6) — 주문번호(merchantUid)로 지급 레코드 역처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 24h 패스 회수 — RDB 레코드 삭제 + Redis 캐시 무효화. */
    @Transactional
    public void revoke24hPassByMerchantUid(String merchantUid) {
        secretPassRepository.findByMerchantUid(merchantUid).ifPresent(pass -> {
            Long userId = pass.getUser().getId();
            Long charId = pass.getCharacter().getId();
            secretPassRepository.delete(pass);
            cacheService.evict(buildPassCacheKey(userId, charId));
            log.info("[SECRET] 24h pass revoked (refund): merchantUid={}, userId={}, charId={}",
                merchantUid, userId, charId);
        });
    }

    /** 영구 해금 회수 — RDB 레코드 삭제. */
    @Transactional
    public void revokePermanentUnlockByMerchantUid(String merchantUid) {
        secretUnlockRepository.findByMerchantUid(merchantUid).ifPresent(unlock -> {
            secretUnlockRepository.delete(unlock);
            log.info("[SECRET] Permanent unlock revoked (refund): merchantUid={}", merchantUid);
        });
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
            return new SecretModeStatus(false, false, false, false, false, false,
                "NEED_ADULT_VERIFY", secretProductsEnabled);
        }

        // [블록 B] 페르소나 나이 게이트 — 인증·구매보다 먼저 안내(FE 프로필 수정 제안 모달)
        boolean personaAdult = isPersonaAdult(user.getId());
        boolean midnightPass = hasMidnightPass(user.getId());
        boolean anyPermanentUnlock = hasAnyPermanentUnlock(user.getId());
        boolean anyActive24hPass = hasAnyActive24hPass(user.getId());
        boolean entitled = midnightPass || anyPermanentUnlock || anyActive24hPass;
        boolean canAccess = personaAdult && entitled;

        String reason = !personaAdult ? "PERSONA_UNDERAGE"
            : (entitled ? "GRANTED" : "NEED_PURCHASE");
        return new SecretModeStatus(true, personaAdult, midnightPass, anyPermanentUnlock,
            anyActive24hPass, canAccess, reason, secretProductsEnabled);
    }

    /**
     * [docs/13 C-3 픽스] {@code canAccess}를 명시 컴포넌트로 승격 — record 파생 메서드는
     * Jackson이 직렬화하지 않아 FE(SecretModeFlow)가 undefined를 읽던 확정 버그.
     */
    public record SecretModeStatus(
        boolean isAdult,
        boolean personaAdult,    // [블록 B] 페르소나 프로필 19+ 여부
        boolean hasMidnightPass,
        boolean hasPermanentUnlock,
        boolean has24hPass,
        boolean canAccess,
        String accessReason,
        // [docs/19 안건 7 = (b)] 시크릿 상품 판매·노출 허용 여부 (= bm.secret-products-enabled).
        //   false면 FE는 시크릿/미드나잇 패스 상품 카드·탭을 렌더하지 않는다. 서버는 이 값과
        //   무관하게 독립적으로 prepareOrder에서 400을 던진다 — FE는 UX일 뿐 게이트가 아니다.
        //   미인증 유저에게도 내려간다(NEED_ADULT_VERIFY 분기 포함) — 상품 카드 노출은
        //   성인 인증 여부와 독립된 롤아웃 축이기 때문이다.
        boolean secretProductsEnabled
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Internal
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildPassCacheKey(Long userId, Long characterId) {
        return SECRET_PASS_PREFIX + userId + ":" + characterId;
    }
}