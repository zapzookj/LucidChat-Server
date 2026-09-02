package com.spring.aichat.service.illustration.scene;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.SceneIllustrationProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.illustration.SceneIllustration;
import com.spring.aichat.domain.illustration.SceneIllustrationRepository;
import com.spring.aichat.domain.user.EnergySplit;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.external.SceneComfyClient;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * [2026-07-30 B-2/A-1 재피벗] 매턴 실시간 씬 일러 오케스트레이션 — 디오라마 검증본 개별 포팅.
 *
 * <p>docs/09 §A-1 확정 구성: LLM skip 플래그 + scene_hash(장소+행위+cast SHA) 이중 디덥(비용 통제) /
 * 비동기 제출+폴링(2s×360=12분 — 콜드스타트 기준) / IN_QUEUE 90s 하트비트 경고.
 * 콘텐츠 통제: 입력은 프로드 채팅 파이프라인의 모더레이션(키워드 게이트·인젝션 기록)을 이미
 * 통과한 LLM 산출만 — 무검열 샌드박스 프롬프트는 이식하지 않았다(모더레이션 게이트 유지).
 *
 * <p>과금: v1 배선은 무과금(스킵 디덥이 비용 통제) — 에너지 정책은 활성화 전 종원 확정 필요.
 */
@Slf4j
@Service
public class SceneRenderService {

    private static final int MAX_POLL_ATTEMPTS = 360;   // ~12분 (2s 간격) — 콜드스타트 실측 기준
    private static final long POLL_INTERVAL_MS = 2000L;
    private static final int HEARTBEAT_EVERY = 15;      // 30s마다 현재 RunPod 상태 로그

    private final SceneIllustrationProperties props;
    private final ScenePromptAssembler assembler;
    private final SceneWorkflowFactory workflowFactory;
    private final SceneComfyClient comfyClient;
    private final SceneAssetService assetService;
    private final SceneRenderWriteService writeService;
    private final SceneIllustrationRepository repository;
    /**
     * [리뷰픽스] 폴링 전용 executor 명시 제출 — @Async 자기호출은 프록시를 우회해 폴링(최대 12분)이
     * 채팅 SSE 스레드에서 동기 실행되던 크리티컬. 전용 풀로 채팅 스트림과 격리.
     */
    private final java.util.concurrent.Executor sceneRenderExecutor;

    public SceneRenderService(SceneIllustrationProperties props, ScenePromptAssembler assembler,
                              SceneWorkflowFactory workflowFactory, SceneComfyClient comfyClient,
                              SceneAssetService assetService, SceneRenderWriteService writeService,
                              SceneIllustrationRepository repository,
                              @org.springframework.beans.factory.annotation.Qualifier("sceneRenderExecutor")
                              java.util.concurrent.Executor sceneRenderExecutor) {
        this.props = props;
        this.assembler = assembler;
        this.workflowFactory = workflowFactory;
        this.comfyClient = comfyClient;
        this.assetService = assetService;
        this.writeService = writeService;
        this.repository = repository;
        this.sceneRenderExecutor = sceneRenderExecutor;
    }

    /** 씬 렌더 트랙 가동 가능 여부 — 배선 지점의 단일 게이트. */
    public boolean ready() {
        return props.ready() && comfyClient.configured();
    }

    /**
     * [2026-07-31 에픽 B] 인밴드 자동 경로 게이트 — 트리거 기본값이 manual로 바뀌면서
     * 매턴 resolveForTurn 배선(V1 ChatStreamService)은 {@code trigger=auto}일 때만 살아난다.
     */
    public boolean autoReady() {
        return ready() && props.isAutoTrigger();
    }

    /** 프론트 폴링/네비게이션 응답용 뷰. */
    public record SceneView(Long id, int turnIndex, String status, String imageUrl) {
        static SceneView of(SceneIllustration s) {
            return new SceneView(s.getId(), s.getTurnIndex(), s.getStatus(), s.getImageUrl());
        }
    }

