# Phase 6 — 정적 분석 이슈 보고서

> Lucid Chat 코드베이스 전수 정적 분석 결과. R1~R5 라운드별 분석을 통해 발견된 보안·정합성·자원 누수·동시성·프론트엔드 이슈 총 **71건** 정리.

---

## 0. 통계 및 우선순위 매트릭스

### 0.1. 라운드별 통계

| Severity | R1 보안 | R2 결제 | R3 SSE/자원 | R4 정합성 | R5 프론트 | **합계** |
|----------|--------|--------|------------|----------|----------|---------|
| Critical | 4 | 2 | 2 | 1 | 1 | **10** |
| High | 7 | 7 | 4 | 5 | 4 | **27** |
| Medium | 7 | 6 | 6 | 7 | 6 | **32** |
| Low | 2 | 0 | 0 | 0 | 0 | **2** |
| **Total** | **20** | **15** | **12** | **13** | **11** | **71** |

### 0.2. 패치 Tier 분류 (의존 순서 반영)

| Tier | 이슈 ID | 작업명 | 예상 시간 |
|------|---------|-------|----------|
| **0 — 배포 차단** | C-10 | baseURL `import.meta.env` 활성화 | 5분 |
| **1A — 보안** | C-1, C-3 | CORS 화이트리스트 + RT 쿠키 Secure | 30분 |
| **1B — 권한 우회** | C-4, C-7, C-8 | `@PreAuthorize` 누락 3건 | 15분 |
| **1C — 결제 사기** | C-5, C-6 | 웹훅 서명 + merchant_uid 검증 | 1.5~2시간 |
| **2 — 인증 정합성** | C-2, H-1, H-2, H-3, H-24 | 토큰 블랙리스트 필터 + role 동적 + refresh single-flight | 2~3시간 |
| **3 — 데이터 정합성** | C-9, H-19, H-20, H-21, H-23 | @Version + ASSISTANT log retry + Order EXPIRED 핸들러 | 4~6시간 |
| **4 — 자원 누수** | H-15~H-18, H-25~H-27 | 부스트 정책 + Async executor + 프론트 cleanup | 3~4시간 |
| **5 — 운영 품질** | 나머지 Medium/Low | 단계적 정리 | 단계적 |

---

## R1 — 보안 · 인증 표면

### 🔴 Critical

#### C-1. CORS 와일드카드 + Credentials 동시 허용

**위치**: `SecurityConfig.java:96, 100`
```java
configuration.setAllowedOriginPatterns(List.of("*"));
configuration.setAllowCredentials(true);
```
**현상**: 모든 origin이 credentials(쿠키·Authorization 헤더)를 포함해 우리 API를 호출 가능. HttpOnly Refresh Token 쿠키가 임의 외부 사이트에서 동작 가능. CSRF 방어 무력화.

**위험 시나리오**: 공격자가 만든 사이트에 피해자가 방문하면, 그 사이트의 JS가 `credentials: 'include'`로 우리 API에 요청 → 피해자 쿠키 자동 전송 → 임의 액션 실행.

**수정**:
```java
// dev/prod profile 분리
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    if (Arrays.asList(env.getActiveProfiles()).contains("prod")) {
        configuration.setAllowedOrigins(List.of(
            "https://lucidchat.com",
            "https://www.lucidchat.com"
        ));
    } else {
        configuration.setAllowedOriginPatterns(List.of("*"));
    }
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
    configuration.setAllowedHeaders(List.of(
        "Authorization", "Content-Type", "X-Requested-With", "Accept", "Cache-Control"
    ));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}
```

---

#### C-2. 토큰 블랙리스트 필터 미적용

**위치**: `JwtTokenService.isBlacklisted()` 메서드는 존재(라인 126), `SecurityConfig`에 호출 필터 부재.

**현상**: `/auth/logout` 호출 후에도 access token으로 모든 API 호출 가능 (TTL 만료 전까지). 로그아웃이 사실상 클라이언트 측 토큰 폐기에만 의존.

**수정**: `OncePerRequestFilter` 추가 + `SecurityConfig`에 등록.
```java
@Component
@RequiredArgsConstructor
public class JwtBlacklistFilter extends OncePerRequestFilter {
    private final JwtTokenService jwtTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtTokenService.isBlacklisted(token)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token revoked");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

// SecurityConfig
http.addFilterBefore(jwtBlacklistFilter,
    BearerTokenAuthenticationFilter.class);
```

**연계 패치**: 블랙리스트 키를 `BL:{jti}` 패턴으로 변경 권장 (M-4 참조).

---

#### C-3. Refresh Token 쿠키 `Secure=false` + SameSite 미설정

**위치**: `AuthController.java:132, 143`
```java
cookie.setSecure(false);
// SameSite 미설정
```

**현상**: HTTP 평문 통신에서도 쿠키 전송됨. MitM 공격으로 RT 탈취 → 무제한 access token 재발급 가능.

**수정**: `ResponseCookie` 사용 + profile 기반 분기.
```java
private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
    boolean isProd = Arrays.asList(env.getActiveProfiles()).contains("prod");
    ResponseCookie cookie = ResponseCookie.from("refresh_token", refreshToken)
        .httpOnly(true)
        .secure(isProd)
        .sameSite("Strict")
        .path("/")
        .maxAge(props.refreshTokenTtlSeconds())
        .build();
    response.addHeader("Set-Cookie", cookie.toString());
}

// logout cookie도 동일 패턴으로
private void clearRefreshTokenCookie(HttpServletResponse response) {
    boolean isProd = Arrays.asList(env.getActiveProfiles()).contains("prod");
    ResponseCookie cookie = ResponseCookie.from("refresh_token", "")
        .httpOnly(true)
        .secure(isProd)
        .sameSite("Strict")
        .path("/")
        .maxAge(0)
        .build();
    response.addHeader("Set-Cookie", cookie.toString());
}
```

---

#### C-4. `AchievementController.unlockClientTriggered` — 권한 우회

**위치**: `AchievementController.java:39-43`
```java
@PostMapping("/rooms/{roomId}/unlock")
public UnlockNotification unlockClientTriggered(@PathVariable Long roomId, @RequestBody UnlockRequest request) {
    Long userId = authGuard.getCurrentUserId(roomId);  // 방 소유자 ID 반환 (호출자 무관)
    return achievementService.unlockClientTriggered(userId, request.code());
}
```

**현상**: `@PreAuthorize` 누락. `getCurrentUserId(roomId)`는 *방의 소유자* userId를 반환할 뿐 호출자(JWT principal)와 비교하지 않음. 유저 A가 유저 B의 roomId 알면 B 계정에 임의로 업적 해금 가능.

