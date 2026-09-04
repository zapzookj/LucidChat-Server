package com.spring.aichat.service.auth;

import com.spring.aichat.domain.enums.AuthProvider;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [E-7.1.a] 소셜 로그인 유저 upsert.
 *
 * <p><b>왜 별도 빈인가</b> — 종전에는 {@code OAuth2LoginSuccessHandler}의 {@code protected}
 * 메서드 3개에 {@code @Transactional}이 붙어 있었으나 같은 클래스가 {@code this.}로 호출해
 * <b>프록시를 타지 않았다</b>. 즉 애노테이션이 사실상 무효였고, '이메일 조회 → INSERT'를 한
 * 트랜잭션으로 묶을 수도 없었다. 주입받아 부르는 별도 빈으로 옮겨야 실제로 적용된다.
 *
 * <p><b>무엇을 고치는가</b> — {@code users.email}에 UNIQUE 인덱스가 있는데
 * (User 엔티티의 {@code idx_user_email}) 세 upsert 경로 모두 <b>username 충돌만</b>
 * {@code existsByUsername}으로 피하고 <b>email 충돌은 검사조차 하지 않았다</b>.
 * 그래서 이미 다른 provider(또는 로컬)로 가입된 이메일로 소셜 로그인하면
 * {@code DataIntegrityViolationException} → <b>500</b>이 뜨고, 재시도해도 같은 지점에서 죽어
 * 그 유저는 그 provider로 <b>영구히 로그인할 수 없었다</b>.
 * 반대 방향(로컬 가입 시 소셜 이메일 충돌)은 {@code AuthService}가 친절히 400으로 막고 있었다 —
 * 비대칭이었다.
 *
 * <p><b>정책</b> — decisions_confirmed §B #19 <b>(B) provider 안내 리다이렉트</b>가 확정이다.
 * 자동 계정 연동(A)은 이메일 소유 검증이 약하면 계정 탈취 벡터가 되고, 별도 계정 생성(C)은
 * 중복 계정을 양산한다. 여기서는 충돌을 <b>값으로 반환</b>하고 화면 처리는 핸들러가 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialUserUpsertService {

    private final UserRepository userRepository;

    /**
     * upsert 결과. {@code user}가 null이면 이메일 충돌이고 {@code conflictWith}가 기존 소유자의 provider다.
     */
    public record UpsertResult(User user, AuthProvider conflictWith) {
        static UpsertResult ok(User u) { return new UpsertResult(u, null); }
        static UpsertResult emailTaken(AuthProvider owner) { return new UpsertResult(null, owner); }
        public boolean isEmailConflict() { return user == null; }
    }

    /**
     * provider+providerId로 조회하고, 없으면 생성한다.
     *
     * <p>동시 요청으로 같은 계정이 두 번 INSERT되는 레이스에서는
     * {@code DataIntegrityViolationException}이 <b>밖으로 전파된다</b> — 호출자가 이 트랜잭션
     * <b>바깥에서</b> {@link #findExisting}으로 재조회해야 한다.
     * ⚠ 여기서 catch 후 재조회하면 안 된다: 제약 위반으로 트랜잭션이 rollback-only로 마킹돼
     * 이후 쿼리가 그대로 죽는다.
     */
    @Transactional
    public UpsertResult upsert(AuthProvider provider, String providerId,
                               String email, String nickname, String usernamePrefix) {
        var existing = userRepository.findByProviderAndProviderId(provider, providerId);
        if (existing.isPresent()) return UpsertResult.ok(existing.get());

        // ★ [E-7.1.a] INSERT 전에 이메일 소유자를 확인한다. 종전에는 이 검사가 없어 500이 났다.
        if (email != null && !email.isBlank()) {
            var byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                AuthProvider owner = byEmail.get().getProvider();
                log.info("[OAUTH] 이메일 충돌 — 기존 계정 존재 | 시도={} | 기존={}", provider, owner);
                return UpsertResult.emailTaken(owner);
            }
        }

        String username = (email != null && !email.isBlank()) ? email : (usernamePrefix + safeSuffix(providerId, 8));
        if (userRepository.existsByUsername(username)) {
            username = usernamePrefix + safeSuffix(providerId, 12);
        }

        User created = new User();
        setUserFields(created, username, nickname, email, provider, providerId);
        return UpsertResult.ok(userRepository.save(created));
    }

    /**
     * 레이스 폴백 — 호출자가 {@code DataIntegrityViolationException}을 받은 뒤 <b>새 트랜잭션</b>에서 부른다.
     * 동시 요청이 먼저 만들어 둔 행을 찾아 돌려준다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public UpsertResult findExisting(AuthProvider provider, String providerId, String email) {
        var byProvider = userRepository.findByProviderAndProviderId(provider, providerId);
        if (byProvider.isPresent()) return UpsertResult.ok(byProvider.get());
        if (email != null && !email.isBlank()) {
            var byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) return UpsertResult.emailTaken(byEmail.get().getProvider());
        }
        return UpsertResult.emailTaken(null); // 원인 불명 — 핸들러가 일반 오류로 처리한다
    }

    /** providerId가 짧을 수 있으므로 substring 범위를 넘지 않게 자른다(종전 코드의 잠재 StringIndexOutOfBounds). */
    private String safeSuffix(String providerId, int len) {
        if (providerId == null) return "unknown";
        return providerId.length() <= len ? providerId : providerId.substring(0, len);
    }

    /**
     * User의 소셜 필드 세팅. 엔티티에 공개 세터가 없어 리플렉션을 쓰던 종전 방식을 그대로 유지한다
     * (엔티티 API 변경은 이 결함의 범위 밖이다).
     */
    private void setUserFields(User user, String username, String nickname, String email,
                               AuthProvider provider, String providerId) {
        try {
            java.lang.reflect.Field f;
            f = User.class.getDeclaredField("username"); f.setAccessible(true); f.set(user, username);
            f = User.class.getDeclaredField("nickname"); f.setAccessible(true); f.set(user, nickname);
            f = User.class.getDeclaredField("email"); f.setAccessible(true); f.set(user, email);
            f = User.class.getDeclaredField("provider"); f.setAccessible(true); f.set(user, provider);
            f = User.class.getDeclaredField("providerId"); f.setAccessible(true); f.set(user, providerId);
        } catch (Exception e) {
            throw new IllegalStateException("소셜 유저 생성 실패", e);
        }
    }
}