    /**
     * 턴 처리 — 디덥 판단 후 SKIPPED 행(재사용) 또는 PENDING 행(+비동기 렌더)을 만든다.
     * 채팅 흐름을 절대 깨지 않는다(실패는 로그만 — 호출측 try-catch 이중 방어).
     *
     * @param roomCharacters 방 캐릭터(V1 SANDBOX=1인, V2 확장 대비 리스트)
     * @param turnIndex      현재 턴 수(로그 카운트 기준)
     */
    public SceneView resolveForTurn(Long roomId, List<Character> roomCharacters,
                                    AiJsonOutput out, int turnIndex, boolean sfw) {
        SceneRenderPlan plan = planRender(roomCharacters, out, sfw);

        // [리뷰픽스] 인플라이트 디덥 — 동일 scene_hash 렌더가 아직 PENDING/GENERATING이면
        // 새 RunPod 잡을 제출하지 않고 그 행을 그대로 반환(프론트가 같은 id 폴링).
        // 기존엔 최신 COMPLETED만 비교해 콜드스타트 중 연속 턴이 동일 씬을 중복 과금했다.
        SceneIllustration latest = repository.findTopByChatRoomIdOrderByIdDesc(roomId).orElse(null);
        if (latest != null && !latest.isTerminal() && plan.sceneHash().equals(latest.getSceneHash())) {
            log.info("[SCENE-RENDER] 인플라이트 재사용: roomId={} turn={} illustrationId={}",
                roomId, turnIndex, latest.getId());
            return SceneView.of(latest);
        }

        SceneIllustration last = repository
            .findTopByChatRoomIdAndStatusOrderByIdDesc(roomId, "COMPLETED").orElse(null);

        boolean llmSkip = out != null && Boolean.TRUE.equals(out.skipIllustrationRegen());
        boolean sameScene = last != null && plan.sceneHash().equals(last.getSceneHash());
        if ((llmSkip || sameScene) && last != null) {
            SceneIllustration skipped = repository.save(SceneIllustration.skipped(
                roomId, turnIndex, plan.sceneHash(), last.getId(), last.getImageUrl()));
            log.info("[SCENE-RENDER] 스킵(재사용): roomId={} turn={} 이유={}",
                roomId, turnIndex, llmSkip ? "llm-skip" : "same-hash");
            return SceneView.of(skipped);
        }

        SceneIllustration pending = repository.save(SceneIllustration.pending(
            roomId, turnIndex, plan.sceneHash(), plan.prompt().fullPrompt()));
        // [리뷰픽스] @Async 자기호출(프록시 우회) 대신 전용 executor 명시 제출.
        // 풀 포화(AbortPolicy) 시 렌더만 실패 처리 — 채팅 흐름은 계속.
        try {
            sceneRenderExecutor.execute(() -> render(pending.getId(), plan.prompt(), roomId, turnIndex));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            writeService.failRender(pending.getId(), "씬 렌더 풀 포화 — 렌더 유실");
        }
        return SceneView.of(pending);
    }

    /**
     * [리뷰픽스] 고아 판정 컷오프 — 렌더 최장 수명은 폴링 12분 + 마진. 이보다 오래된 비종결 행은
     * 서버 재시작/크래시로 render() 스레드를 잃은 고아다(정상 경로는 반드시 12분 내 종결).
     */
    private static final long STALE_IN_FLIGHT_MINUTES = 20;

    /**
     * [2026-07-31 에픽 B] 인플라이트 뷰 — 방의 마지막 행이 아직 렌더 중이면 반환.
     * 수동 요청의 과금 전 중복 가드(같은 방 동시 1렌더 원칙 — 프론트 버튼 비활성의 서버측 짝).
     *
     * <p>[리뷰픽스] 고아 행 지연 스윕: 컷오프를 넘긴 비종결 행은 여기서 실패 종결시킨다
     * (failRender가 MANUAL 행의 에너지를 원요청자에게 환불). 스케줄러 없이도 다음 요청이
     * 방을 자연 복구 — 서버 재시작으로 방이 영구 409에 갇히던 문제의 해소.
     */
    public SceneView inFlightView(Long roomId) {
        SceneIllustration latest = repository.findTopByChatRoomIdOrderByIdDesc(roomId)
            .filter(s -> !s.isTerminal())
            .orElse(null);
        if (latest == null) return null;
        if (latest.getUpdatedAt() != null && latest.getUpdatedAt()
            .isBefore(java.time.LocalDateTime.now().minusMinutes(STALE_IN_FLIGHT_MINUTES))) {
            log.warn("[SCENE-RENDER] 고아 비종결 행 지연 스윕: roomId={} illustrationId={} status={}",
                roomId, latest.getId(), latest.getStatus());
            writeService.failRender(latest.getId(), "서버 재시작 등으로 유실된 렌더 — 자동 정리(환불)");
            return null;
        }
        return SceneView.of(latest);
    }