**수정**:
```java
@PostMapping("/rooms/{roomId}/unlock")
@PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
public UnlockNotification unlockClientTriggered(@PathVariable Long roomId, @RequestBody UnlockRequest request) {
    Long userId = authGuard.getCurrentUserId(roomId);
    return achievementService.unlockClientTriggered(userId, request.code());
}
```

---

### 🟠 High

#### H-1. `JwtTokenService.reissue` — Role 하드코딩 + 탈취 대응 부재

**위치**: `JwtTokenService.java:96`
```java
return issueTokenPair(username, "ROLE_USER");
```

**현상**:
1. ADMIN 권한 유저가 토큰 갱신 시 USER로 강등.
2. 탈취 감지(저장 토큰 불일치) 시 단순 예외만 던짐 — 모든 세션 무효화 안 함.

**수정**:
```java
public TokenPair reissue(String refreshToken) {
    Jwt jwt = jwtDecoder.decode(refreshToken);
    String username = jwt.getSubject();

    String storedToken = redisTemplate.opsForValue().get(REFRESH_PREFIX + username);
    if (storedToken == null || !storedToken.equals(refreshToken)) {
        // 탈취 감지 → 모든 세션 강제 로그아웃
        redisTemplate.delete(REFRESH_PREFIX + username);
        log.warn("[JWT] RT mismatch — possible theft. All sessions revoked: user={}", username);
        throw new IllegalArgumentException("유효하지 않거나 만료된 Refresh Token입니다.");
    }

    // DB에서 실제 role 조회
    User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    String role = user.getRoles().isEmpty() ? "ROLE_USER" : user.getRoles().iterator().next();

    return issueTokenPair(username, role);
}
```

---

#### H-2. `AuthService.signup/login` — Role 하드코딩

**위치**: `AuthService.java:61, 89`

**수정**: H-1과 동일 패턴 — `user.getRoles()`에서 추출.

---

#### H-3. `BCryptPasswordEncoder` 직접 인스턴스화

**위치**: `AuthService.java:39`
```java
private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
```

**수정**: `SecurityConfig`에 Bean 등록 + DI.
```java
// SecurityConfig
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);  // strength 명시
}

// AuthService
private final PasswordEncoder passwordEncoder;
```

---

#### H-4. `AuthGuard` 캐시 영구 저장의 stale 위험

**위치**: `AuthGuard.java:54` (영구 캐싱)

**현상**: ChatRoom 삭제·이전 시 `cacheService.evictRoomOwner(roomId)` 호출 누락 시 stale ownership.

**수정**:
1. `ChatService.deleteChatRoom` 등 모든 삭제 경로에서 evict 호출 강제.
2. 또는 `ChatRoom @PreRemove` 콜백 추가:
```java
@PreRemove
public void onPreRemove() {
    // 이벤트 발행 → 리스너에서 캐시 evict
}
```
3. 영구 캐싱 정책을 7~30일 TTL로 변경 (안전망).

---

#### H-5. Theater · 일러스트 · 감독명령어 Rate Limit 미적용

**위치**: `ApiRateLimiter.java`

**현상 (LLM 비용 폭탄 위험)**:
- `POST /theater/rooms/{}/next-batch` — 배치당 LLM 호출
- `POST /theater/rooms/{}/intervention/start` — 에너지 2 + LLM
- `POST /theater/rooms/{}/director-commands` — LLM 분류기
- `POST /illustrations/generate` — 에너지 10 + Fal.ai 큐
- `POST /theater/rooms/{}/branches/scene` — LLM

**수정**: 편의 메서드 추가.
```java
public boolean checkTheaterBatch(String username) {
    return isRateLimited("theater_batch", username, 1, 3);
}
public boolean checkIllustrationGenerate(String username) {
    return isRateLimited("illust_gen", username, 1, 30);  // 30초/1회 (엄격)
}
public boolean checkDirectorCommand(String username) {
    return isRateLimited("director_cmd", username, 2, 10);
}
public boolean checkBranchScene(String username) {
    return isRateLimited("branch_scene", username, 1, 5);
}
public boolean checkIntervention(String username) {
    return isRateLimited("intervention", username, 1, 5);
}
```
각 컨트롤러에서 호출 추가.

---

#### H-6. Login 에러 메시지가 계정 형태 노출

**위치**: `AuthService.java:79`
```java
throw new BadRequestException("해당 계정은 소셜 로그인으로 가입되었습니다. 구글 로그인을 이용하세요.");
```

**현상**: 공격자가 valid username/email + 가입 경로(소셜/로컬) enumeration 가능.

**수정**:
```java
if (user.getProvider() != AuthProvider.LOCAL) {
    log.info("[LOGIN] Attempt on social account: user={}", req.username());
    throw new NotFoundException("아이디 또는 비밀번호가 올바르지 않습니다.");  // 통일
}
```
별도 안내가 필요하면 로그인 폼 옆에 *상시 안내문* 배치 (예: "구글로 가입하셨다면 구글 로그인을 이용하세요").

---

#### H-7. `extractClientIp` X-Forwarded-For 무조건 신뢰

**위치**: `AuthController.java:149-155`

**현상**: 클라이언트가 임의로 XFF 헤더 위조 시 첫 값이 채택됨. ALB 환경에서 ALB가 헤더 추가 모드면 위조 값이 첫 자리.

**수정**: trusted proxy 화이트리스트 + XFF 마지막 값 사용.
```java
private String extractClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
        // ALB는 마지막에 client IP를 append. trusted proxy 기준으로 마지막 hop 신뢰
        String[] hops = xff.split(",");
        return hops[hops.length - 1].trim();
    }
    return request.getRemoteAddr();
}
```

---

### 🟡 Medium

#### M-1. `/actuator/**` 전체 permitAll
**위치**: `SecurityConfig.java:55`
**수정**:
```java
.requestMatchers("/actuator/health", "/actuator/info").permitAll()
.requestMatchers("/actuator/**").hasRole("ADMIN")
```

#### M-2. CORS `setAllowedHeaders("*")`
**수정**: C-1 패치에 포함됨.

#### M-3. JWT issuer/audience 검증 미흡
**위치**: `JwtConfig.java`
**수정**:
```java
@Bean
public JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
        .macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefault(),
        new JwtIssuerValidator(props.issuer())
    ));
    return decoder;
}
```

