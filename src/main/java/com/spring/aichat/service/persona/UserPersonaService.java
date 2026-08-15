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
 * [블록 B 페르소나] 활성 프로필 + 저장 카드(세이브 슬롯) 서비스.
 *
 * <p>프로필은 항상 존재(최초 접근 시 lazy 부트스트랩 — 이름은 닉네임 폴백, 렌즈 중립 5).
 * 카드는 프로필 스냅샷: 저장(프로필→카드 복사, 슬롯 무료 3/구독 10)·로드(카드→프로필 복사)·삭제.
 * 구독 해지로 상한을 초과 보유하게 되면 기존 카드는 보존(로드·삭제 가능), 신규 저장만 차단.
 *
 * <p>렌즈 배분은 전 유저 공통 축당 0~10·총점 25(스탯 무료 — docs/14 #4 BM 확정).
 * 본문은 하드 키워드 게이트만(채팅 주입 시 sanitizePersona가 인젝션 방어 — 기존 배관).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPersonaService {

    private static final int NAME_MAX = 30;
    private static final int TEXT_MAX = 1000;
    private static final int AGE_MAX = 120;
    private static final String DEFAULT_PROFILE_NAME = "주인공";

    private final UserPersonaRepository personaRepository;
    private final UserRepository userRepository;
    private final UgcModerationService moderationService;

    public record ProfilePayload(String name, Integer age, String gender, String personaText,
                                 Integer allure, Integer friendliness, Integer trust,
                                 Integer charisma, Integer mystique) {}

    /** 카드 목록 + 슬롯 정보(프론트 계약 — envelope). */
    public record CardList(List<UserPersona> cards, int slotLimit, int slotUsed) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  활성 프로필
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 항상 존재하는 활성 프로필 — 없으면 부트스트랩.
     * [리뷰픽스] 동시 첫 요청 레이스: JPA save는 파셜 유니크 위반 시 트랜잭션째 abort되므로
     * ON CONFLICT DO NOTHING 네이티브 upsert(패자 no-op) 후 재조회로 항상 승자 행을 반환한다.
     */
    @Transactional
    public UserPersona getOrCreateProfile(User user) {
        return personaRepository.findByUserIdAndProfileTrue(user.getId())
            .orElseGet(() -> {
                String name = bootstrapName(user);
                int inserted = personaRepository.insertProfileIfAbsent(user.getId(), name);
                if (inserted > 0) {
                    log.info("[PERSONA] 프로필 부트스트랩: userId={} name={}", user.getId(), name);
                }
                return personaRepository.findByUserIdAndProfileTrue(user.getId())
                    .orElseThrow(() -> new IllegalStateException(
                        "프로필 부트스트랩 실패: userId=" + user.getId()));
            });
    }

    @Transactional
    public UserPersona getOrCreateProfile(String username) {
        return getOrCreateProfile(findUser(username));
    }

    @Transactional
    public UserPersona updateProfile(String username, ProfilePayload payload) {
        User user = findUser(username);
        UserPersona profile = getOrCreateProfile(user);
        Validated v = validate(payload);
        profile.applyProfileUpdate(v.name(), v.age(), v.gender(), v.text(),
            v.allure(), v.friendliness(), v.trust(), v.charisma(), v.mystique());
        return profile;
    }

    /** 시크릿 나이 게이트용 — 프로필 미존재(나이 미설정)면 false. */
    @Transactional(readOnly = true)
    public boolean isProfileAdult(Long userId) {
        return personaRepository.findByUserIdAndProfileTrue(userId)
            .map(UserPersona::isAdultPersona)
            .orElse(false);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  저장 카드 (세이브 슬롯)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional(readOnly = true)
    public CardList listCards(String username) {
        User user = findUser(username);
        List<UserPersona> cards = personaRepository.findByUserIdAndProfileFalseOrderByUpdatedAtDesc(user.getId());
        return new CardList(cards, slotLimitFor(user), cards.size());
    }

    /** 현재 프로필을 카드로 저장 — 슬롯 상한(무료 3/구독 10) 검사. */
    @Transactional
    public UserPersona saveCard(String username) {
        User user = findUser(username);
        getOrCreateProfile(user);
        // [리뷰픽스] 슬롯 상한 check-then-insert 레이스 — 유저당 1행인 프로필 행을 락 앵커로
        // 동시 저장을 직렬화(카드 행엔 DB 제약이 없어 락 없이는 동시 요청이 상한을 초과한다).
        UserPersona profile = personaRepository.findByUserIdAndProfileTrueForUpdate(user.getId())
            .orElseThrow(() -> new IllegalStateException("프로필 없음: userId=" + user.getId()));
        int limit = slotLimitFor(user);
        long used = personaRepository.countByUserIdAndProfileFalse(user.getId());
        if (used >= limit) {
            if (limit == UserPersona.FREE_SLOT_LIMIT) {
                throw new BusinessException(ErrorCode.PREMIUM_REQUIRED,
                    "카드 슬롯을 모두 사용했어요. 구독하면 %d장까지 보관할 수 있어요."
                        .formatted(UserPersona.SUBSCRIBER_SLOT_LIMIT));
            }
            throw new BadRequestException(
                "페르소나 카드는 최대 %d장까지 보관할 수 있어요.".formatted(limit));
        }
        UserPersona card = personaRepository.save(UserPersona.snapshotOf(profile));
        log.info("[PERSONA] 카드 저장: userId={} cardId={} name={}", user.getId(), card.getId(), card.getName());
        return card;
    }

    /** 카드 로드 — 카드 내용을 프로필로 복사(카드 보존). */
    @Transactional
    public UserPersona loadCard(String username, Long personaId) {
        User user = findUser(username);
        UserPersona card = ownedCard(user, personaId);
        UserPersona profile = getOrCreateProfile(user);
        profile.copyContentFrom(card);
        log.info("[PERSONA] 카드 로드: userId={} cardId={}", user.getId(), personaId);
        return profile;
    }

    @Transactional
    public void deleteCard(String username, Long personaId) {
        User user = findUser(username);
        personaRepository.delete(ownedCard(user, personaId));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private int slotLimitFor(User user) {
        return user.getSubscriptionTier() == null
            ? UserPersona.FREE_SLOT_LIMIT
            : UserPersona.SUBSCRIBER_SLOT_LIMIT;
    }

    private UserPersona ownedCard(User user, Long personaId) {
        return personaRepository.findByIdAndUserIdAndProfileFalse(personaId, user.getId())
            .orElseThrow(() -> new NotFoundException("페르소나 카드를 찾을 수 없어요."));
    }

    private String bootstrapName(User user) {
        String nickname = user.getNickname();
        if (nickname == null || nickname.isBlank()) return DEFAULT_PROFILE_NAME;
        String trimmed = nickname.trim();
        return trimmed.length() > NAME_MAX ? trimmed.substring(0, NAME_MAX) : trimmed;
    }

    private record Validated(String name, Integer age, CharacterGender gender, String text,
                             int allure, int friendliness, int trust, int charisma, int mystique) {}

    private Validated validate(ProfilePayload p) {
        String name = p.name() == null ? "" : p.name().trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new BadRequestException("이름은 1~%d자로 입력해 주세요.".formatted(NAME_MAX));
        }
        Integer age = p.age();
        if (age != null && (age < 1 || age > AGE_MAX)) {
            throw new BadRequestException("나이는 1~%d 사이로 입력해 주세요.".formatted(AGE_MAX));
        }
        String text = p.personaText() == null ? "" : p.personaText().trim();
        if (text.length() > TEXT_MAX) {
            throw new BadRequestException("소개는 %d자 이내로 입력해 주세요.".formatted(TEXT_MAX));
        }
        moderationService.assertRawConceptAllowed(name);
        if (!text.isEmpty()) {
            moderationService.assertRawConceptAllowed(text);
        }

        CharacterGender gender = CharacterGender.fromStringOrNull(p.gender());
        if (p.gender() != null && !p.gender().isBlank() && gender == null) {
            throw new BadRequestException("알 수 없는 성별입니다: " + p.gender());
        }

        int allure = nz(p.allure()), friendliness = nz(p.friendliness()), trust = nz(p.trust());
        int charisma = nz(p.charisma()), mystique = nz(p.mystique());
        int total = 0;
        for (int s : new int[]{allure, friendliness, trust, charisma, mystique}) {
            if (s < 0 || s > UserPersona.LENS_AXIS_MAX) {
                throw new BadRequestException(
                    "렌즈는 축당 0~%d 사이로 배분해 주세요.".formatted(UserPersona.LENS_AXIS_MAX));
            }
            total += s;
        }
        if (total > UserPersona.LENS_TOTAL_MAX) {
            throw new BadRequestException(
                "렌즈 배분 합계가 한도를 초과했어요. (배분 %d / 한도 %d)"
                    .formatted(total, UserPersona.LENS_TOTAL_MAX));
        }

        return new Validated(name, age, gender, text, allure, friendliness, trust, charisma, mystique);
    }

    private static int nz(Integer v) {
        return v != null ? v : 0;
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
    }
}
