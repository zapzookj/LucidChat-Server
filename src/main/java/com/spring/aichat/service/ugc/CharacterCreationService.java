package com.spring.aichat.service.ugc;

import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
import com.spring.aichat.domain.ugc.CreationJobStatus;
import com.spring.aichat.domain.ugc.UgcWorldRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.ugc.BaseCandidate;
import com.spring.aichat.dto.ugc.EmotionAssetState;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.dto.ugc.UgcDtos;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.InsufficientEnergyException;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.service.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * [UGC v1] 캐릭터 생성 유저 액션 서비스 — 과금(TX-1)·소유권·상태 검증의 단일 지점.
 *
 * <p>패턴: 검증 → 과금(잔액 부족 시 차감 전 예외) → 상태 전이 → 커밋 후 워커 kickoff.
 * 과금 규칙(2026-08-04 단계 과금 개편): 단계 진입 시 차감 6(시작)/4(스탠딩)/8(감정)/2(마무리) —
 * 합 20 유지 / 리롤 2 (실패 컷 재시도는 무과금). 신규 잡은 billing_mode=STAGED,
 * 레거시(null) 잡은 선차감 20이 이미 끝난 상태라 단계 차감 전부 스킵.
 * 실패 정책: 파이프라인 귀책 FAILED = 누적 전액 환불 / EXPIRED·중도 포기 = 무환불(취소=정산).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterCreationService {

    static final int CONCEPT_MIN_LENGTH = 30;
    static final int CONCEPT_MAX_LENGTH = 1000;

    private static final List<CreationJobStatus> ACTIVE_STATUSES =
        Arrays.stream(CreationJobStatus.values()).filter(CreationJobStatus::isActive).toList();

    private final CharacterCreationJobRepository jobRepository;
    private final UserRepository userRepository;
    private final UgcWorldRepository ugcWorldRepository; // [세계관 빌더] 3택 소유 검증
    private final UgcPipelineProperties props;
    private final UgcModerationService moderationService;
    private final UgcPipelineWorker worker;
    private final UgcJobJson json;
    private final RedisCacheService cacheService;
    private final TransactionTemplate txTemplate;
    // [2026-08-04 남캐] 남성 빌더 게이트
    private final com.spring.aichat.config.UgcModeProperties ugcModeProperties;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  컨셉 제출 (위저드 화면 1)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** [하위 호환] 성별 미지정 — FEMALE 기본. */
    public Long startCreation(String username, String requestedName, String conceptRaw,
                              UgcDtos.AppearanceHints appearanceHints,
                              String officialWorldIdRaw, Long requestedUgcWorldId) {
        return startCreation(username, requestedName, conceptRaw, appearanceHints,
            officialWorldIdRaw, requestedUgcWorldId, null);
    }

    /** [하위 호환] 난이도 미지정 — null(바인딩 미설정 → NORMAL 폴백). */
    public Long startCreation(String username, String requestedName, String conceptRaw,
                              UgcDtos.AppearanceHints appearanceHints,
                              String officialWorldIdRaw, Long requestedUgcWorldId,
                              String genderRaw) {
        return startCreation(username, requestedName, conceptRaw, appearanceHints,
            officialWorldIdRaw, requestedUgcWorldId, genderRaw, null);
    }

    public Long startCreation(String username, String requestedName, String conceptRaw,
                              UgcDtos.AppearanceHints appearanceHints,
                              String officialWorldIdRaw, Long requestedUgcWorldId,
                              String genderRaw, String difficultyRaw) {
        // [2026-08-04 남캐] 위저드 명시 선택 — 무효 문자열은 400(오타가 조용히 여캐로 흐르는 것 방지)
        com.spring.aichat.domain.enums.CharacterGender gender =
            com.spring.aichat.domain.enums.CharacterGender.FEMALE;
        if (genderRaw != null && !genderRaw.isBlank()) {
            gender = com.spring.aichat.domain.enums.CharacterGender.fromStringOrNull(genderRaw);
            if (gender == null) {
                throw new BadRequestException("알 수 없는 성별입니다: " + genderRaw);
            }
        }
        if (gender.isMale() && !ugcModeProperties.maleBuilderOn()) {
            throw new BadRequestException("남성 캐릭터 빌더는 아직 준비 중이에요.");
        }
        // [2026-08-05 난이도] 위저드 지정(선택) — 무효 문자열은 400(오타가 조용히 NORMAL로 흐르는 것 방지)
        com.spring.aichat.domain.enums.CharacterDifficulty difficulty = null;
        if (difficultyRaw != null && !difficultyRaw.isBlank()) {
            difficulty = com.spring.aichat.domain.enums.CharacterDifficulty.fromStringOrNull(difficultyRaw);
            if (difficulty == null) {
                throw new BadRequestException("알 수 없는 난이도입니다: " + difficultyRaw);
            }
        }
        String userConcept = conceptRaw == null ? "" : conceptRaw.trim();
        if (userConcept.length() < CONCEPT_MIN_LENGTH || userConcept.length() > CONCEPT_MAX_LENGTH) {
            throw new BadRequestException(
                "컨셉은 %d자 이상 %d자 이하로 입력해 주세요.".formatted(CONCEPT_MIN_LENGTH, CONCEPT_MAX_LENGTH));
        }
        String name = (requestedName == null || requestedName.isBlank()) ? null : requestedName.trim();
        if (name != null && name.length() > 50) {
            throw new BadRequestException("이름은 50자 이하로 입력해 주세요.");
        }

        // [세계관 빌더] 3택 검증 — 공식(enum) | 내 커스텀 월드(소유 검증) | 생략('나중에 연결')
        WorldId officialWorldId = null;
        if (officialWorldIdRaw != null && !officialWorldIdRaw.isBlank()) {
            officialWorldId = WorldId.fromStringOrNull(officialWorldIdRaw);
            if (officialWorldId == null) {
                throw new BadRequestException("알 수 없는 세계관입니다: " + officialWorldIdRaw);
            }
        }
        if (officialWorldId != null && requestedUgcWorldId != null) {
            throw new BadRequestException("세계관은 하나만 선택할 수 있어요.");
        }

        // [2026-07-20 개편] 외형 구조화 힌트를 컨셉 원문에 병합 — Stage 0가 태그에 강제 반영
        String concept = withAppearanceHints(userConcept, appearanceHints);

        // 하드 키워드 게이트 — 에너지 차감 전 (유저 손실 없음)
        moderationService.assertRawConceptAllowed(concept);
        if (name != null) {
            moderationService.assertRawConceptAllowed(name);
        }

        WorldId finalOfficialWorldId = officialWorldId;
        com.spring.aichat.domain.enums.CharacterGender finalGender = gender;
        com.spring.aichat.domain.enums.CharacterDifficulty finalDifficulty = difficulty;
        Long jobId = txTemplate.execute(tx -> {
            User user = findUser(username);
            if (jobRepository.existsByUserIdAndStatusIn(user.getId(), ACTIVE_STATUSES)) {
                throw new BadRequestException("이미 진행 중인 캐릭터 생성이 있어요. 완료하거나 정리한 뒤 다시 시도해 주세요.");
            }
            // [세계관 빌더] 내 커스텀 월드 소유 검증 — 타인/미존재 월드는 404 은닉
            if (requestedUgcWorldId != null) {
                ugcWorldRepository.findByIdAndOwnerUserId(requestedUgcWorldId, user.getId())
                    .orElseThrow(() -> new NotFoundException("세계관을 찾을 수 없습니다. worldId=" + requestedUgcWorldId));
            }
            // [2026-08-04 단계 과금] 선차감 20 → 시작 단계 6만 차감 (이후 단계 진입 시 각각 차감)
            int cost = props.energy().stageStart();
            user.consumeEnergy(cost); // 부족 시 InsufficientEnergyException — 차감 전 예외
            userRepository.save(user);

            CharacterCreationJob job = CharacterCreationJob.start(user.getId(), name, concept, cost);
            job.markStagedBilling(); // 신규 잡은 전부 단계 과금 — null=레거시 선차감 잡
            job.assignRequestedWorld(finalOfficialWorldId, requestedUgcWorldId);
            job.assignGender(finalGender);   // [남캐] 전 스테이지의 단일 성별 기준
            job.assignRequestedDifficulty(finalDifficulty);  // [난이도] null=미지정(바인딩 미설정 유지)
            job = jobRepository.save(job);
            return job.getId();
        });

        cacheService.evictUserProfile(username);
        worker.runStage0(jobId);
        log.info("[UGC] 생성 시작: username={}, jobId={}", username, jobId);
        return jobId;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  황금샷 선택/리롤 (위저드 화면 2 · GACHA_WAIT)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void selectGoldenShot(String username, Long jobId, int selectedIndex) {
        boolean charged = Boolean.TRUE.equals(txTemplate.execute(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.GACHA_WAIT);

            List<String> keys = json.readKeys(job.getGoldenShotKeysJson());
            if (selectedIndex < 0 || selectedIndex >= keys.size()) {
                throw new BadRequestException("잘못된 황금샷 선택입니다.");
            }
            // [2026-08-05 디자인 리롤] 선택 배치의 외형 스냅샷 복원 — 리롤 누적본의 어느 배치를
            // 골라도 태그·이미지 정합 유지. 최신 배치(현행 컨셉과 동일)면 무병합, 스냅샷 부재
            // 레거시 잡도 무병합(현행 동작). withAppearanceFrom이 최신 프로필 편집분을 보존한다.
            List<UgcJobJson.GoldenSnapshot> snaps = json.readGoldenSnapshots(
                json.readScratch(job.getExternalJobsJson()).get(UgcPipelineWorker.GOLDEN_SNAPSHOTS_KEY));
            UgcJobJson.GoldenSnapshot snap = UgcJobJson.resolveSnapshot(snaps, selectedIndex);
            boolean isLatestBatch = snap == null || snaps.isEmpty()
                || snap.startIndex() == snaps.get(snaps.size() - 1).startIndex();
            if (snap != null && !isLatestBatch) {
                StructuredConcept latest = json.readConcept(job.getStructuredConceptJson());
                StructuredConcept merged = latest.withAppearanceFrom(json.readConcept(snap.conceptJson()));
                job.applyStage0(json.writeConcept(merged), merged.bgColor());
            }
            // [2026-08-04 단계 과금] 스탠딩 진입 차감 — 부족 시 예외로 TX 롤백(GACHA_WAIT 유지, 잡 실패 금지)
            int cost = props.energy().stageStanding();
            boolean c = chargeStageEnergy(job, username, cost,
                "스탠딩 진행에 에너지 " + cost + "가 필요해요. 충전 후 다시 시도해 주세요.");
            job.toBaseProcessing(keys.get(selectedIndex));
            return c;
        }));
        if (charged) {
            cacheService.evictUserProfile(username);
        }
        worker.runBaseStage(jobId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스탠딩 후보 선택/리롤 (위저드 화면 2-b · BASE_WAIT) — 2026-07-20 개편
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 스탠딩 후보 확정 — 스타 토폴로지 원점과 감정 파생 seed가 여기서 고정된다. */
    public void selectBaseStanding(String username, Long jobId, int selectedIndex) {
        boolean charged = Boolean.TRUE.equals(txTemplate.execute(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.BASE_WAIT);

            List<BaseCandidate> candidates = json.readBaseCandidates(job.getBaseCandidatesJson());
            if (selectedIndex < 0 || selectedIndex >= candidates.size()) {
                throw new BadRequestException("잘못된 스탠딩 선택입니다.");
            }
            BaseCandidate chosen = candidates.get(selectedIndex);
            if (!chosen.is(BaseCandidate.READY)) {
                throw new BadRequestException("아직 준비되지 않은 스탠딩입니다.");
            }
            // [2026-08-04 단계 과금] 감정 진입 차감(원가 최대 구간) — 부족 시 TX 롤백(BASE_WAIT 유지)
            int cost = props.energy().stageEmotions();
            boolean c = chargeStageEnergy(job, username, cost,
                "감정 컷 진행에 에너지 " + cost + "이 필요해요. 충전 후 다시 시도해 주세요.");
            job.toEmotionsProcessing(chosen.key());
            job.fixBaseEditSeed(chosen.seed());
            worker.initEmotionAssets(job, chosen.key());
            return c;
        }));
        if (charged) {
            cacheService.evictUserProfile(username);
        }
        worker.runEmotionStage(jobId);
    }

    /** 스탠딩 후보 배치 리롤 (과금 — Qwen 2패스×2 + WF-2×2 재파생). */
    public void rerollBaseCandidates(String username, Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.BASE_WAIT);

            int cost = props.energy().baseReroll();
            User user = findUser(username);
            user.consumeEnergy(cost);
            userRepository.save(user);
            job.chargeEnergy(cost);
            job.restartBaseGeneration();
        });
        cacheService.evictUserProfile(username);
        worker.runBaseStage(jobId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  프로필 초안 편집 (레이턴시 하이딩 — Stage0 이후 ~ REVIEW_WAIT까지 상시)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 비외형 설정(성격/말투/서사/첫인사 등) 초안 수정 — 이미지와 무관한 텍스트라 생성 진행 중에도 안전.
     * null 필드는 유지. 외형 태그(appearance_tags)는 이미지가 이미 생성되므로 편집 불가.
     */
    public void updateProfileDraft(String username, Long jobId, UgcDtos.UpdateProfileRequest req) {
        // 하드 키워드 게이트 — 수정 텍스트에도 동일 적용
        String combined = String.join(" ",
            List.of(nz(req.name()), nz(req.tagline()), nz(req.personality()), nz(req.tone()),
                nz(req.appearance()), nz(req.clothing()), nz(req.backstory()), nz(req.coreValues()),
                nz(req.flaws()), nz(req.speechQuirks()), nz(req.firstGreeting()), nz(req.introNarration())));
        moderationService.assertRawConceptAllowed(combined);

        // [D-19 / D-3.6 · docs/19_assets/decision_agenda.md D-19] 유저 입력 길이 상한 — 400 거부.
        // 여기서 막지 않으면 초과값이 잡 JSON에 그대로 실려, 전 스테이지를 완주한 뒤 최종 바인딩의
        // varchar 초과로 잡이 죽고 전액 환불된다(순 0E GPU 드레인). 절삭이 아니라 거부인 이유는
        // UgcTextLimits javadoc 참조 — 조용히 자르면 유저 편집 의도가 소실된다.
        UgcTextLimits.requireCharacterTexts(req.name(), req.tagline(), req.role(), req.tone());

        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            if (job.getStatus().isTerminal()
                || job.getStatus() == CreationJobStatus.POSTPROCESSING
                || job.getStatus() == CreationJobStatus.BINDING) {
                throw new BadRequestException("설정을 수정할 수 없는 단계입니다. (상태: " + job.getStatus() + ")");
            }
            if (job.getStructuredConceptJson() == null) {
                throw new BadRequestException("아직 설정 초안을 만드는 중이에요. 잠시 후 다시 시도해 주세요.");
            }

            StructuredConcept concept = json.readConcept(job.getStructuredConceptJson());
            StructuredConcept.CharacterProfile p = concept.character();

            // 첫인사는 편집본에도 정규화 규칙 재적용 (괄호 지문 → 나레이션 채널)
            String greeting = p.firstGreeting();
            String intro = patch(req.introNarration(), p.introNarration());
            if (req.firstGreeting() != null) {
                var parts = ConceptStructuringService.normalizeGreeting(req.firstGreeting());
                greeting = parts.dialogue() != null ? parts.dialogue() : greeting;
                if (req.introNarration() == null && parts.extractedNarration() != null) {
                    intro = parts.extractedNarration();
                }
            }

            // [2026-07-30 P1 빈값=삭제] null=유지 · 빈 문자열=삭제 · 값=교체.
            // 이름은 필수(빈값도 유지), 첫인사는 정규화 경로가 별도 처리(빈값 유지).
            StructuredConcept.CharacterProfile updated = new StructuredConcept.CharacterProfile(
                or(req.name(), p.name()), patch(req.tagline(), p.tagline()), p.age(),
                patch(req.role(), p.role()), patch(req.personality(), p.personality()), patch(req.tone(), p.tone()),
                patch(req.appearance(), p.appearance()), patch(req.clothing(), p.clothing()),
                patch(req.backstory(), p.backstory()), patch(req.coreValues(), p.coreValues()),
                patch(req.flaws(), p.flaws()), patch(req.speechQuirks(), p.speechQuirks()),
                greeting, intro,
                p.height(), p.likes(), p.dislikes(), p.hobby(), p.profileQuote());

            StructuredConcept merged = new StructuredConcept(
                concept.appearanceTags(), concept.personaTags(), concept.moodTags(), concept.sceneTags(),
                concept.bgColor(), updated, concept.moderation(),
                concept.basePose(), concept.emotionPrompts());
            job.applyStage0(json.writeConcept(merged), concept.bgColor());
        });
        log.info("[UGC] 프로필 초안 수정: username={}, jobId={}", username, jobId);
    }

    private static String or(String override, String base) {
        return (override != null && !override.isBlank()) ? override.trim() : base;
    }

    /**
     * [2026-07-30 P1 빈값=삭제] PATCH 의미론 — null=유지 · 빈 문자열=삭제(null) · 값=trim 교체.
     * 기존 or()는 빈 문자열을 '유지'로 삼켜 유저가 필드를 지울 수 없었다.
     */
    private static String patch(String override, String base) {
        if (override == null) return base;
        String t = override.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 외형 구조화 힌트를 [외형 지정] 블록으로 병합 — Stage 0 시스템 프롬프트가 이 블록을 태그에 강제 반영. */
    private static String withAppearanceHints(String concept, UgcDtos.AppearanceHints h) {
        String block = appearanceHintsBlock(h);
        if (block == null) return concept;
        return concept + "\n\n[외형 지정 — appearance_tags에 반드시 반영]\n" + block;
    }

    /** 힌트 6필드 → 블록 텍스트 (전부 비면 null) — 시작·리롤 외형 수정 공용. */
    static String appearanceHintsBlock(UgcDtos.AppearanceHints h) {
        if (h == null) return null;
        StringBuilder block = new StringBuilder();
        appendHint(block, "머리", h.hair());
        appendHint(block, "눈", h.eyes());
        appendHint(block, "체형", h.body());
        appendHint(block, "의상", h.outfit());
        appendHint(block, "액세서리", h.accessories());
        appendHint(block, "기타", h.extra());
        return block.isEmpty() ? null : block.toString();
    }

    private static void appendHint(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("- ").append(label).append(": ").append(value.trim()).append("\n");
    }

    /**
     * [2026-08-04 디자인 리롤 — 종원 확정] 힌트 없는 리롤이 시드만 바꿔 "같은 디자인의 미묘한
     * 변주"만 내던 문제(리롤 기대 효과 부재) 대응: 리롤은 항상 외형 재구조화를 거친다 —
     * 힌트 있으면 그 지정을 최우선 반영, 없으면 디자인 다양화 지시로 컨셉 기준 새 디자인을 뽑는다.
     */
    private static final String DESIGN_REROLL_DIRECTIVE = """
        이번 요청은 특정 부위 수정이 아니라 **전체 디자인 리롤**이다: [기존 외형 태그]와 확연히
        다른 새 디자인을 제안하라 — 헤어 실루엣·기장, 의상 스타일, 액세서리 구성의 축을
        적극적으로 바꿔 같은 컨셉의 다른 해석을 보여줘라. 단 [원래 컨셉 서술]이 명시한
        지정(머리색·눈색 등)과 성별 정체성은 반드시 유지한다.""";

    /**
     * 황금샷 배치 리롤. [2026-07-21] 외형 지정 동봉 가능 — 베이스 확정 전(GACHA_WAIT)이라
     * 외형 재구조화가 안전한 유일 구간. [2026-08-04] 힌트 미동봉 시에도 디자인 다양화
     * 재구조화를 수행한다(시드만 리롤은 폐지). [2026-08-05 종원 승인] 기존 후보는 비우지 않고
     * 누적한다 — 배치별 외형 스냅샷(GOLDEN_SNAPSHOTS_KEY)이 선택 시 정합을 복원하므로
     * 구디자인 원화를 남겨두고 최종 비교·선택하는 UX가 안전해졌다.
     */
    public void rerollGoldenShots(String username, Long jobId, UgcDtos.AppearanceHints appearanceEdit) {
        String hintsBlock = appearanceHintsBlock(appearanceEdit);
        if (hintsBlock != null) {
            // 하드 키워드 게이트 — 에너지 차감 전 (유저 손실 없음). 내부 디자인 지시문은 게이트 불요.
            moderationService.assertRawConceptAllowed(hintsBlock);
        }
        String effectiveBlock = hintsBlock != null ? hintsBlock : DESIGN_REROLL_DIRECTIVE;

        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.GACHA_WAIT);

            int cost = props.energy().goldenReroll();
            User user = findUser(username);
            user.consumeEnergy(cost);
            userRepository.save(user);
            job.chargeEnergy(cost);
            job.restartGoldenGeneration();
            Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
            scratch.put(UgcPipelineWorker.APPEARANCE_EDIT_KEY, effectiveBlock);
            job.updateExternalJobs(json.writeScratch(scratch));
        });
        cacheService.evictUserProfile(username);
        worker.runGoldenReroll(jobId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  감정 리롤 / 검수 확정 (위저드 화면 4 · REVIEW_WAIT)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 감정 1컷 리롤. FAILED 컷 = 무료 재시도 / READY 컷 = 유저 발의 리롤(과금).
     * NEUTRAL은 스타 토폴로지 원점(베이스)이라 리롤 불가 — 파생 14종의 일관성이 깨진다.
     */
    public void rerollEmotion(String username, Long jobId, EmotionTag tag) {
        if (tag == EmotionTag.NEUTRAL) {
            throw new BadRequestException("기본 표정은 다시 뽑을 수 없어요.");
        }
        boolean charged = Boolean.TRUE.equals(txTemplate.execute(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.REVIEW_WAIT);

            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
            EmotionAssetState state = emotions.get(tag);
            if (state == null) {
                throw new BadRequestException("알 수 없는 감정 컷입니다.");
            }
            boolean free = state.is(EmotionAssetState.FAILED);
            if (!free) {
                int cost = props.energy().emotionReroll();
                User user = findUser(username);
                user.consumeEnergy(cost);
                userRepository.save(user);
                job.chargeEnergy(cost);
            }
            worker.resetEmotionForReroll(job, tag);
            return !free;
        }));
        if (charged) {
            cacheService.evictUserProfile(username);
        }
        worker.runEmotionReroll(jobId, tag);
    }

    /**
     * [2026-07-20 리롤 누적] 감정 컷 버전 골라잡기 — 누적된 완성본(history) 중 하나를 선택본으로 (무과금).
     */
    public void selectEmotionVersion(String username, Long jobId, EmotionTag tag, int versionIndex) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.REVIEW_WAIT);

            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
            EmotionAssetState state = emotions.get(tag);
            if (state == null || state.history().isEmpty()) {
                throw new BadRequestException("선택할 수 있는 버전이 없습니다.");
            }
            if (versionIndex < 0 || versionIndex >= state.history().size()) {
                throw new BadRequestException("잘못된 버전 선택입니다.");
            }
            emotions.put(tag, state.selectVersion(versionIndex));
            job.updateEmotionAssets(json.writeEmotions(emotions));
        });
    }

    /** 검수 확정 — 15컷 전부 READY여야 한다 (FAILED 컷은 무료 재시도 유도). */
    public void confirmReview(String username, Long jobId) {
        boolean charged = Boolean.TRUE.equals(txTemplate.execute(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.REVIEW_WAIT);

            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
            boolean allReady = emotions.size() == EmotionTag.values().length
                && emotions.values().stream().allMatch(s -> s.is(EmotionAssetState.READY));
            if (!allReady) {
                throw new BadRequestException("아직 완성되지 않은 컷이 있어요. 실패한 컷을 다시 시도해 주세요.");
            }
            // [2026-08-04 단계 과금] 마무리(누끼·바인딩) 진입 차감 — 부족 시 TX 롤백(REVIEW_WAIT 유지)
            int cost = props.energy().stageFinalize();
            boolean c = chargeStageEnergy(job, username, cost,
                "마무리 진행에 에너지 " + cost + "가 필요해요. 충전 후 다시 시도해 주세요.");
            job.toPostprocessing();
            return c;
        }));
        if (charged) {
            cacheService.evictUserProfile(username);
        }
        worker.runCutoutStage(jobId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  중도 포기 / 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 중도 포기 — 무환불 정책(이미 GPU/LLM 비용 발생). */
    public void abandon(String username, Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            if (job.getStatus().isTerminal()) return; // 멱등
            job.fail("유저 중도 포기");
        });
        log.info("[UGC] 중도 포기: username={}, jobId={}", username, jobId);
    }

    public CharacterCreationJob getOwnedJob(String username, Long jobId) {
        User user = findUser(username);
        return jobRepository.findByIdAndUserId(jobId, user.getId())
            .orElseThrow(() -> new NotFoundException("생성 잡을 찾을 수 없습니다. jobId=" + jobId));
    }

    /** 진행 중 잡 (스튜디오 진행 카드 — 동시 1잡 정책이라 0~1개). */
    public List<CharacterCreationJob> getActiveJobs(String username) {
        User user = findUser(username);
        return jobRepository.findByUserIdAndStatusInOrderByIdDesc(user.getId(), ACTIVE_STATUSES);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [2026-08-04 단계 과금] STAGED 잡 단계 진입 차감 — 잡 비관적 락 TX 내부 전용.
     * 레거시(billing_mode null) 선차감 잡은 스킵. 부족 시 BadRequestException으로 진행만 차단
     * (TX 롤백 — 잡 상태·차감 모두 원복, 잡 실패 금지). User @Version 낙관락 충돌은 기존
     * consumeEnergy 경로 관례 그대로 전파. 반환: 실제 차감 여부(프로필 캐시 무효화 판단용).
     */
    private boolean chargeStageEnergy(CharacterCreationJob job, String username,
                                      int cost, String insufficientMessage) {
        if (!job.isStagedBilling()) return false; // 레거시 선차감 잡 — 이미 20 지불 완료
        User user = findUser(username);
        try {
            user.consumeEnergy(cost); // 기존 차감 경로 재사용 — 부족 시 차감 전 예외
        } catch (InsufficientEnergyException e) {
            throw new BadRequestException(insufficientMessage);
        }
        userRepository.save(user);
        job.chargeEnergy(cost); // 환불 정산 기준 누적 — failAndRefund가 전액 환불에 그대로 사용
        return true;
    }

    private CharacterCreationJob lockOwnedJob(String username, Long jobId) {
        User user = findUser(username);
        CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new NotFoundException("생성 잡을 찾을 수 없습니다. jobId=" + jobId));
        if (!job.getUserId().equals(user.getId())) {
            // 소유 은닉 — 타인 잡의 존재를 노출하지 않는다
            throw new NotFoundException("생성 잡을 찾을 수 없습니다. jobId=" + jobId);
        }
        return job;
    }

    private static void requireStatus(CharacterCreationJob job, CreationJobStatus expected) {
        if (job.getStatus() != expected) {
            throw new BadRequestException("현재 단계에서 수행할 수 없는 요청입니다. (상태: " + job.getStatus() + ")");
        }
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
    }
}