#### M-4. 블랙리스트 키 비효율 (`BL:{accessToken전체}`)
**수정**: JWT 발급 시 `jti` 클레임 추가, 블랙리스트는 `BL:{jti}`.
```java
// JwtTokenService.generateAccessToken
JwtClaimsSet claims = JwtClaimsSet.builder()
    .id(UUID.randomUUID().toString())  // jti
    .issuer(props.issuer())
    ...
    .build();

// logout / isBlacklisted
String jti = jwt.getId();
redisTemplate.opsForValue().set(BLACKLIST_PREFIX + jti, "logout", ttl, SECONDS);
```

#### M-5. ApiRateLimiter Redis 장애 시 fail-open
**위치**: `ApiRateLimiter.java:101-106`
**수정**: 엔드포인트별 정책 분리.
```java
public boolean isRateLimited(String endpoint, String identifier, int max, int window, boolean failClosed) {
    try { ... }
    catch (Exception e) {
        log.error(...);
        return failClosed;  // 결제·채팅은 true(차단), 비핵심은 false
    }
}
// 채팅/결제: failClosed=true. 일반: false.
```

#### M-6. `logout` 만료 access token 케이스
**위치**: `AuthController.java:118-138`
**현상**: 만료 토큰 시 username 추출 실패 → RT 키 삭제 누락.
**수정**: cookie의 RT에서 username 추출 시도 또는 `decodeIgnoreExpired` 메서드 추가.

#### M-7. `refresh` 토큰 부재 시 IllegalArgumentException
**위치**: `AuthController.java:65-67`
**수정**:
```java
if (refreshToken == null) {
    throw new BusinessException(ErrorCode.UNAUTHORIZED, "Refresh Token이 없습니다.");
}
```

### 🟢 Low

- **L-1**: PromptInjectionGuard 일부 패턴 ReDoS 가능성. 입력 길이 제한이 있어 실질 위험 낮음. 모니터링만.
- **L-2**: 로그인 Rate Limit IP+username 두 축 적용 권장. 현재는 IP만.

---

## R2 — 결제 · 권한 자산

### 🔴 Critical

#### C-5. PortOne 웹훅 서명 검증 부재

**위치**: `PaymentController.java:60-90` + `PaymentService.processWebhook`

**현상**: 웹훅 SecurityConfig permitAll. PortOne의 HMAC/서명 헤더(`x-iamport-signature`) 검증 없음. 외부에서 임의 페이로드 POST 가능.

**위험**: C-6과 결합되어 결제 사기 성립.

**수정**: PortOne v1 서명 검증 (또는 v2면 HMAC-SHA256).
```java
// PaymentController
@PostMapping("/webhook")
public ResponseEntity<Void> webhook(
    @RequestBody String rawBody,
    @RequestHeader(value = "x-iamport-signature", required = false) String signature
) {
    if (!paymentService.verifyWebhookSignature(rawBody, signature)) {
        log.error("[WEBHOOK] Invalid signature");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    // 기존 처리 흐름
    JsonNode payload = objectMapper.readTree(rawBody);
    paymentService.processWebhook(
        payload.path("imp_uid").asText(),
        payload.path("merchant_uid").asText()
    );
    return ResponseEntity.ok().build();
}

// PaymentService
public boolean verifyWebhookSignature(String rawBody, String signature) {
    if (signature == null) return false;
    String expected = HmacUtils.hmacSha256Hex(props.getWebhookSecret(), rawBody);
    return MessageDigest.isEqual(
        signature.getBytes(StandardCharsets.UTF_8),
        expected.getBytes(StandardCharsets.UTF_8)
    );
}
```

---

#### C-6. PortOne paymentInfo의 `merchant_uid` 미검증 — **결제 사기**

**위치**: `PaymentService.java:148-173`

**시나리오**:
1. 공격자가 본인 계정에서 ENERGY_T1 결제 → impUid=X 발급.
2. 피해자가 ENERGY_T1 주문(`merchantUid=B`) → PENDING.
3. 공격자가 webhook에 `{imp_uid: X, merchant_uid: B}` 전송.
4. `getPaymentInfo(X)` 응답: `status=paid, amount=1500`.
5. 코드는 amount만 검증 → B 주문 PAID 마킹 → **피해자에게 30 에너지 지급**.

**수정**:
```java
private PaymentResultResponse verifyAndDeliver(Order order, String impUid, String caller) {
    // ... 기존 status 체크 ...

    JsonNode paymentInfo = portOneClient.getPaymentInfo(impUid);
    int paidAmount = paymentInfo.path("amount").asInt();
    String portOneStatus = paymentInfo.path("status").asText();
    String paymentMerchantUid = paymentInfo.path("merchant_uid").asText();

    // ✅ ADD: merchant_uid 검증 (결제 사기 차단)
    if (!paymentMerchantUid.equals(order.getMerchantUid())) {
        log.error("[FRAUD] merchant_uid mismatch | order={} | payment={} | impUid={}",
            order.getMerchantUid(), paymentMerchantUid, impUid);
        throw new BusinessException(ErrorCode.PAYMENT_VERIFICATION_FAILED,
            "Merchant UID mismatch — possible fraud attempt");
    }

    // ... 기존 amount 검증 ...
}
```

---

### 🟠 High

#### H-8. 환불 후 `markFailed` 영속 실패 (트랜잭션 롤백)

**위치**: `PaymentService.java:163-172`

**현상**: `jakarta.transaction.@Transactional`은 RuntimeException 시 롤백. `cancelPayment` 후 `markFailed → save → throw` 흐름에서 throw로 인해 markFailed가 *DB에 저장 안 됨*. PortOne에서는 환불됐는데 DB는 PENDING으로 남음.

**수정**: `Propagation.REQUIRES_NEW` 별도 트랜잭션 또는 이벤트 패턴.
```java
@Service
@RequiredArgsConstructor
public class PaymentFailureRecorder {
    private final OrderRepository orderRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markOrderFailed(Long orderId, String reason) {
        Order o = orderRepository.findById(orderId).orElseThrow();
        o.markFailed(reason);
        orderRepository.save(o);
    }
}

// PaymentService
if (paidAmount != order.getAmount()) {
    try {
        portOneClient.cancelPayment(impUid, paidAmount, "Amount mismatch - auto refund");
    } catch (Exception e) {
        log.error("[PAYMENT] Auto-refund failed", e);
    } finally {
        paymentFailureRecorder.markOrderFailed(order.getId(),
            "Amount mismatch: expected=" + order.getAmount() + " actual=" + paidAmount);
    }
    throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, ...);
}
```

---