    /**
     * [리뷰픽스] 렌더 풀 포화 신호 — <b>환불은 이미 failRender가 완료한 상태</b>임을 타입으로 전달.
     * 호출측(SceneRequestService)은 이 예외에서 절대 재환불하지 않는다(이중 환불 결함의 재발 방지 계약).
     */
    public static class RenderPoolSaturatedException extends RuntimeException {
        public RenderPoolSaturatedException(Throwable cause) {
            super("씬 렌더 풀 포화", cause);
        }
    }

    /**
     * [2026-07-31 에픽 B] 수동 요청 제출 — 씬 디렉터 스펙으로 즉시 렌더.
     * 디덥 없음(유저가 명시적으로 과금 요청 — 같은 씬 재요청도 새 시드로 새 렌더).
     * 과금·환불 정산은 행에 기록(requestedBy/energyCharged) — 실패 시 failRender가 환불.
     *
     * @throws RenderPoolSaturatedException 렌더 풀 포화 — <b>환불 완료됨</b>, 호출측은 재환불 금지
     */
    public SceneView submitManual(Long roomId, List<Character> cast,
                                  AiJsonOutput.SceneIllustrationSpec spec, int turnIndex,
                                  boolean sfw, Long requestedBy, EnergySplit charge) {
        SceneRenderPlan plan = planRender(cast, spec, sfw);
        SceneIllustration pending = repository.save(SceneIllustration.pendingManual(
            roomId, turnIndex, plan.sceneHash(), plan.prompt().fullPrompt(),
            requestedBy, charge));
        try {
            sceneRenderExecutor.execute(() -> render(pending.getId(), plan.prompt(), roomId, turnIndex));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // failRender가 행 기반 멱등 환불까지 수행 — 전용 예외로 '환불 완료' 사실을 전파
            writeService.failRender(pending.getId(), "씬 렌더 풀 포화 — 렌더 유실");
            throw new RenderPoolSaturatedException(e);
        }
        log.info("[SCENE-RENDER] 수동 제출: roomId={} turn={} illustrationId={} by={}",
            roomId, turnIndex, pending.getId(), requestedBy);
        return SceneView.of(pending);
    }

    /** 프론트 폴링 — 방 스코프 검증은 컨트롤러(AuthGuard)가 담당. */
    public SceneView getView(Long roomId, Long illustrationId) {
        return repository.findById(illustrationId)
            .filter(s -> s.getChatRoomId().equals(roomId))
            .map(SceneView::of)
            .orElse(null);
    }

