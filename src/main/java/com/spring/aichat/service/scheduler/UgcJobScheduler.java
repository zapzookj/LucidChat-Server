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
 *       재확인해 {@link UgcPipelineWorker#onComfyEvent}에 공급 (이벤트 경로 공용·멱등).
 *       [D-3.2a] /status 404(결과 소실)는 일시 오류와 구분해 실패 이벤트로 전환한다.</li>
 *   <li><b>TTL 만료</b> (10분): {@code *_WAIT} 72h 방치 잡을 EXPIRED 종결 (무환불 정책).</li>
 *   <li><b>스테일 스윕</b> (5분): [D-3.1a/b/d · D-3.2b] 무진행 PROCESSING/POSTPROCESSING/BINDING 잡을
 *       {@link UgcPipelineWorker#recoverStaleJob}으로 회수 — 월드 트랙과 동형. 종전엔 CONCEPT_PROCESSING만
 *       봤고 키가 있으면 무조건 폴러에 위임해 폴러의 영구 ERROR 스킵과 데드락을 이뤘다.</li>
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

    /**
     * [D-3.1a/b/d] 캐릭터 잡 스테일 스윕 대상 — 월드 {@link #WORLD_STALE_STATUSES}와 동형.
     * REVIEW_WAIT는 리롤 in-flight(Qwen future) 유실 케이스 — 미결 감정이 없으면 회수가 no-op이라 포함 무비용.
     * GACHA_WAIT/BASE_WAIT는 순수 유저 대기라 TTL 스윕 담당.
     */
    private static final List<CreationJobStatus> CHAR_STALE_STATUSES = List.of(
        CreationJobStatus.CONCEPT_PROCESSING,
        CreationJobStatus.BASE_PROCESSING,
        CreationJobStatus.EMOTIONS_PROCESSING,
        CreationJobStatus.REVIEW_WAIT,
        CreationJobStatus.POSTPROCESSING,
        CreationJobStatus.BINDING
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
                    // 일시 오류(네트워크·5xx 합성 ERROR)는 다음 주기 재시도 — 장기화는 스테일 스윕의 하드 컷오프가 회수.
                    //   ⚠ 여기서 ERROR를 실패로 흘리면 폴 실패 1회가 재시도 예산을 태운다(retryStageOrFail) — 그래서 스킵.
                    if (status.inFlight() || status.transientError()) continue;

                    UgcPipelineWorker.ExternalKey parsed = UgcPipelineWorker.parseExternalKey(entry.getKey());
                    if (parsed == null) {
                        log.warn("[UGC-POLL] 알 수 없는 externalJobs 키: {}", entry.getKey());
                        continue;
                    }
                    if (status.notFound()) {
                        // [D-3.2a] 404 = 결과 퍼지 확정. 종전엔 ERROR와 동일 취급으로 영구 스킵 + 키 잔존 → D-3.2b 데드락.
                        log.warn("[UGC-POLL] 외부 잡 결과 소실(404): jobId={}, key={}", job.getId(), entry.getKey());
                        worker.injectLostExternalJob(job.getId(), entry.getKey(), entry.getValue(), "외부 잡 결과 소실(404)");
                        continue;
                    }
                    log.info("[UGC-POLL] 폴백 이벤트 공급: jobId={}, key={}, status={}",
                        job.getId(), entry.getKey(), status.status());
                    worker.onComfyEvent(job.getId(), parsed.stage(), parsed.token(), status);
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

    /**
     * [D-3.1a/b/d · D-3.2b] 캐릭터 잡 통합 스테일 스윕 — N분(기본 30) 무진행 잡을
     * {@link UgcPipelineWorker#recoverStaleJob}으로 회수한다(외부 키 없는 유실분은 즉시 재제출·재실행,
     * 키 있는 항목은 폴러 담당). 하드 컷오프(기본 90분)를 넘긴 잡은 미결 키까지 '결과 소실'로 주입한다 —
     * 종전 {@code recoverStaleConceptJobs}는 CONCEPT_PROCESSING만 보고 키가 있으면 무조건 위임해,
     * 폴러의 영구 ERROR 스킵과 함께 '키의 존재'를 근거로 서로 미루는 데드락을 이뤘다.
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void recoverStaleCharacterJobs() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleCutoff = now.minusMinutes(props.job().staleMinutes());
        LocalDateTime hardCutoff = now.minusMinutes(props.job().hardStaleMinutes());
        List<CharacterCreationJob> stale =
            jobRepository.findByStatusInAndUpdatedAtBefore(CHAR_STALE_STATUSES, staleCutoff);
        for (CharacterCreationJob job : stale) {
            boolean hardStale = isHardStale(job.getUpdatedAt(), hardCutoff);
            try {
                worker.recoverStaleJob(job.getId(), hardStale);
            } catch (Exception e) {
                log.warn("[UGC-POLL] 캐릭터 스테일 복구 실패: jobId={} — {}", job.getId(), e.getMessage());
            }
        }
    }

    /** 폴러 위임 만료 판정 — updatedAt이 없으면(이론상 불가) 보수적으로 만료 아님. 순수 판정(테스트 대상). */
    static boolean isHardStale(LocalDateTime updatedAt, LocalDateTime hardCutoff) {
        return updatedAt != null && updatedAt.isBefore(hardCutoff);
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
}