#### H-9. `SECRET_UNLOCK_PERMANENT` 이중 결제 race condition

**위치**: `PaymentService.java:60-69` (prepareOrder)

**현상**: `hasPermanentUnlock` 체크는 *완료된 unlock*만. PENDING 상태 주문은 무시.

**수정**:
```java
if (product == ProductType.SECRET_UNLOCK_PERMANENT) {
    if (request.targetCharacterId() == null) throw ...;
    if (secretModeService.hasPermanentUnlock(user.getId(), request.targetCharacterId())) {
        throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED, ...);
    }
    // ADD: PENDING 주문 체크
    if (orderRepository.existsByUser_IdAndProductTypeAndTargetCharacterIdAndStatus(
            user.getId(), product, request.targetCharacterId(), OrderStatus.PENDING)) {
        throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
            "이미 진행 중인 주문이 있습니다.");
    }
}
```

OrderRepository에 메서드 추가 필요.

---

#### H-10. 구독 만료 시 `freeEnergy` 클램핑 누락

**위치**: `SubscriptionService.java:86-94`

**현상**: 구독 중 freeEnergy=100까지 차오른 유저의 구독이 만료되면 비구독 max(30) 초과 상태로 남음. `regenEnergy`에서 `Math.min(getFreeEnergyMax(), ...)` → 30으로 강제 클램프 → **순간 70 손실**.

**수정**:
```java
@Transactional
public void deactivateExpired() {
    int subCount = subscriptionRepository.deactivateExpiredSubscriptions(LocalDateTime.now());
    if (subCount > 0) {
        log.info("[SUB] Deactivated {} expired subscriptions", subCount);
        userRepository.clearExpiredSubscriptionTiers();
        // ADD: freeEnergy 클램핑
        int clampCount = userRepository.clampFreeEnergyForNonSubscribers(30);
        log.info("[SUB] Clamped freeEnergy for {} users", clampCount);
    }
}

// UserRepository
@Modifying
@Query("UPDATE User u SET u.freeEnergy = :max WHERE u.subscriptionTier IS NULL AND u.freeEnergy > :max")
int clampFreeEnergyForNonSubscribers(@Param("max") int max);
```

---

#### H-11. 트랜잭션 commit 전 캐시 evict (Read-After-Evict-Before-Commit)

**위치**: `SubscriptionService.java:77`, `PaymentService.java:178`, `VerificationService.java:149`, `ChatStreamService.java:189` 등

**현상**: evict 후 commit 전에 다른 요청이 cache miss → DB에서 *commit 전 값* 읽어 다시 캐싱 → stale 캐시 영구화.

**수정**: `TransactionSynchronization`로 afterCommit에 evict.
```java
// 공통 helper
@Component
public class TransactionalCacheEvictor {
    private final RedisCacheService cacheService;

    public void evictUserProfileAfterCommit(String username) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        cacheService.evictUserProfile(username);
                    }
                });
        } else {
            cacheService.evictUserProfile(username);  // TX 외부 호출 fallback
        }
    }

    public void evictRoomInfoAfterCommit(Long roomId) { /* 동일 패턴 */ }
}
```

호출처 변경: `cacheService.evictUserProfile(username)` → `cacheEvictor.evictUserProfileAfterCommit(username)`.

---

#### H-12. CI hash salt/pepper 부재

**위치**: `VerificationService.java:180-188`

**현상**: 단순 SHA-256. DB 유출 시 rainbow table 공격 가능.

**수정**:
```java
// application.yml
verification:
  ci-pepper: ${CI_PEPPER}  // 환경변수에서 주입

// VerificationService
@Value("${verification.ci-pepper}")
private String ciPepper;

private String hashSHA256(String input) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest((ciPepper + input).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    } catch (Exception e) { ... }
}
```

⚠️ **마이그레이션 필요**: 기존 ciHash는 그대로 둘 수 없음. 기존 데이터 마이그레이션 스크립트 또는 점진 재인증 정책.

---

#### H-13. `LocalDate.now()` timezone 미지정

**위치**: `VerificationService.java:168`

**수정**:
```java
private static final ZoneId KST = ZoneId.of("Asia/Seoul");

private boolean isAdult(String birthdate) {
    try {
        LocalDate birth = LocalDate.parse(birthdate, DateTimeFormatter.ofPattern("yyyyMMdd"));
        long age = ChronoUnit.YEARS.between(birth, LocalDate.now(KST));
        return age >= ADULT_AGE;
    } catch (Exception e) { ... }
}
```

전체 프로젝트에서 `LocalDate.now()` / `LocalDateTime.now()` 검색 후 timezone 명시 추가.

---

#### H-14. 동시 CI 등록 race condition

**위치**: `VerificationService.java:137-146`

**수정**:
```java
try {
    user.completeAdultVerification(ciHash);
    userRepository.save(user);
} catch (DataIntegrityViolationException e) {
    log.warn("[VERIFY] Concurrent CI registration: user={}, ciHash={}",
        username, ciHash.substring(0, 8) + "...");
    throw new BusinessException(ErrorCode.VERIFICATION_DUPLICATE_CI,
        "이미 다른 계정에서 인증된 정보입니다.");
}
```

---

### 🟡 Medium

#### M-8. PortOne `getAccessToken` 캐싱 부재
**수정**: Redis 캐시 (`portone:access_token`, TTL 25분).
```java
public String getAccessToken() {
    return cacheService.getString("portone:access_token")
        .orElseGet(() -> {
            String token = fetchNewToken();
            cacheService.setWithTTL("portone:access_token", token, 25 * 60);
            return token;
        });
}
```

#### M-9. `targetCharacterId` 존재 검증 누락
**위치**: `PaymentService.prepareOrder`
**수정**: prepareOrder에 추가.
```java
if (request.targetCharacterId() != null
    && !characterRepository.existsById(request.targetCharacterId())) {
    throw new BusinessException(ErrorCode.NOT_FOUND, "Character not found");
}
```

#### M-10. EnergyRegenScheduler bulk update + lost update
**현상**: User에 @Version 없음 → 동시 처리 시 lost update.
**수정**: H-19와 함께 처리 (User에 @Version 추가). bulk update는 native query라 @Version 우회. 별도 native update for 캐시 invalidation.

