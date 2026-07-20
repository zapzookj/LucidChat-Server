package com.spring.aichat.service.admin;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.CharacterVisibility;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.SecretReviewStatus;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
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
            emotionAssets
        );
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
            throw new BusinessException(ErrorCode.BAD_REQUEST, "판정 항목이 없습니다 (publishApprove/secretApprove).");
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
            promptAssembler.faceDetailWildcard(concept.appearanceTags()),
            workflowFactory.templateNegative(),
            promptAssembler.qwenPosePrompt(),
            promptAssembler.qwenBackgroundPrompt(job.getBgColor()),
            promptAssembler.qwenEmotionPrompt(EmotionTag.JOY, personaHint),
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
            c.getVisibility().name(), c.getSecretReviewStatus().name(), requestType);
    }

    private void notifyOwner(Character c, String title, String body) {
        if (c.getOwnerUserId() == null) return;
        notificationService.notify(c.getOwnerUserId(), "UGC_REVIEW_RESULT", title, body,
            "UGC_CHARACTER", String.valueOf(c.getId()));
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