    /** 씬 네비게이션(A-2) — 방의 턴별 씬 목록(오름차순). */
    public List<SceneView> listViews(Long roomId) {
        return repository.findByChatRoomIdOrderByIdAsc(roomId).stream()
            .map(SceneView::of)
            .toList();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  계획 (순수 함수 — 단위 테스트 대상)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record SceneRenderPlan(ScenePromptAssembler.ScenePrompt prompt, String sceneHash) {}

    /** 테스트 편의 오버로드 — sfw 기본값 true. */
    public SceneRenderPlan planRender(List<Character> roomCharacters, AiJsonOutput out) {
        return planRender(roomCharacters, out, true);
    }

    /**
     * LLM 출력 + 방 캐릭터로 씬 positive와 scene_hash를 계산.
     *
     * @param sfw [리뷰픽스 수위 게이트] 비시크릿 방이면 true — sfw 태그 강제 + NSFW 밴.
     *            시크릿 방(SecretModeService 통과)만 false.
     */
    public SceneRenderPlan planRender(List<Character> roomCharacters, AiJsonOutput out, boolean sfw) {
        return planRender(roomCharacters, out == null ? null : out.sceneIllustration(), sfw);
    }

    /** [2026-07-31 에픽 B] 스펙 직접 수용 오버로드 — 씬 디렉터(수동 경로) 산출용. */
    public SceneRenderPlan planRender(List<Character> roomCharacters,
                                      AiJsonOutput.SceneIllustrationSpec spec, boolean sfw) {
        String location = spec == null ? "" : nz(spec.locationDescription());
        String action = spec == null ? "" : nz(spec.actionDescription());

        Map<String, Character> byName = roomCharacters.stream()
            .collect(Collectors.toMap(c -> c.getName().toLowerCase(Locale.ROOT), c -> c, (a, b) -> a));

        List<ScenePromptAssembler.SceneActor> actors = new ArrayList<>();
        StringBuilder castKey = new StringBuilder();

        if (spec != null && spec.cast() != null && !spec.cast().isEmpty()) {
            for (AiJsonOutput.SceneCast c : spec.cast()) {
                boolean male = c.isMale();
                castKey.append('|').append(nz(c.ref())).append(':').append(male ? 'M' : 'F')
                    .append(':').append(nz(c.emotion())).append(':').append(nz(c.pose()));
                if (c.isUser()) {
                    actors.add(new ScenePromptAssembler.SceneActor(null, c.emotion(), c.pose(), true, male));
                } else {
                    Character hero = c.ref() == null ? null : byName.get(c.ref().toLowerCase(Locale.ROOT));
                    String tags = hero != null ? hero.getAppearanceTags() : null;
                    // [2026-08-04 남캐] DB 성별이 권위 — LLM cast.gender는 미등록 ref 폴백만
                    boolean actorMale = hero != null ? hero.getGenderOrDefault().isMale() : male;
                    actors.add(new ScenePromptAssembler.SceneActor(tags, c.emotion(), c.pose(), false, actorMale));
                }
            }
        } else {
            // 폴백: cast 미제공 시 방 히로인 전원 등장 — 성별은 DB 기준([2026-08-04 남캐])
            for (Character hero : roomCharacters) {
                castKey.append('|').append(hero.getName());
                actors.add(new ScenePromptAssembler.SceneActor(hero.getAppearanceTags(), null, null,
                    false, hero.getGenderOrDefault().isMale()));
            }
        }

        ScenePromptAssembler.ScenePrompt prompt = assembler.assemble(location, action, actors, sfw);
        String sceneHash = sha256(location + "##" + action + "##" + castKey);
        return new SceneRenderPlan(prompt, sceneHash);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  렌더 본체 — 제출 + 폴링 + S3 복사 (sceneRenderExecutor 스레드에서만 실행)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    void render(Long illustrationId, ScenePromptAssembler.ScenePrompt prompt,
                Long roomId, int turnIndex) {
        if (!comfyClient.configured()) {
            writeService.failRender(illustrationId, "illustration.scene.runpod 미설정");
            return;
        }
        try {
            ObjectNode workflow = workflowFactory.buildScene(prompt, "scene_r" + roomId + "_t" + turnIndex);
            SceneComfyClient.SubmitResult submit = comfyClient.submit(workflow);
            writeService.markSubmitted(illustrationId, submit.jobId());
            log.info("[SCENE-RENDER] 제출 illustrationId={} runpodJob={} 초기상태={}",
                illustrationId, submit.jobId(), submit.status());

            String lastStatus = submit.status();
            for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
                sleep(POLL_INTERVAL_MS);
                SceneComfyClient.JobStatus status = comfyClient.getStatus(submit.jobId());

                // 상태 전이 + 30s 하트비트 — IN_QUEUE 장기 대기(워커 미기동)를 백엔드에서 가시화
                boolean changed = !status.status().equalsIgnoreCase(lastStatus);
                if (changed || attempt % HEARTBEAT_EVERY == HEARTBEAT_EVERY - 1) {
                    long elapsed = (long) (attempt + 1) * POLL_INTERVAL_MS / 1000;
                    if ("IN_QUEUE".equalsIgnoreCase(status.status()) && elapsed >= 90) {
                        log.warn("[SCENE-RENDER] runpodJob={} {}s째 IN_QUEUE — 워커 미기동/미가용 의심 "
                            + "(RunPod 엔드포인트 Workers 탭·Container Disk·GPU 가용성 확인)", submit.jobId(), elapsed);
                    } else {
                        log.info("[SCENE-RENDER] runpodJob={} status={} ({}s 경과)", submit.jobId(), status.status(), elapsed);
                    }
                    lastStatus = status.status();
                }

                if (status.completed()) {
                    if (status.images().isEmpty()) {
                        writeService.failRender(illustrationId, "완료됐으나 산출 이미지 없음");
                        return;
                    }
                    String presigned = status.images().get(0).data();
                    String publicUrl = assetService.storeScene(presigned, roomId, turnIndex);
                    writeService.completeRender(illustrationId, publicUrl);
                    log.info("[SCENE-RENDER] 렌더 완료 illustrationId={} url={}", illustrationId, publicUrl);
                    return;
                }
                if (status.failed()) {
                    writeService.failRender(illustrationId, "RunPod 실패: " + nz(status.error()));
                    return;
                }
            }
            long timeoutSec = (long) MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000;
            writeService.failRender(illustrationId, "렌더 타임아웃(" + timeoutSec + "s, 마지막 상태 " + lastStatus + ")");
        } catch (Exception e) {
            log.error("[SCENE-RENDER] 렌더 예외 illustrationId={}: {}", illustrationId, e.getMessage());
            writeService.failRender(illustrationId, "렌더 예외: " + e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