#### M-11. SubscriptionService bulk update의 user/subscription 정합성
**위치**: `deactivateExpired`
**수정**: 단일 트랜잭션 + affected userId 수집.
```java
@Transactional
public void deactivateExpired() {
    List<Long> expiredUserIds = subscriptionRepository.findExpiredUserIds(LocalDateTime.now());
    if (expiredUserIds.isEmpty()) return;

    subscriptionRepository.deactivateByIds(expiredUserIds);
    userRepository.clearSubscriptionTiersByUserIds(expiredUserIds);
    userRepository.clampFreeEnergyByUserIds(expiredUserIds, 30);
    expiredUserIds.forEach(id -> /* evict cache */);
}
```

#### M-12. `requestToken` Rate Limit 미적용
**수정**: `ApiRateLimiter.checkVerification(username)` (60초/3회) 추가, VerificationController에서 호출.

#### M-13. `birthdate` 로그 노출
**위치**: `VerificationService.java:171`
**수정**:
```java
log.error("[VERIFY] 생년월일 파싱 실패 (length={})", birthdate != null ? birthdate.length() : 0, e);
```

---

## R3 — 자원 누수 · SSE · 외부 API

### 🔴 Critical

#### C-7. `ChatController.sendStream` — `@PreAuthorize` 누락 (권한 우회)

**위치**: `ChatController.java:55-76`

**현상**: 메서드에 `@PreAuthorize` 없음. `ChatStreamService.sendMessageStream`도 owner check 없음(`findWithMemberAndCharacterById`만 호출 후 그 방의 user 에너지 차감).

**위험 시나리오**: 유저 A가 본인 JWT로 `/api/v1/chat/rooms/{유저B의roomId}/messages/stream` POST → **B의 에너지 차감 + B 방에 메시지 저장 + B 캐릭터의 LLM 응답 발생**.

**수정**:
```java
@PostMapping(value = "/rooms/{roomId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")  // ✅ ADD
public SseEmitter sendStream(...) { ... }
```

---

#### C-8. `EndingController.generateEnding` — `@PreAuthorize` 누락

**위치**: `EndingController.java:27-34`

**현상**: 권한 검증 없이 임의 roomId 엔딩 생성 가능. EndingService가 LLM 두 번 호출(엔딩 씬 + 메모리 시적 변환). 비용 폭탄 + 데이터 노출.

**수정**:
```java
@PostMapping("/generate")
@PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")  // ✅ ADD
public EndingResponse generateEnding(...) { ... }
```

---

### 🟠 High

#### H-15. Theater 배치에 부스트 모드 비용 정책 미반영

**위치**: `TheaterService.java:359-364`

**현상**: `chargeBatchEnergy`가 `BoostModeResolver.resolveEnergyCost`를 사용하지 않고 `ChatMode.THEATER.getBaseCost()`(=1) 직접 호출.

**수정**: 정책 결정 후 둘 중 택일.
- (a) Theater에서도 부스트 비용 적용:
```java
private void chargeBatchEnergy(String username) {
    User user = userRepository.findByUsername(username).orElseThrow(...);
    int cost = boostModeResolver.resolveEnergyCost(ChatMode.THEATER, user);
    user.consumeEnergy(cost);
}
```
- (b) 의도적으로 Theater는 base 고정이라면 명시 주석 추가.

---

#### H-16. `IllustrationService.requestIllustration` — 외부 큐 제출 후 부분 실패 시 비용 누수

**위치**: `IllustrationService.java:67-89`

**현상**: `submitToQueue` 외부 호출 성공 후 illustrationRepository.save() 실패 → TX 롤백 → 에너지 환불됨. 그러나 Fal.ai 큐는 이미 제출 → 외부 비용 발생, orphan request.

**수정**: PENDING 레코드를 외부 호출 *전*에 저장.
```java
@Transactional
public IllustrationRequestResult requestIllustration(String username, Long roomId) {
    // ... 권한 / 에너지 차감 ...

    // ✅ ADD: PENDING 레코드 먼저 저장
    UserIllustration illust = UserIllustration.createInitial(
        user, character.getId(), character.getName(), positivePrompt, "MANUAL", emotion, location, outfit
    );
    illustrationRepository.save(illust);  // commit 전 영속성 컨텍스트

    // 외부 API 호출
    QueueResponse queueResp;
    try {
        queueResp = falAiClient.submitToQueue(workflow);
    } catch (Exception e) {
        // 외부 호출 실패 → TX 롤백, 레코드 함께 롤백, 에너지 환불 자연 발생
        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "Image generation failed", e);
    }

    illust.attachQueueInfo(queueResp.requestId(), queueResp.statusUrl(), queueResp.responseUrl());
    return new IllustrationRequestResult(queueResp.requestId(), illust.getId(), "PENDING");
}
```

---

#### H-17. `BackgroundGenerationService` — `@Async` 기본 executor 사용

**위치**: `BackgroundGenerationService.java:102, 95` 등

**현상**: executor 미지정 → SimpleAsyncTaskExecutor → 매 호출 새 스레드 → OOM 위험 (스레드 ~1MB 스택). 게다가 `pollUntilComplete` 최대 3분 점유.

**수정**: 전용 executor Bean 정의.
```java
// AsyncConfig
@Bean("backgroundGenExecutor")
public Executor backgroundGenExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("bg-gen-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
}

@Bean("illustrationExecutor")
public Executor illustrationExecutor() { /* 동일 패턴 */ }

// 호출처
@Async("backgroundGenExecutor")
public CompletableFuture<String> generateBackgroundAsync(...) { ... }

@Async("illustrationExecutor")
public void generateAutoIllustration(...) { ... }
```

`IllustrationService`의 모든 `@Async`도 동일 적용.

---

#### H-18. SSE emitter timeout(120초) vs LLM timeout(120초) 동기화 문제

**위치**: `ChatController.java:65`, `OpenRouterStreamClient.java:134`

**현상**: 동일 timeout이면 LLM이 119초만에 응답해도 final_result 전송 시 SSE 만료. 클라이언트에서 timeout.

**수정**: SSE timeout = LLM timeout + 30초 buffer.
```java
SseEmitter emitter = new SseEmitter(150_000L);  // 150초
```

`StoryController.java`의 모든 SseEmitter도 동일 적용 (라인 107, 130, 147, 164).

---

### 🟡 Medium

#### M-14. `OpenRouterStreamClient` — 비-200 응답 시 InputStream close 누락
**위치**: `OpenRouterStreamClient.java:208-214`
**수정**:
```java
if (response.statusCode() != 200) {
    String errorBody;
    try (InputStream body = response.body()) {
        errorBody = new String(body.readAllBytes());
    }
    throw new ExternalApiException(...);
}
```

#### M-15. `OpenRouterStreamClient.ttftWatchdog` 단일 스레드
**수정**: `Executors.newScheduledThreadPool(2)`로 확장.

