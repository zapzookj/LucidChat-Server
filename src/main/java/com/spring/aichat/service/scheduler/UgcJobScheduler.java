package com.spring.aichat.service.scheduler;

import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
import com.spring.aichat.domain.ugc.CreationJobStatus;
import com.spring.aichat.domain.ugc.UgcWorldCreationJob;
import com.spring.aichat.domain.ugc.UgcWorldCreationJobRepository;
import com.spring.aichat.domain.ugc.WorldCreationJobStatus;
import com.spring.aichat.external.UgcComfyClient;
import com.spring.aichat.service.ugc.UgcJobJson;
import com.spring.aichat.service.ugc.UgcPipelineWorker;
import com.spring.aichat.service.ugc.UgcStage;
import com.spring.aichat.service.ugc.UgcWorldPipelineWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * [UGC v1] 잡 유지보수 스케줄러.
 *
 * <ol>
 *   <li><b>폴링 폴백</b> (1분): webhook 유실 대비 — PROCESSING 잡의 미결 RunPod 잡을 /status로
 *       재확인해 {@link UgcPipelineWorker#onComfyEvent}에 공급 (이벤트 경로 공용·멱등).</li>
 *   <li><b>TTL 만료</b> (10분): {@code *_WAIT} 72h 방치 잡을 EXPIRED 종결 (무환불 정책).</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UgcJobScheduler {

    private static final List<CreationJobStatus> COMFY_PROCESSING_STATUSES = List.of(
        CreationJobStatus.CONCEPT_PROCESSING,   // WF-1 진행 구간 포함
        CreationJobStatus.BASE_PROCESSING,
        CreationJobStatus.EMOTIONS_PROCESSING,
        // [2026-07-20 Fix] REVIEW_WAIT 포함 — 감정 컷 리롤은 잡을 REVIEW_WAIT에 둔 채 WF-2를
        // 재제출하므로, 이 상태를 폴링하지 않으면(웹훅 미도달 환경) 리롤 결과가 영영 반영되지
        // 않아 무한 로딩이 걸린다. 미결 외부 잡이 없으면 /status 호출이 발생하지 않아 비용 0.
        CreationJobStatus.REVIEW_WAIT,
        CreationJobStatus.POSTPROCESSING
    );

    private static final List<CreationJobStatus> WAIT_STATUSES = List.of(
        CreationJobStatus.GACHA_WAIT,
        CreationJobStatus.BASE_WAIT,
        CreationJobStatus.REVIEW_WAIT
    );

    /** [세계관 빌더] *_WAIT TTL 만료 대상. */
    private static final List<WorldCreationJobStatus> WORLD_WAIT_STATUSES = List.of(
        WorldCreationJobStatus.EDIT_WAIT,
        WorldCreationJobStatus.REVIEW_WAIT
    );

    /**
     * [세계관 빌더] 스테일 스윕 대상 — fal 전용 트랙은 웹훅/폴링 폴백이 없어 서버 재시작으로
     * in-flight future가 유실되면 잡이 무진행으로 멈춘다. REVIEW_WAIT는 리롤 in-flight 유실 케이스
     * (외부 잡 스크래치가 비어 있으면 복구 로직이 스킵하므로 포함해도 무비용).
     */
    private static final List<WorldCreationJobStatus> WORLD_STALE_STATUSES = List.of(
        WorldCreationJobStatus.CONCEPT_PROCESSING,
        WorldCreationJobStatus.ILLUSTRATING,
        WorldCreationJobStatus.REVIEW_WAIT,
        WorldCreationJobStatus.BINDING
    );

    private final CharacterCreationJobRepository jobRepository;
    private final UgcWorldCreationJobRepository worldJobRepository;
    private final UgcComfyClient comfyClient;
    private final UgcPipelineWorker worker;
    private final UgcWorldPipelineWorker worldWorker;
    private final UgcJobJson json;
    private final UgcPipelineProperties props;

    /** webhook 유실 대비 폴링 폴백 — 미결 RunPod 잡만 재확인. */
    @Scheduled(fixedRate = 60 * 1000)
    public void pollPendingComfyJobs() {
        if (!props.runpod().configured()) return;

        List<CharacterCreationJob> jobs = jobRepository.findByStatusIn(COMFY_PROCESSING_STATUSES);
        for (CharacterCreationJob job : jobs) {
            Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
            for (Map.Entry<String, String> entry : scratch.entrySet()) {
                if (!UgcPipelineWorker.isExternalJobKey(entry.getKey())) continue; // K_* 내부 키 스킵
                try {
                    UgcComfyClient.JobStatus status = comfyClient.getStatus(entry.getValue());
                    if (status.inFlight() || "ERROR".equals(status.status())) continue;

                    ParsedKey parsed = parseKey(entry.getKey());
                    if (parsed == null) continue;
                    log.info("[UGC-POLL] 폴백 이벤트 공급: jobId={}, key={}, status={}",
                        job.getId(), entry.getKey(), status.status());
                    worker.onComfyEvent(job.getId(), parsed.stage(), parsed.tag(), status);
                } catch (Exception e) {
                    log.warn("[UGC-POLL] 폴링 실패: jobId={}, key={} — {}", job.getId(), entry.getKey(), e.getMessage());
                }
            }
        }
    }

    /** *_WAIT 방치 만료 — 무환불 종결. */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void expireAbandonedWaits() {
        List<CharacterCreationJob> expired =
            jobRepository.findByStatusInAndExpiresAtBefore(WAIT_STATUSES, LocalDateTime.now());
        for (CharacterCreationJob job : expired) {
            worker.expireJob(job.getId());
        }
        if (!expired.isEmpty()) {
            log.info("[UGC-POLL] TTL 만료 처리: {}건", expired.size());
        }
    }

    /** [세계관 빌더] *_WAIT 방치 만료 — 무환불 종결. */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void expireAbandonedWorldWaits() {
        List<UgcWorldCreationJob> expired =
            worldJobRepository.findByStatusInAndExpiresAtBefore(WORLD_WAIT_STATUSES, LocalDateTime.now());
        for (UgcWorldCreationJob job : expired) {
            worldWorker.expireJob(job.getId());
        }
        if (!expired.isEmpty()) {
            log.info("[UGC-POLL] 월드 TTL 만료 처리: {}건", expired.size());
        }
    }

    /** [2026-07-21] 캐릭터 잡 LLM 구간 스테일 판정(분) — Stage0 재시도 최악 소요(~7분)의 여유 배수. */
    private static final int CONCEPT_STALE_MINUTES = 30;

    /**
     * [2026-07-21 리뷰 픽스] 캐릭터 잡 CONCEPT_PROCESSING 스테일 스윕 — Stage0/외형 재구조화
     * LLM 구간은 외부 잡 id가 없어 폴링 폴백이 못 잡는다. 서버 재시작으로 @Async가 유실되면
     * 영구 고착(동시 1잡 정책으로 신규 생성까지 차단)이므로 30분 무진행 시 실패·전액 환불.
     * 미결 RunPod 잡(GOLDEN)이 있으면 폴링 폴백 담당이므로 스킵.
     * (BASE/EMOTIONS의 fal(Qwen) 구간 유실은 별도 태스크 — requestId 선확보 이관 예정)
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void recoverStaleConceptJobs() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(CONCEPT_STALE_MINUTES);
        List<CharacterCreationJob> stale =
            jobRepository.findByStatusAndUpdatedAtBefore(CreationJobStatus.CONCEPT_PROCESSING, cutoff);
        for (CharacterCreationJob job : stale) {
            boolean hasPendingExternal = json.readScratch(job.getExternalJobsJson()).keySet().stream()
                .anyMatch(UgcPipelineWorker::isExternalJobKey);
            if (hasPendingExternal) continue; // WF-1 제출됨 — 폴링 폴백이 복구
            log.warn("[UGC-POLL] 스테일 CONCEPT_PROCESSING 회수 (LLM 구간 유실): jobId={}", job.getId());
            worker.failAndRefund(job.getId(), "컨셉 처리 시간 초과 — 사용한 에너지는 전액 환불되었어요.");
        }
    }

    /**
     * [세계관 빌더] 스테일 잡 복구 — N분(기본 30) 무진행 PROCESSING 잡을 requestId 재부착/재제출로
     * 복구하고, 복구 불가(CONCEPT_PROCESSING 유실)는 실패·전액 환불한다.
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void recoverStaleWorldJobs() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(props.world().staleMinutes());
        List<UgcWorldCreationJob> stale =
            worldJobRepository.findByStatusInAndUpdatedAtBefore(WORLD_STALE_STATUSES, cutoff);
        for (UgcWorldCreationJob job : stale) {
            try {
                worldWorker.recoverStaleJob(job.getId());
            } catch (Exception e) {
                log.warn("[UGC-POLL] 월드 스테일 복구 실패: jobId={} — {}", job.getId(), e.getMessage());
            }
        }
    }

    // externalJobs 키 규약: "GOLDEN" | "BASE_REFINE:0" | "EMOTION_REFINE:JOY" | "CUTOUT:JOY"
    private ParsedKey parseKey(String key) {
        try {
            int idx = key.indexOf(':');
            if (idx < 0) {
                return new ParsedKey(UgcStage.valueOf(key), null);
            }
            return new ParsedKey(
                UgcStage.valueOf(key.substring(0, idx)),
                key.substring(idx + 1));
        } catch (IllegalArgumentException e) {
            log.warn("[UGC-POLL] 알 수 없는 externalJobs 키: {}", key);
            return null;
        }
    }

    private record ParsedKey(UgcStage stage, String tag) {}
}
