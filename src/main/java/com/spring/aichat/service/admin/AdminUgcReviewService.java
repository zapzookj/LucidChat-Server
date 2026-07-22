package com.spring.aichat.service.admin;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.CharacterVisibility;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.SecretReviewStatus;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
import com.spring.aichat.domain.ugc.UgcWorld;
import com.spring.aichat.domain.ugc.UgcWorldLocationRepository;
import com.spring.aichat.domain.ugc.UgcWorldRepository;
import com.spring.aichat.domain.ugc.WorldReviewStatus;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.admin.UgcReviewDtos;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.audit.AuditLogService;
import com.spring.aichat.service.notification.NotificationService;
import com.spring.aichat.service.ugc.UgcAssetService;
import com.spring.aichat.service.ugc.UgcJobJson;
import com.spring.aichat.service.ugc.UgcPromptAssembler;
import com.spring.aichat.service.ugc.UgcWorkflowFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [UGC v1] 백오피스 UGC 승인 큐 — 모더레이션 3층 중 ② (공개 + Secret 통합 검수).
 *
 * <p>큐 대상: 공개 신청(PENDING_PUBLIC) ∪ Secret 단독 신청(secretReviewStatus=PENDING).
 * PRIVATE 전용 캐릭터는 검수 없음(신고 기반만) — 신청이 있어야 큐에 뜬다.
 *
 * <p>판정: 한 화면에서 체크박스 2개(공개 승인 / secretEligible) 동시 제출.
 * 모든 뮤테이션은 동일 TX 감사 로그(코드베이스 P0 원칙) + 소유자 인앱 알림.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUgcReviewService {

    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;
    private final UgcWorldRepository ugcWorldRepository; // [세계관 빌더] 월드 섹션·피기백 판정
    private final UgcWorldLocationRepository ugcWorldLocationRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final UgcAssetService assetService;
    private final CharacterCreationJobRepository jobRepository;
    private final UgcJobJson json;
    private final UgcPromptAssembler promptAssembler;
    private final UgcWorkflowFactory workflowFactory;

    @Transactional(readOnly = true)
    public UgcReviewDtos.QueueResponse queue() {
        Set<Character> pending = new LinkedHashSet<>();
        pending.addAll(characterRepository.findByVisibilityOrderByIdAsc(CharacterVisibility.PENDING_PUBLIC));
        pending.addAll(characterRepository.findBySecretReviewStatusOrderByIdAsc(SecretReviewStatus.PENDING));

        List<UgcReviewDtos.QueueItem> items = pending.stream()
            .filter(Character::isUgc)
            .map(this::toQueueItem)
            .toList();
        return new UgcReviewDtos.QueueResponse(items);
    }

    @Transactional(readOnly = true)
    public UgcReviewDtos.DetailResponse detail(Long characterId) {
        Character c = findUgc(characterId);

        Map<String, String> emotionAssets = new LinkedHashMap<>();
        for (EmotionTag tag : EmotionTag.values()) {
            emotionAssets.put(tag.name(),
                assetService.publicUrl("characters/" + c.getSlug() + "/default_" + tag.name().toLowerCase() + ".png"));
        }

        return new UgcReviewDtos.DetailResponse(
            toQueueItem(c),
            c.getTagline(), c.getDescription(), c.getRole(),
            c.getPersonality(), c.getTone(), c.getAppearance(), c.getClothing(),
            c.getBackstory(), c.getCoreValues(), c.getFlaws(), c.getSpeechQuirks(),
            c.getFirstGreeting(), c.getReviewNote(), c.isSecretEligible(),
            emotionAssets,
            buildWorldSection(c)
        );
    }

    /** [세계관 빌더] 소속 UGC 월드 섹션 — 공개 심사에 월드 검수 자동 포함(피기백). */
    private UgcReviewDtos.WorldSection buildWorldSection(Character c) {
        if (c.getUgcWorldId() == null) return null;
        return ugcWorldRepository.findById(c.getUgcWorldId())
            .map(w -> new UgcReviewDtos.WorldSection(
                w.getId(), w.getName(), w.getIntro(), w.getLore(),
                splitMood(w.getMoodTags()), w.getThumbnailUrl(),
                w.getReviewStatus().name(), w.getReviewNote(),
                ugcWorldLocationRepository.findByUgcWorldIdAndActiveTrueOrderByDisplayOrderAsc(w.getId()).stream()
                    .map(l -> new UgcReviewDtos.WorldLocationItem(
                        l.getLocationKey(), l.getDisplayName(), l.getDescription(), l.getBackgroundUrl()))
                    .toList()))
            .orElse(null);
    }

    private static List<String> splitMood(String moodCsv) {
        if (moodCsv == null || moodCsv.isBlank()) return List.of();
        return List.of(moodCsv.split("\\s*,\\s*"));
    }

    /**
     * 판정 — 공개/Secret 독립 축을 한 번에.
     * 공개 판정은 PENDING_PUBLIC 상태에서만 유효(그 외 400), Secret 판정은 관리자 재량으로 상시 가능
     * (신청 없는 캐릭터에도 부여/회수 가능 — 공개 검수 중 동시 판정 케이스).
     */
    @Transactional
    public void review(String actor, Long characterId, UgcReviewDtos.ReviewRequest req) {
        Character c = findUgc(characterId);
        StringBuilder detail = new StringBuilder();

        // [세계관 빌더] 통반려 정책 — 월드 반려와 캐릭터 공개 승인의 조합은 모순 (심사 우회 방지)
        if (Boolean.TRUE.equals(req.publishApprove()) && Boolean.FALSE.equals(req.worldApprove())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "세계관 반려 시 캐릭터 공개는 승인할 수 없습니다 — 함께 반려해 주세요 (통반려 정책).");
        }

        // [세계관 빌더] 월드 판정 축 — 소속 월드가 있어야 유효. Secret 축처럼 관리자 재량 상시 판정.
        UgcWorld world = null;
        if (req.worldApprove() != null) {
            if (c.getUgcWorldId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "연결된 세계관이 없는 캐릭터입니다: " + characterId);
            }
            world = ugcWorldRepository.findById(c.getUgcWorldId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                    "세계관을 찾을 수 없습니다: " + c.getUgcWorldId()));
        }

        // [세계관 빌더] 공개 승인 게이트 — 소속 월드가 미승인이면 worldApprove=true 동시 제출 필수
        if (Boolean.TRUE.equals(req.publishApprove()) && c.getUgcWorldId() != null
            && !Boolean.TRUE.equals(req.worldApprove())) {
            WorldReviewStatus current = ugcWorldRepository.findById(c.getUgcWorldId())
                .map(UgcWorld::getReviewStatus).orElse(WorldReviewStatus.NONE);
            if (current != WorldReviewStatus.APPROVED) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "소속 세계관이 미승인 상태입니다 — worldApprove 판정을 함께 제출해 주세요.");
            }
        }

        if (world != null) {
            if (req.worldApprove()) {
                world.approve(req.note());
                detail.append("world=APPROVED ");
                notifyOwnerWorld(c, world, "세계관이 승인되었어요",
                    world.getName() + " 세계관이 검수를 통과했어요.");
            } else {
                world.reject(req.note());
                detail.append("world=REJECTED ");
                notifyOwnerWorld(c, world, "세계관이 반려되었어요",
                    world.getName() + " 세계관이 반려되었어요. 사유를 확인해 주세요.");
            }
        }

        if (req.publishApprove() != null) {
            if (c.getVisibility() != CharacterVisibility.PENDING_PUBLIC) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "공개 심사 대기 상태가 아닙니다: " + c.getVisibility());
            }
            if (req.publishApprove()) {
                c.approvePublish(req.note());
                detail.append("publish=APPROVED ");
                notifyOwner(c, "캐릭터가 공개되었어요",
                    c.getName() + " 캐릭터가 승인되어 모든 유저에게 공개되었어요.");
            } else {
                c.rejectPublish(req.note());
                detail.append("publish=REJECTED ");
                notifyOwner(c, "공개 신청이 반려되었어요",
                    c.getName() + " 캐릭터의 공개 신청이 반려되었어요. 사유를 확인해 주세요.");
            }
        }

        if (req.secretApprove() != null) {
            if (req.secretApprove()) {
                c.approveSecret(req.note());
                detail.append("secret=APPROVED ");
                notifyOwner(c, "Secret 모드가 허용되었어요",
                    c.getName() + " 캐릭터의 Secret 모드 대화가 허용되었어요.");
            } else {
                c.rejectSecret(req.note());
                detail.append("secret=REJECTED ");
                notifyOwner(c, "Secret 신청이 반려되었어요",
                    c.getName() + " 캐릭터의 Secret 모드 신청이 반려되었어요.");
            }
        }

        if (detail.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "판정 항목이 없습니다 (publishApprove/secretApprove/worldApprove).");
        }

        auditLogService.record(actor, "UGC_REVIEW", "CHARACTER", String.valueOf(characterId),
            detail + "(사유: " + req.note() + ")");
        log.info("[ADMIN-UGC] 판정: characterId={}, {}, actor={}", characterId, detail, actor);
    }

    /**
     * [프롬프트 인스펙션 2026-07-20] 캐릭터 일러 생성에 들어간 실제 프롬프트 재구성 — 튜닝 참조용.
     * 최종 프롬프트 = 잡의 구조화 태그(Stage0 이후 불변) + 서버 상수의 결정적 함수이므로
     * 저장본 없이 제출 시점 값이 정확히 재현된다.
     */
    @Transactional(readOnly = true)
    public UgcReviewDtos.PromptInspection prompts(Long characterId) {
        Character c = findUgc(characterId);
        CharacterCreationJob job = jobRepository.findByCharacterId(c.getId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "원본 생성 잡을 찾을 수 없습니다: characterId=" + characterId));
        if (job.getStructuredConceptJson() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "구조화 산출이 없는 잡입니다: jobId=" + job.getId());
        }
        StructuredConcept concept = json.readConcept(job.getStructuredConceptJson());
        String personaHint = (concept.personaTags() == null || concept.personaTags().isEmpty())
            ? null : String.join(", ", concept.personaTags());

        return new UgcReviewDtos.PromptInspection(
            job.getId(),
            concept.appearanceTags(),
            concept.personaTags(),
            concept.sceneTags(),
            job.getBgColor(),
            promptAssembler.goldenShotPositive(concept.appearanceTags(), concept.personaTags(), concept.sceneTags()),
            promptAssembler.refinePositive(concept.appearanceTags(), concept.personaTags(), EmotionTag.NEUTRAL, job.getBgColor()),
            promptAssembler.refinePositive(concept.appearanceTags(), concept.personaTags(), EmotionTag.JOY, job.getBgColor()),
            // [2026-07-21 재구성] 감정 표정 포함 구성 — JOY 예시로 실구성 확인
            promptAssembler.faceDetailWildcard(concept.appearanceTags(), concept.personaTags(), EmotionTag.JOY),
            workflowFactory.templateNegative(),
            promptAssembler.qwenPosePrompt(concept.basePose()),
            promptAssembler.qwenBackgroundPrompt(job.getBgColor()),
            promptAssembler.qwenEmotionPrompt(EmotionTag.JOY, personaHint,
                concept.emotionPromptFor(EmotionTag.JOY.name())),
            promptAssembler.qwenNegative()
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private UgcReviewDtos.QueueItem toQueueItem(Character c) {
        boolean publishPending = c.getVisibility() == CharacterVisibility.PENDING_PUBLIC;
        boolean secretPending = c.getSecretReviewStatus() == SecretReviewStatus.PENDING;
        String requestType = (publishPending && secretPending) ? "BOTH"
            : publishPending ? "PUBLISH" : "SECRET";

        String nickname = c.getOwnerUserId() == null ? null
            : userRepository.findById(c.getOwnerUserId())
                .map(User::getNickname).orElse(null);

        return new UgcReviewDtos.QueueItem(
            c.getId(), c.getName(), c.getSlug(), c.getThumbnailUrl(),
            c.getOwnerUserId(), nickname,
            c.getVisibility().name(), c.getSecretReviewStatus().name(), requestType,
            c.getUgcWorldId() != null);
    }

    private void notifyOwner(Character c, String title, String body) {
        if (c.getOwnerUserId() == null) return;
        notificationService.notify(c.getOwnerUserId(), "UGC_REVIEW_RESULT", title, body,
            "UGC_CHARACTER", String.valueOf(c.getId()));
    }

    private void notifyOwnerWorld(Character c, UgcWorld world, String title, String body) {
        if (c.getOwnerUserId() == null) return;
        notificationService.notify(c.getOwnerUserId(), "UGC_REVIEW_RESULT", title, body,
            "UGC_WORLD", String.valueOf(world.getId()));
    }

    private Character findUgc(Long characterId) {
        Character c = characterRepository.findById(characterId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "캐릭터를 찾을 수 없습니다: " + characterId));
        if (!c.isUgc()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "공식 캐릭터는 UGC 검수 대상이 아닙니다: " + characterId);
        }
        return c;
    }
}