#### M-16. `IllustrationService.checkStatus` 폴링 외부 API Rate Limit 부재
**수정**: 결과 캐싱 (1초 TTL Redis) — 동일 requestId 동시 폴링이 한 번의 외부 호출로 합쳐지도록.
```java
public IllustrationStatusResult checkStatus(String requestId, String username) {
    // ... 권한 검증 ...
    String cacheKey = "illust:poll:" + requestId;
    Optional<String> cached = cacheService.getString(cacheKey);
    if (cached.isPresent()) {
        return objectMapper.readValue(cached.get(), IllustrationStatusResult.class);
    }
    // 실제 폴링
    PollResult poll = falAiClient.pollStatus(illust.getStatusUrl());
    // ... 결과 처리 후 1초 TTL 캐싱 ...
}
```

#### M-17. `IllustrationService.handleCompletion` 멱등성 race condition
**현상**: 폴링 + 웹훅 동시 도착 시 양쪽 isCompleted=false 통과 → S3 업로드 2회.
**수정**: status PENDING → PROCESSING atomic update, affected rows 검증.
```java
@Modifying
@Query("UPDATE UserIllustration u SET u.status = 'PROCESSING' " +
       "WHERE u.id = :id AND u.status = 'PENDING'")
int markProcessing(@Param("id") Long id);

// handleCompletion
int affected = illustrationRepository.markProcessing(illust.getId());
if (affected == 0) {
    log.info("[ILLUST] Already being processed by another worker: {}", illust.getId());
    return;
}
// 이후 S3 업로드 + 완료 처리
```

#### M-18. PromptInjection 차단 시 에너지 보상
**현상**: 현재는 로깅만이라 OK. 정책 변경(차단 추가) 시 보상 패치 필요.

#### M-19. SSE 클라이언트 disconnect 후 LLM 응답 끝까지 소비
**수정**: 클라이언트 disconnect 감지 시 LLM stream cancel.
```java
emitter.onError(ex -> {
    log.warn("⚠️ [SSE] Client disconnected: roomId={}", roomId);
    // 진행 중인 LLM stream을 취소하는 신호
    streamCancellationRegistry.cancel(roomId);
});

// OpenRouterStreamClient.streamCompletion 진입 시 등록
// 매 chunk 처리 시 cancellation flag 체크
```

이는 큰 리팩토링이라 우선순위 낮음. 모니터링으로 우선 대응.

---

## R4 — 트랜잭션 · 정합성 · 동시성

### 🔴 Critical

#### C-9. ASSISTANT 메시지 저장 실패 시 정합성 파괴

**위치**: `ChatStreamService.java:322-336`

**현상 흐름**:
- TX-2 (JPA): 스탯/관계/엔딩/promotion 영속됨.
- MongoDB ASSISTANT save 실패: log만 남고 무시.
- SSE: final_result 정상 전송.
- **결과**: history 누락 → 다음 LLM 컨텍스트 손실 + 새로고침 시 응답 영구 손실 + 스탯 변화 원인 추적 불가.

**수정**: retry + deadletter.
```java
// 새 컴포넌트
@Component
@RequiredArgsConstructor
public class ChatLogPersister {
    private final ChatLogMongoRepository chatLogRepository;
    private final ChatLogDeadletterRepository deadletterRepo;

    @Retryable(
        retryFor = {DataAccessException.class, MongoException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 200, multiplier = 2)
    )
    public ChatLogDocument saveWithRetry(ChatLogDocument doc) {
        return chatLogRepository.save(doc);
    }

    @Recover
    public ChatLogDocument recover(Exception e, ChatLogDocument doc) {
        log.error("[CHAT-LOG] All retries failed, deadlettering | roomId={}", doc.getRoomId(), e);
        deadletterRepo.save(ChatLogDeadletter.from(doc, e.getMessage()));
        return null;
    }
}

// ChatStreamService 호출처
ChatLogDocument saved = chatLogPersister.saveWithRetry(assistantLog);
if (saved == null) {
    // 운영 alert + 유저에게는 정상 응답 (이미 SSE로 전송됨)
    monitoringService.alert("ASSISTANT_LOG_PERSIST_FAILED", roomId);
}
```

---

### 🟠 High

#### H-19. User 엔티티 `@Version` 부재 — Lost Update

**위치**: `User.java`

**시나리오**:
- 채팅 -2 (영속성 컨텍스트로 8) + 일러스트 -10 (영속성 -1) 동시 → 한쪽 무효화 또는 음수.
- 스케줄러 +1 + 채팅 -2 → 한쪽 무효화.

**수정**:
```java
@Version
@Column(name = "version", nullable = false)
private Long version = 0L;
```

⚠️ DB 마이그레이션: `ALTER TABLE users ADD COLUMN version BIGINT NOT NULL DEFAULT 0;`

⚠️ 기존 호출처에서 `OptimisticLockingFailureException` 발생 시 retry 정책 추가:
```java
@Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 50))
public void consumeEnergyWithRetry(Long userId, int amount) { ... }
```

---

#### H-20. `ChatRoom.findByIdForUpdate` 정의됐으나 미사용

**위치**: `ChatRoomRepository.java:25-27` 정의, 호출처 0건.

**현상**: 락 메서드 dead code. ChatRoom 내부 필드(affectionScore 등)가 동시 수정 시 lost update.

**수정**: 정책 결정.
- (a) 제거 (캐시-only 정책 명확화)
- (b) `ChatRoom`에 `@Version` 추가 → 모든 호출처 자동 보호
- (c) TX-2에서 `findByIdForUpdate` 사용 → 직렬화 (성능 영향)

권장 (b) — User 패치와 같은 패턴.

---

#### H-21. `TheaterState` 동시 수정 보호 부재

**위치**: `TheaterState.java`

**시나리오**: 같은 roomId 두 동시 next-batch 호출 (rate limit 미적용 H-5와 결합) → addScenes/advanceBatch 중복 → 같은 batchId 두 번 생성 + scenesInCurrentChapter 중복 증가.

**수정**: `@Version` 추가.
```java
@Version
@Column(name = "version", nullable = false)
private Long version = 0L;
```

---

#### H-22. `TheaterService.onBatchConsumed` — batchId mismatch 시 단순 warn

**위치**: `TheaterService.java:174-177`

