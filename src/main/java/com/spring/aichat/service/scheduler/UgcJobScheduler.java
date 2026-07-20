package com.spring.aichat.service.scheduler;

import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
import com.spring.aichat.domain.ugc.CreationJobStatus;
import com.spring.aichat.external.UgcComfyClient;
import com.spring.aichat.service.ugc.UgcJobJson;
import com.spring.aichat.service.ugc.UgcPipelineWorker;
import com.spring.aichat.service.ugc.UgcStage;
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

    private final CharacterCreationJobRepository jobRepository;
    private final UgcComfyClient comfyClient;
    private final UgcPipelineWorker worker;
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
