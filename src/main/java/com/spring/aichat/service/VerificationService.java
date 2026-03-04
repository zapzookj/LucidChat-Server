package com.spring.aichat.service.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.verification.VerificationCallbackRequest;
import com.spring.aichat.dto.verification.VerificationTokenResponse;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.external.NiceApiClient;
import com.spring.aichat.external.NiceApiClient.CryptoTokenResult;
import com.spring.aichat.service.cache.RedisCacheService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 성인 인증 서비스
 *
 * [안정성 핵심 설계]
 * 1. 인증 세션(key/iv)은 Redis에 5분 TTL로 저장 (메모리 누수 방지)
 * 2. CI 값은 SHA-256 해시 후 저장 (개인정보 최소화)
 * 3. 중복 CI 체크로 다중 계정 어뷰징 차단
 * 4. 모든 외부 API 호출은 try-catch 래핑 + 로깅
 *
 * [플로우]
 * requestToken() -> 프론트에서 NICE 팝업 -> verifyCallback() -> 성인 인증 완료
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VerificationService {

    private final NiceApiClient niceApiClient;
    private final UserRepository userRepository;
    private final RedisCacheService cacheService;

    private static final int ADULT_AGE = 19;
    private static final long SESSION_TTL_SECONDS = 300; // 5분
    private static final String VERIFY_SESSION_PREFIX = "verify:session:";

    /**
     * Step 1: NICE 인증 토큰 발급
     * - Access Token 발급 -> 암호화 토큰 발급
     * - key/iv를 Redis 세션에 저장 (프론트에 노출 금지)
     * - 프론트에 tokenVersionId, encData, integrityValue 반환
     */
    public VerificationTokenResponse requestToken(String username) {
        // 이미 인증된 유저 체크
        User user = findUserByUsername(username);
        if (Boolean.TRUE.equals(user.getIsAdult())) {
            throw new BusinessException(ErrorCode.VERIFICATION_ALREADY_DONE,
                "이미 성인 인증이 완료되었습니다.");
        }

        // 고유 요청번호 생성 (UUID 기반, 30자 제한)
        String requestNo = UUID.randomUUID().toString().replace("-", "").substring(0, 30);

        // NICE API 호출
        String accessToken = niceApiClient.getAccessToken();
        CryptoTokenResult result = niceApiClient.requestCryptoToken(accessToken, requestNo);

        // key/iv를 Redis에 저장 (5분 TTL)
        String sessionKey = VERIFY_SESSION_PREFIX + requestNo;
        String sessionValue = result.key() + "|" + result.iv();
        cacheService.setWithTTL(sessionKey, sessionValue, SESSION_TTL_SECONDS);

        log.info("[VERIFY] 인증 토큰 발급: user={}, requestNo={}", username, requestNo);

        return new VerificationTokenResponse(
            requestNo,
            result.tokenVersionId(),
            result.encData(),
            result.integrityValue()
        );
    }

    /**
     * Step 2: 인증 결과 검증 및 성인 인증 처리
     *
     * [검증 순서]
     * 1. Redis에서 세션(key/iv) 조회 (만료 체크)
     * 2. enc_data 복호화 -> 이름, 생년월일, CI 추출
     * 3. 만 19세 이상 판별
     * 4. CI 해시화 -> 중복 계정 체크
     * 5. User 엔티티에 isAdult=true, ciHash, adultVerifiedAt 저장
     */
    @Transactional
    public void verifyCallback(String username, VerificationCallbackRequest request) {
        // 1. Redis 세션 조회
        String sessionKey = VERIFY_SESSION_PREFIX + request.requestNo();
        String sessionValue = cacheService.getAndDelete(sessionKey);

        if (sessionValue == null) {
            throw new BusinessException(ErrorCode.VERIFICATION_EXPIRED,
                "인증 세션이 만료되었습니다. 다시 시도해주세요.");
        }

        String[] parts = sessionValue.split("\\|");
        if (parts.length != 2) {
            throw new BusinessException(ErrorCode.VERIFICATION_DECRYPT_FAILED,
                "인증 세션 데이터가 손상되었습니다.");
        }

        String key = parts[0];
        String iv = parts[1];

        // 2. 복호화
        JsonNode decrypted = niceApiClient.decryptResult(request.encData(), key, iv);

        String birthdate = decrypted.path("birthdate").asText();   // YYYYMMDD
        String ci = decrypted.path("ci").asText();
        String name = decrypted.path("name").asText();

        log.info("[VERIFY] 인증 결과 복호화 성공: user={}, name={}", username, maskName(name));

        // 3. 만 19세 이상 판별
        if (!isAdult(birthdate)) {
            log.warn("[VERIFY] 미성년자 인증 시도: user={}", username);
            throw new BusinessException(ErrorCode.VERIFICATION_UNDERAGE,
                "만 19세 미만은 시크릿 모드를 이용할 수 없습니다.");
        }

        // 4. CI 해시 -> 중복 체크
        String ciHash = hashSHA256(ci);

        if (userRepository.existsByCiHash(ciHash)) {
            log.warn("[VERIFY] 중복 CI 감지: user={}, ciHash={}", username, ciHash.substring(0, 8) + "...");
            throw new BusinessException(ErrorCode.VERIFICATION_DUPLICATE_CI,
                "이미 다른 계정에서 인증된 정보입니다. 다중 계정은 허용되지 않습니다.");
        }

        // 5. 유저 성인 인증 완료 처리
        User user = findUserByUsername(username);
        user.completeAdultVerification(ciHash);
        userRepository.save(user);

        // 캐시 무효화
        cacheService.evictUserProfile(username);

        log.info("[VERIFY] 성인 인증 완료: user={}", username);
    }

    // ── 내부 유틸 ──

    private User findUserByUsername(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    /**
     * 만 19세 이상 판별 (한국 나이 기준)
     * - 생년월일 기준으로 만 나이 계산
     */
    private boolean isAdult(String birthdate) {
        try {
            LocalDate birth = LocalDate.parse(birthdate, DateTimeFormatter.ofPattern("yyyyMMdd"));
            long age = ChronoUnit.YEARS.between(birth, LocalDate.now());
            return age >= ADULT_AGE;
        } catch (Exception e) {
            log.error("[VERIFY] 생년월일 파싱 실패: {}", birthdate, e);
            throw new BusinessException(ErrorCode.VERIFICATION_DECRYPT_FAILED,
                "생년월일 정보가 올바르지 않습니다.");
        }
    }

    /**
     * SHA-256 해시 (CI 원본 대신 해시값만 저장)
     */
    private String hashSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "CI 해시화에 실패했습니다.", e);
        }
    }

    /** 이름 마스킹 (로그용) */
    private String maskName(String name) {
        if (name == null || name.length() <= 1) return "*";
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }
}