**수정**: 명시적 분기.
```java
if (consumedBatchId != state.getCurrentBatchId()) {
    log.warn("🎭 [THEATER] Batch ID mismatch | expected={} | got={}",
        state.getCurrentBatchId(), consumedBatchId);
    // 정책 (a): 무시하고 server 기준 진행
    // 정책 (b): 차단
    throw new BusinessException(ErrorCode.STALE_CLIENT_STATE,
        "클라이언트 상태가 오래되었습니다. 새로고침 후 다시 시도해주세요.");
}
```

권장: (b). 클라이언트 stale 상태로 진행하면 진행 어긋남 누적 위험.

---

#### H-23. PortOne 웹훅 — Order EXPIRED 상태에서 paid 도착

**위치**: `PaymentService.java:141-146` + `OrderExpirationScheduler`

**시나리오**: 30분 후 EXPIRED → 31분 후 PortOne webhook (paid) 도착 → markPaid 실패 → PortOne paid + DB EXPIRED + 환불 미실행.

**수정**: EXPIRED + paid 케이스 분기.
```java
private PaymentResultResponse verifyAndDeliver(Order order, String impUid, String caller) {
    if (order.getStatus() == OrderStatus.PAID) { ... }

    // ✅ ADD: EXPIRED + paid 자동 환불
    if (order.getStatus() == OrderStatus.EXPIRED) {
        JsonNode paymentInfo = portOneClient.getPaymentInfo(impUid);
        if ("paid".equals(paymentInfo.path("status").asText())) {
            log.error("[PAYMENT] Late payment on EXPIRED order — auto refund | uid={}",
                order.getMerchantUid());
            try {
                portOneClient.cancelPayment(impUid, paymentInfo.path("amount").asInt(),
                    "Order expired, auto refund");
            } catch (Exception e) {
                log.error("[PAYMENT] Auto-refund failed for EXPIRED order", e);
                // alert: 운영자 수동 처리 필요
            }
        }
        throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
            "Order expired");
    }

    if (order.getStatus() != OrderStatus.PENDING) { ... }
    // ... 기존 흐름 ...
}
```

---

### 🟡 Medium

#### M-20. MongoDB + JPA 분산 트랜잭션 부재 (eventual consistency)
**현상**: 보상 트랜잭션이 일부 실패만 커버. C-9 패치 외에 운영 모니터링.
**수정**: 저장 실패율 metric + alert. 백오피스 복구 도구.

#### M-21. Cache evict 시점 — 트랜잭션 내부 호출
**수정**: H-11과 동일. `TransactionalCacheEvictor` 공통 helper로 일관 적용.

#### M-22. `cacheRoomOwner` 영구 캐시 invalidate 누락
**수정**: H-4와 동일.

#### M-23. EnergyRegenScheduler bulk update + Hibernate 1차 캐시
**수정**: H-19 패치 후, native bulk update와 @Version 호환성 검증. 최악의 경우 bulk 후 cache evict bulk 호출.

#### M-24. `Achievement.unlockClientTriggered` 동시 호출 멱등성
**수정**:
```java
try {
    achievementRepository.save(achievement);
} catch (DataIntegrityViolationException e) {
    log.info("[ACHIEVEMENT] Already unlocked (idempotent): user={}, code={}", userId, code);
    return existing;  // 이미 있으면 그것 반환
}
```

#### M-25. `TheaterSaveSlot` 동시 save 시 슬롯 충돌
**수정**: catch DataIntegrityViolationException 또는 PESSIMISTIC_WRITE.

#### M-26. `UserSubscription` 만료 갭 정합성
**현상**: `User.getFreeEnergyMax()`가 `subscriptionTier != null`만 체크. 만료된 구독자도 max 100.
**수정**: H-10 패치(clampFreeEnergyForNonSubscribers)와 함께 처리. 또는 `getFreeEnergyMax`를 active subscription 기반으로 변경.

---

## R5 — 프론트엔드

### 🔴 Critical

#### C-10. baseURL 하드코딩 — **프로덕션 빌드 작동 불능**

**위치**: 5곳
- `axios.js:6` — `baseURL: 'http://localhost:8080/api/v1'`
- `UseChatStream.js:10` — `const BASE_URL = 'http://localhost:8080/api/v1'`
- `UseChatStream.js:59, 93, 127, 157, 167` — 주석 처리된 fallback 5개 추가
- `LoginPage.jsx:38` — `const API_BASE = 'http://localhost:8080'`

**현상**: 프로덕션 빌드 → ERR_CONNECTION_REFUSED. 서비스 100% 중단.

**수정**:
```js
// axios.js
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1',
  ...
});

// UseChatStream.js
const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

// LoginPage.jsx
const API_BASE = import.meta.env.VITE_API_BASE_URL?.replace("/api/v1", "") || "http://localhost:8080";
```

**.env.production 작성 필요**:
```
VITE_API_BASE_URL=https://api.lucidchat.com/api/v1
```

CI/CD에서 환경변수 강제 (build-time fail-fast).

---

### 🟠 High

#### H-24. axios 인터셉터 — 동시 401 → refresh race condition

**위치**: `axios.js:24-66`

**현상**: 5개 API 동시 401 → 5개 동시 refresh → 첫 번째만 성공, 나머지는 storedToken 불일치 → 정상 유저 강제 로그아웃.

**수정**: single-flight pattern.
```js
let refreshPromise = null;

async function refreshOnce() {
    if (!refreshPromise) {
        refreshPromise = api.post('/auth/refresh')
            .finally(() => { refreshPromise = null; });
    }
    return refreshPromise;
}

api.interceptors.response.use(
    (response) => response,
    async (error) => {
        const originalRequest = error.config;

        if (error.response?.status === 401 && !originalRequest._retry) {
            originalRequest._retry = true;
            try {
                const res = await refreshOnce();  // ✅ single flight
                const { accessToken } = res.data;
                localStorage.setItem('accessToken', accessToken);
                originalRequest.headers.Authorization = `Bearer ${accessToken}`;
                return api(originalRequest);
            } catch (refreshError) {
                localStorage.removeItem('accessToken');
                localStorage.removeItem('user');
                localStorage.removeItem('roomId');
                window.location.href = '/login';
                return Promise.reject(refreshError);
            }
        }

        if (error.response?.status === 429) { /* ... */ }
        return Promise.reject(error);
        // ❌ DEAD CODE 제거: 원래 67-68 줄의 두 번째 return
    }
);
```

`UseChatStream.js`의 `tryRefreshToken`도 동일 single-flight 적용.

---

#### H-25. SSE 클라이언트 reader 자원 정리 누락

**위치**: `UseChatStream.js:222-264`

