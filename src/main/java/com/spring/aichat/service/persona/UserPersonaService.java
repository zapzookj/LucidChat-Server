package com.spring.aichat.service.persona;

import com.spring.aichat.domain.enums.CharacterGender;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserPersona;
import com.spring.aichat.domain.user.UserPersonaRepository;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.service.ugc.UgcModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * [2026-08-04 페르소나 풀 패키지] 페르소나 카드 CRUD + 스탯 분배 게이트.
 *
 * <p>스탯 분배 정책은 Theater와 동일 규칙(구독 티어별 총량/단일 상한 —
 * TheaterLobbyService 상수와 값 동기, 드리프트 시 그쪽이 정본):
 * 미구독 0p / LUCID_PASS 20p·perStat 10 / MIDNIGHT 500p·perStat 100.
 * 본문은 하드 키워드 게이트만(채팅 주입 시 sanitizePersona가 인젝션 방어 — 기존 배관).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPersonaService {

    private static final int NAME_MAX = 30;
    private static final int TEXT_MAX = 1000;

    private final UserPersonaRepository personaRepository;
    private final UserRepository userRepository;
    private final UgcModerationService moderationService;

    public record PersonaPayload(String name, String gender, String personaText,
                                 Integer charm, Integer wit, Integer boldness,
                                 Integer intellect, Integer empathy) {}

    @Transactional(readOnly = true)
    public List<UserPersona> list(String username) {
        return personaRepository.findByUserIdOrderByUpdatedAtDesc(findUser(username).getId());
    }

    @Transactional
    public UserPersona create(String username, PersonaPayload payload) {
        User user = findUser(username);
        if (personaRepository.countByUserId(user.getId()) >= UserPersona.MAX_CARDS_PER_USER) {
            throw new BadRequestException(
                "페르소나 카드는 최대 %d장까지 보관할 수 있어요.".formatted(UserPersona.MAX_CARDS_PER_USER));
        }
        Validated v = validate(user, payload);
        UserPersona persona = personaRepository.save(UserPersona.create(
            user.getId(), v.name(), v.gender(), v.text(),
            v.charm(), v.wit(), v.boldness(), v.intellect(), v.empathy()));
        log.info("[PERSONA] 카드 생성: userId={} personaId={} name={}", user.getId(), persona.getId(), v.name());
        return persona;
    }

    @Transactional
    public UserPersona update(String username, Long personaId, PersonaPayload payload) {
        User user = findUser(username);
        UserPersona persona = owned(user, personaId);
        Validated v = validate(user, payload);
        persona.update(v.name(), v.gender(), v.text(),
            v.charm(), v.wit(), v.boldness(), v.intellect(), v.empathy());
        return persona;
    }

    @Transactional
    public void delete(String username, Long personaId) {
        User user = findUser(username);
        personaRepository.delete(owned(user, personaId));
    }

    /** 방 생성 배관용 — 소유 검증 로드 (스냅샷 복사는 호출측 책임). */
    @Transactional(readOnly = true)
    public UserPersona requireOwned(Long userId, Long personaId) {
        return personaRepository.findByIdAndUserId(personaId, userId)
            .orElseThrow(() -> new NotFoundException("페르소나 카드를 찾을 수 없어요."));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private UserPersona owned(User user, Long personaId) {
        return personaRepository.findByIdAndUserId(personaId, user.getId())
            .orElseThrow(() -> new NotFoundException("페르소나 카드를 찾을 수 없어요."));
    }

    private record Validated(String name, CharacterGender gender, String text,
                             int charm, int wit, int boldness, int intellect, int empathy) {}

    private Validated validate(User user, PersonaPayload p) {
        String name = p.name() == null ? "" : p.name().trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new BadRequestException("카드 이름은 1~%d자로 입력해 주세요.".formatted(NAME_MAX));
        }
        String text = p.personaText() == null ? "" : p.personaText().trim();
        if (text.isEmpty() || text.length() > TEXT_MAX) {
            throw new BadRequestException("페르소나 본문은 1~%d자로 입력해 주세요.".formatted(TEXT_MAX));
        }
        moderationService.assertRawConceptAllowed(name);
        moderationService.assertRawConceptAllowed(text);

        CharacterGender gender = CharacterGender.fromStringOrNull(p.gender());
        if (p.gender() != null && !p.gender().isBlank() && gender == null) {
            throw new BadRequestException("알 수 없는 성별입니다: " + p.gender());
        }

        int charm = nz(p.charm()), wit = nz(p.wit()), boldness = nz(p.boldness());
        int intellect = nz(p.intellect()), empathy = nz(p.empathy());
        for (int s : new int[]{charm, wit, boldness, intellect, empathy}) {
            if (s < 0) throw new BadRequestException("스탯은 0 이상이어야 해요.");
        }
        assertStatBudget(user, charm + wit + boldness + intellect + empathy,
            Math.max(charm, Math.max(wit, Math.max(boldness, Math.max(intellect, empathy)))));

        return new Validated(name, gender, text, charm, wit, boldness, intellect, empathy);
    }

    /** Theater 초기 스탯 분배(validateInitialStats)와 동일 정책 — 티어별 총량·단일 상한 값 동기. */
    private void assertStatBudget(User user, int total, int maxSingle) {
        int totalCap;
        int perStatCap;
        if (user.getSubscriptionTier() == null) {
            totalCap = 0;
            perStatCap = 0;
        } else {
            switch (user.getSubscriptionTier()) {
                case LUCID_PASS -> { totalCap = 20; perStatCap = 10; }
                case LUCID_MIDNIGHT_PASS -> { totalCap = 500; perStatCap = 100; }
                default -> { totalCap = 0; perStatCap = 0; }
            }
        }
        if (total > totalCap) {
            throw new BusinessException(ErrorCode.PREMIUM_REQUIRED,
                "스탯 분배 한도를 초과했어요. (배분 %d / 한도 %d — 구독 등급에 따라 확장)".formatted(total, totalCap));
        }
        if (maxSingle > perStatCap) {
            throw new BusinessException(ErrorCode.PREMIUM_REQUIRED,
                "단일 스탯 한도를 초과했어요. (최대 %d)".formatted(perStatCap));
        }
    }

    private static int nz(Integer v) {
        return v != null ? v : 0;
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
    }
}