**수정**:
```js
const reader = response.body.getReader();
try {
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        // ... 기존 처리 ...
    }
} finally {
    try { await reader.cancel(); } catch {}
}
```

---

#### H-26. ChatPage / TheaterPlayPage — SSE AbortController 미사용

**위치**: `ChatPage.jsx`, `TheaterPlayPage.jsx`

**수정**: 컴포넌트 단위 abortController.
```jsx
// ChatPage.jsx
const abortRef = useRef(null);

useEffect(() => {
    return () => {
        // unmount 시 진행 중인 SSE abort
        abortRef.current?.abort();
    };
}, []);

const sendMessage = async () => {
    abortRef.current?.abort();  // 이전 요청 취소
    abortRef.current = new AbortController();
    try {
        await sendMessageStream(roomId, message, callbacks, abortRef.current);
    } catch (e) {
        if (e.name !== 'AbortError') throw e;
    }
};
```

`TheaterPlayPage.jsx`도 동일 패턴.

---

#### H-27. AudioEngine — SFX Audio 인스턴스 누수

**위치**: `AudioEngine.jsx:278-288`

**수정**:
```jsx
const sfxRefsArray = useRef([]);

useEffect(() => {
    if (!location || location === prevLocationRef.current) return;
    prevLocationRef.current = location;

    const sfxSrc = SFX_MAP[location];
    if (!sfxSrc) return;

    const sfx = new Audio(sfxSrc);
    sfx.volume = mutedRef.current ? 0 : masterRef.current * SFX_VOLUME_RATIO;

    const cleanup = () => {
        sfx.src = '';
        sfxRefsArray.current = sfxRefsArray.current.filter(a => a !== sfx);
    };

    sfx.addEventListener('ended', cleanup, { once: true });
    sfx.addEventListener('error', cleanup, { once: true });
    sfxRefsArray.current.push(sfx);

    sfx.play().catch(() => cleanup());
}, [location]);

// 전체 cleanup
useEffect(() => {
    return () => {
        if (bgmRef.current) {
            bgmRef.current.pause();
            bgmRef.current.src = '';
            bgmRef.current = null;
        }
        ambienceRefs.current.forEach(a => { a.pause(); a.src = ''; });
        ambienceRefs.current = [];
        sfxRefsArray.current.forEach(a => { a.pause(); a.src = ''; });
        sfxRefsArray.current = [];
    };
}, []);
```

---

### 🟡 Medium

#### M-27. axios 인터셉터 dead code
**위치**: `axios.js:67-68` 두 번째 `return Promise.reject(error)`
**수정**: H-24 패치에 포함. 두 번째 return 제거.

#### M-28. localStorage에 accessToken 저장 — XSS 취약
**현상**: 보안 vs UX trade-off. 출시 시점에는 유지, 추후 in-memory 패턴 검토.
**미루기 권장**: 추후 별도 마이그레이션 (refresh silent re-auth 패턴).

#### M-29. `useResourcePreloader.preloadAudio` 누수
**위치**: `UseResourcePreloader.js:83-94`
**수정**:
```js
function preloadAudio(src) {
    return new Promise((resolve) => {
        const audio = new Audio();
        const cleanup = (result) => {
            audio.src = "";
            audio.oncanplaythrough = null;
            audio.onerror = null;
            resolve(result);
        };
        const timer = setTimeout(() => cleanup(false), 10000);  // 10초 timeout
        audio.oncanplaythrough = () => { clearTimeout(timer); cleanup(true); };
        audio.onerror = () => { clearTimeout(timer); cleanup(false); };
        audio.preload = "auto";
        audio.src = src;
    });
}
```

#### M-30. `useTheaterStream` — unmount 후 setState
**위치**: `useTheaterStream.js:62, 145, 162`
**수정**:
```js
const isMountedRef = useRef(true);
useEffect(() => {
    return () => { isMountedRef.current = false; };
}, []);

// 모든 setState 호출 전 가드
if (isMountedRef.current) setCurrentBatch(batch);
```

#### M-31. AudioEngine — fadeOut 진행 중 중단 보호 부재
**수정**: 활성 fadeOut timer를 ref에 저장 후 새 변경 시 clearInterval.

#### M-32. AuthContext — 다중 탭 동기화 부재
**위치**: `AuthContext.jsx`
**수정**:
```jsx
useEffect(() => {
    const onStorage = (e) => {
        if (e.key === 'accessToken' && !e.newValue) {
            // 다른 탭에서 logout 발생
            setUser(null);
            window.location.href = '/login';
        }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
}, []);
```

---

## 부록 A — 패치 의존 관계 그래프

일부 이슈는 다른 패치의 선행을 요구한다.

```
C-10 (baseURL) ──→ 모든 프론트 패치의 전제

C-1 (CORS) ─────────────┐
C-3 (Cookie Secure) ────┼──→ Profile-aware 환경 설정 도입 필요
                        │
C-2 (Blacklist Filter) ─┼──→ M-4 (jti claim) 함께 패치
H-1 (Reissue role) ─────┤
H-2 (Auth role) ────────┘

H-19 (User @Version) ────→ M-10, M-23 자연 해결
H-20 (ChatRoom @Version) ─→ TX-2 race 자연 해결
H-21 (TheaterState @V) ───→ Theater 동시성 자연 해결

C-5 (Webhook signature) ─→ C-6 (merchant_uid) 의 전제 (둘 다 필수)

H-11 (TX cache evict) ───→ TransactionalCacheEvictor helper 도입
                          → 모든 evict 호출처 마이그레이션
```

## 부록 B — 미해결 / 출시 후 검토

| 이슈 | 사유 |
|------|------|
| M-19 (SSE disconnect → LLM cancel) | 큰 리팩토링. 모니터링 우선. |
| M-28 (localStorage XSS) | 보안/UX trade-off. v2 마이그레이션. |
| L-1 (Regex ReDoS) | 입력 길이 제한 있어 실질 위험 낮음. |

---

## 부록 C — 작업 시 주의

1. **DB 마이그레이션 동반 패치**: H-12 (CI pepper), H-19/H-20/H-21 (@Version) — 모두 schema 변경 필요. 운영 DB 백업 후 진행.
2. **profile 분리 필수**: C-1, C-3 — `application-prod.yml` 생성하여 운영 환경 변수 명확히.
3. **CI/CD 환경변수**: `VITE_API_BASE_URL`, `CI_PEPPER`, PortOne webhook secret — 모두 secret manager 등록.
4. **모니터링 연계**: C-9 deadletter, M-20 분산 TX 실패율 — Datadog/CloudWatch alert 설정.
