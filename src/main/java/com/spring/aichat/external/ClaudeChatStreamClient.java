package com.spring.aichat.external;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.spring.aichat.config.ClaudeConsoleProperties;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.external.OpenRouterStreamClient.StreamResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.service.stream.SceneStreamExtractor;
import com.spring.aichat.service.util.LlmOutputParser;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Claude Console(Anthropic 직결) 채팅 스트리밍 — OpenRouter 앞단의 1차 경로.
 *
 * <p>{@link #tryStream}은 <b>절대 던지지 않는다</b>. Claude로 응답을 끝까지 받았으면 결과를,
 * 아니면(키 없음·크레딧 소진·인증 실패·과부하·첫 토큰 지연·거절·스트림 중단) 실패를 돌려주고
 * 호출부가 기존 OpenRouter 경로를 그대로 탄다.
 *
 * <p>[모델] 2026-11-14~ 일반 = Sonnet 5.5(적응형 사고 + 낮은 effort — 사고를 끄면 `reasoning` 필드 때문에
 * 거절된다, buildParams 주석 참조), 부스트 = 모델 공란 → Claude를 건너뛰고 OpenRouter pro-model(Gemini Pro).
 * 사고 on/off는 모드가 아니라 모델명으로 정한다(Haiku만 끔). temperature 등 샘플링 파라미터는 400이므로 보내지 않는다.
 *
 * <p>[스레딩] SDK 스트림 읽기는 워커 스레드, 콜백(onFirstScene·onEventStatus)은 <b>호출 스레드</b>에서
 * 실행한다 — 콜백이 SseEmitter와 지연 로딩 엔티티를 만지기 때문에 기존 OpenRouter 클라이언트와
 * 같은 스레드 모델을 유지한다. 호출 스레드는 큐를 데드라인 걸고 폴링하므로 연결 단계 지연까지 잡는다.
 */
@Slf4j
@Component
public class ClaudeChatStreamClient {

    /** 첫 토큰 이후 토큰 간 최대 무응답 시간. */
    private static final long IDLE_TIMEOUT_MS = 30_000;

    private static final String JSON_ONLY_RULE =
        "Output exactly one JSON object and nothing else: no markdown code fences, no text before or after the JSON.";

    private final ClaudeConsoleProperties props;
    private final AnthropicClient client;   // null = 비활성
    private final ExecutorService io;
    /** 연결은 됐지만 아직 텍스트가 없는 구간(적응형 사고 중)의 최대 대기. */
    private static final long THINKING_TIMEOUT_MS = 20_000;

    /** 쿨다운 없는 요청 오류(4xx)가 이만큼 연속되면 설정 문제로 보고 길게 끈다(잘못된 모델 ID·effort 등). */
    private static final int BAD_REQUEST_STREAK_LIMIT = 3;

    /** 적응형 사고 모델은 사고 토큰이 max_tokens를 같이 쓴다 — 잘림 방지 여유분. */
    private static final int THINKING_TOKEN_HEADROOM = 4096;

    private static final Object HEARTBEAT = new Object();
    private static final ObjectMapper VALIDATOR = new ObjectMapper();

    /** 키 단위 차단(크레딧 소진·인증 실패) — 모델과 무관하게 전체. */
    private final AtomicLong keySkipUntil = new AtomicLong(0);
    /** 모델 단위 차단(과부하·레이트리밋·지연) — [0]=일반, [1]=부스트. 한쪽 문제가 다른 쪽을 끄지 않는다. */
    private final AtomicLong[] modelSkipUntil = {new AtomicLong(0), new AtomicLong(0)};
    private final AtomicInteger[] badRequestStreak = {new AtomicInteger(0), new AtomicInteger(0)};

    public ClaudeChatStreamClient(ClaudeConsoleProperties props) {
        this.props = props;
        if (props.enabled()) {
            // maxRetries(0): 429/5xx를 SDK가 붙잡고 재시도하면 그만큼 폴백이 늦어진다 — 실패는 즉시 OpenRouter로 넘긴다.
            // timeout은 호출 전체 상한이라 넉넉히 — 멈춤 감지는 호출 스레드의 데드라인(TTFT·무응답)이 맡는다.
            this.client = AnthropicOkHttpClient.builder()
                .apiKey(props.apiKey().trim())
                .maxRetries(0)
                .timeout(Duration.ofSeconds(300))
                .build();
            log.info("🟣 [CLAUDE] 직결 경로 활성 | model={} | boostModel={}", props.model(),
                props.boostModel().isBlank() ? "(없음 — 부스트는 OpenRouter 직행)" : props.boostModel());
        } else {
            this.client = null;
            log.info("🟣 [CLAUDE] CLAUDE_API_KEY 없음 — 직결 경로 비활성(OpenRouter만 사용)");
        }
        this.io = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "claude-stream");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * @param result            성공 시 결과, 실패·건너뜀이면 null
     * @param firstSceneEmitted 실패했지만 first_scene은 이미 클라이언트로 나갔는지 —
     *                          true면 폴백 경로는 first_scene을 다시 쏘면 안 된다(중복 표시)
     */
    public record Attempt(StreamResult result, boolean firstSceneEmitted, boolean eventStatusEmitted) {
        public boolean succeeded() { return result != null; }
    }

    private static final Attempt SKIPPED = new Attempt(null, false, false);

    /**
     * Claude로 스트리밍을 시도한다.
     *
     * @param boost      부스트 모드 여부 (true = boostModel, 공란이면 시도하지 않는다)
     * @param secretMode 시크릿 모드 여부 — true면 시도하지 않는다(성인 수위 텍스트는 Anthropic
     *                   이용정책상 거절 대상이라 매 턴 거절→폴백 지연만 생긴다)
     * @param messages   OpenAI 형식 메시지(기존 빌더 산출물 그대로)
     */
    public Attempt tryStream(boolean boost, boolean secretMode, List<OpenAiMessage> messages, int maxTokens,
                             Consumer<String> onFirstScene, Consumer<String> onEventStatus) {
        if (client == null || secretMode) return SKIPPED;
        String model = boost ? props.boostModel() : props.model();
        // 모델 공란 = 이 모드는 Claude를 쓰지 않는다(2026-11-14~ 부스트는 OpenRouter pro-model 직행).
        if (model == null || model.isBlank()) return SKIPPED;
        long now = System.currentTimeMillis();
        int slot = boost ? 1 : 0;
        if (now < keySkipUntil.get() || now < modelSkipUntil[slot].get()) return SKIPPED;

        MessageCreateParams params;
        try {
            params = buildParams(model, boost, messages, maxTokens);
        } catch (Exception e) {
            log.error("🟣 [CLAUDE] 요청 조립 실패 — OpenRouter로 폴백", e);
            return SKIPPED;
        }

        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        AtomicBoolean abandoned = new AtomicBoolean(false);
        AtomicReference<StreamResponse<RawMessageStreamEvent>> streamRef = new AtomicReference<>();
        io.execute(() -> pump(params, queue, abandoned, streamRef));

        StringBuilder buffer = new StringBuilder(4096);
        SceneStreamExtractor extractor = new SceneStreamExtractor();
        boolean firstSceneEmitted = false;
        boolean eventStatusEmitted = false;
        boolean connected = false;
        long ttft = -1, ttfs = -1;

        try {
            while (true) {
                // 3단 데드라인: 연결 전(서버 무응답) → 연결 후 텍스트 전(사고 중) → 텍스트 흐르는 중
                long wait = ttft >= 0 ? IDLE_TIMEOUT_MS : connected ? THINKING_TIMEOUT_MS : props.ttftDeadlineMs();
                Object item = queue.poll(wait, TimeUnit.MILLISECONDS);

                if (item == null) {
                    abandon(abandoned, streamRef);
                    modelCooldown(slot, props.errorCooldownSec());
                    log.warn("🟣 [CLAUDE] {} ({}ms) — OpenRouter로 폴백 | model={}",
                        ttft >= 0 ? "토큰 간 무응답" : connected ? "첫 텍스트 지연(사고 구간)" : "연결 데드라인 초과", wait, model);
                    return new Attempt(null, firstSceneEmitted, eventStatusEmitted);
                }
                if (item == HEARTBEAT) {
                    connected = true;
                    continue;
                }
                if (item instanceof Throwable t) {
                    classifyFailure(t, model, slot);
                    return new Attempt(null, firstSceneEmitted, eventStatusEmitted);
                }
                if (item instanceof End end) {
                    badRequestStreak[slot].set(0);
                    String problem = unusableReason(end, buffer.toString());
                    if (problem != null) {
                        // "끝까지 받았지만 못 쓰는 응답"도 실패다 — 성공으로 돌려주면 하류 파싱 실패로 턴이 통째로 날아간다.
                        log.warn("🟣 [CLAUDE] {} — OpenRouter로 폴백 | model={} | max_tokens={} | chars={}",
                            problem, model, maxTokens, buffer.length());
                        return new Attempt(null, firstSceneEmitted, eventStatusEmitted);
                    }
                    log.info("🟣 [CLAUDE] Complete | ttft={}ms | ttfs={}ms | chars={} | model={}",
                        ttft, ttfs, buffer.length(), model);
                    return new Attempt(new StreamResult(buffer.toString(), null, ttft, ttfs),
                        firstSceneEmitted, eventStatusEmitted);
                }

                connected = true;
                String token = (String) item;
                if (token.isEmpty()) continue;
                if (ttft < 0) {
                    ttft = System.currentTimeMillis() - now;
                    log.info("🟣 [CLAUDE] TTFT: {}ms | model={}", ttft, model);
                }
                buffer.append(token);
                String bufferStr = buffer.toString();

                if (!extractor.isEventStatusExtracted() && onEventStatus != null) {
                    String eventStatus = extractor.tryExtractEventStatus(bufferStr);
                    if (eventStatus != null) {
                        eventStatusEmitted = true;
                        try { onEventStatus.accept(eventStatus); }
                        catch (Exception cbErr) { log.warn("🟣 [CLAUDE] onEventStatus callback failed", cbErr); }
                    }
                }
                if (!extractor.isFirstSceneExtracted()) {
                    String firstScene = extractor.tryExtractFirstScene(bufferStr);
                    if (firstScene != null) {
                        ttfs = System.currentTimeMillis() - now;
                        firstSceneEmitted = true;
                        if (onFirstScene != null) {
                            try { onFirstScene.accept(firstScene); }
                            catch (Exception cbErr) { log.warn("🟣 [CLAUDE] onFirstScene callback failed", cbErr); }
                        }
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            abandon(abandoned, streamRef);
            return new Attempt(null, firstSceneEmitted, eventStatusEmitted);
        } catch (RuntimeException e) {
            abandon(abandoned, streamRef);
            log.error("🟣 [CLAUDE] 예기치 못한 오류 — OpenRouter로 폴백 | model={}", model, e);
            return new Attempt(null, firstSceneEmitted, eventStatusEmitted);
        }
    }

    /** 스트림이 끝났을 때 응답을 못 쓰는 이유. 쓸 수 있으면 null. */
    private static String unusableReason(End end, String text) {
        StopReason stop = end.stopReason();
        if (StopReason.REFUSAL.equals(stop)) return "거절(stop_reason=refusal, " + end.refusalDetail() + ")";
        if (stop == null) return "종료 신호 없이 스트림 끝남(부분 응답)";
        if (StopReason.MAX_TOKENS.equals(stop)) return "stop_reason=max_tokens(응답 잘림)";
        if (text.isBlank()) return "빈 응답(stop_reason=" + stop + ")";
        return isChatJson(text) ? null : "JSON 형식 아님(산문 응답·형식 이탈)";
    }

    /** 채팅 응답 계약(V1·V2 공통): scenes 배열을 가진 JSON 객체. */
    static boolean isChatJson(String text) {
        try {
            JsonNode root = VALIDATOR.readTree(LlmOutputParser.extractJson(text));
            return root != null && root.isObject() && root.path("scenes").isArray();
        } catch (Exception e) {
            return false;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  워커: SDK 스트림 → 큐
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record End(StopReason stopReason, String refusalDetail) {}

    private void pump(MessageCreateParams params, BlockingQueue<Object> queue, AtomicBoolean abandoned,
                      AtomicReference<StreamResponse<RawMessageStreamEvent>> streamRef) {
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
            streamRef.set(stream);
            if (abandoned.get()) return;   // 연결 대기 중에 호출부가 떠났다 — try-with-resources가 닫는다

            AtomicReference<StopReason> stop = new AtomicReference<>();
            AtomicReference<String> refusal = new AtomicReference<>("");
            Iterator<RawMessageStreamEvent> it = stream.stream().iterator();
            while (it.hasNext()) {
                if (abandoned.get()) return;
                RawMessageStreamEvent event = it.next();
                var text = event.contentBlockDelta().flatMap(delta -> delta.delta().text());
                if (text.isPresent()) queue.add(text.get().text());
                else queue.add(HEARTBEAT);   // message_start·사고 블록 등 — 서버가 살아 있다는 신호
                event.messageDelta()
                    .flatMap(delta -> delta.delta().stopReason())
                    .ifPresent(stop::set);
                event.messageDelta()
                    .flatMap(delta -> delta.delta().stopDetails())
                    .ifPresent(d -> refusal.set("category=" + d.category().map(Object::toString).orElse("?")
                        + " | " + d.explanation().orElse("")));
            }
            queue.add(new End(stop.get(), refusal.get()));
        } catch (Throwable t) {
            if (!abandoned.get()) queue.add(t);
        }
    }

    private void abandon(AtomicBoolean abandoned, AtomicReference<StreamResponse<RawMessageStreamEvent>> streamRef) {
        abandoned.set(true);
        StreamResponse<RawMessageStreamEvent> s = streamRef.get();
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  실패 분류 → 쿨다운
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void classifyFailure(Throwable t, String model, int slot) {
        if (t instanceof AnthropicServiceException e) {
            int status = e.statusCode();
            String msg = String.valueOf(e.getMessage());
            // 스트림 도중 오류(SSE error 이벤트)는 HTTP 200으로 오므로 상태 코드보다 오류 타입을 먼저 본다.
            String type = e.errorType().map(Object::toString).orElse("").toLowerCase();
            if (isCreditExhausted(status, type, msg)) {
                keyCooldown(props.creditCooldownSec());
                log.warn("🟣 [CLAUDE] 크레딧 소진 감지(HTTP {}) — {}초간 OpenRouter만 사용 | {}",
                    status, props.creditCooldownSec(), brief(msg));
            } else if (status == 401 || status == 403 || type.contains("authentication") || type.contains("permission")) {
                keyCooldown(props.creditCooldownSec());
                log.error("🟣 [CLAUDE] 인증/권한 실패(HTTP {}) — 키 확인 필요. {}초간 OpenRouter만 사용 | {}",
                    status, props.creditCooldownSec(), brief(msg));
            } else if (status == 429 || status >= 500
                || type.contains("overloaded") || type.contains("rate_limit") || type.contains("api_error")
                || type.contains("timeout")) {
                modelCooldown(slot, props.errorCooldownSec());
                log.warn("🟣 [CLAUDE] HTTP {} {} — OpenRouter로 폴백 | model={} | {}", status, type, model, brief(msg));
            } else {
                // 400·404 등 요청 오류 — 한 번은 요청 고유 문제일 수 있어 그 요청만 폴백하고,
                // 연속되면 설정 문제(모델 ID·effort·사고 설정)이므로 길게 끈다. 안 그러면 모든 턴이 왕복 1회를 헛쓴다.
                int streak = badRequestStreak[slot].incrementAndGet();
                if (streak >= BAD_REQUEST_STREAK_LIMIT) {
                    modelCooldown(slot, props.creditCooldownSec());
                    badRequestStreak[slot].set(0);
                }
                log.error("🟣 [CLAUDE] HTTP {} (요청 오류, 연속 {}회) — OpenRouter로 폴백 | model={} | {}",
                    status, streak, model, brief(msg));
            }
            return;
        }
        modelCooldown(slot, props.errorCooldownSec());
        log.warn("🟣 [CLAUDE] 스트림 실패 — OpenRouter로 폴백 | model={} | {}", model, t.toString());
    }

    /** 크레딧 소진은 400 invalid_request_error("credit balance is too low") 또는 402 billing_error로 온다. */
    static boolean isCreditExhausted(int status, String errorType, String message) {
        if (status == 402) return true;
        String type = errorType == null ? "" : errorType.toLowerCase();
        String msg = message == null ? "" : message.toLowerCase();
        return type.contains("billing") || msg.contains("credit balance") || msg.contains("billing");
    }

    private void keyCooldown(long seconds) {
        keySkipUntil.accumulateAndGet(System.currentTimeMillis() + seconds * 1000, Math::max);
    }

    private void modelCooldown(int slot, long seconds) {
        modelSkipUntil[slot].accumulateAndGet(System.currentTimeMillis() + seconds * 1000, Math::max);
    }

    private static String brief(String s) {
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  OpenAI 형식 메시지 → Anthropic Messages 요청
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 변환 결과 — 테스트 가능하도록 SDK 타입과 분리. */
    record Converted(List<String> system, boolean cacheFirstSystem, List<Turn> turns) {}

    record Turn(String role, String content) {}

    /**
     * 기존 빌더는 system 메시지를 앞·중간·뒤에 모두 놓는다(OpenAI 형식에선 허용).
     * Anthropic은 system이 최상위 파라미터이고 대화는 user로 시작·종료해야 하므로:
     * <ul>
     *   <li>맨 앞 system 묶음 → 최상위 system (첫 블록은 정적 프롬프트 = 캐시 대상)</li>
     *   <li>맨 뒤 system 묶음(동적 규칙·출력 형식·액션 주입) → 항상 최상위 system 뒤에 붙인다.
     *       (user 턴으로 내리면 출력 형식의 `reasoning` 요구가 유저 발화로 보여 거절 위험이 커진다.)
     *       대화가 assistant로 끝나면 프리필로 취급돼 400이므로 짧은 합성 user 턴을 붙인다</li>
     *   <li>중간 system(나레이션 등) → user 턴으로 접되, 유저 발화가 아님을 표식으로 밝힌다
     *       (표식 없이 접으면 나레이션이 유저 말로 오귀속된다 — 기존 빌더가 role=system을 쓰는 이유)</li>
     *   <li>같은 role 연속 → 병합, 첫 턴이 assistant면 합성 user 삽입</li>
     * </ul>
     */
    static Converted convert(List<OpenAiMessage> messages) {
        int start = 0;
        while (start < messages.size() && isSystem(messages.get(start))) start++;
        int end = messages.size();
        while (end > start && isSystem(messages.get(end - 1))) end--;

        List<String> system = new ArrayList<>();
        for (int i = 0; i < start; i++) addIfText(system, messages.get(i).content());
        boolean cacheFirst = !system.isEmpty();

        List<Turn> turns = new ArrayList<>();
        for (int i = start; i < end; i++) {
            OpenAiMessage m = messages.get(i);
            if (m.content() == null || m.content().isBlank()) continue;
            if (isSystem(m)) {
                appendTurn(turns, "user", SYSTEM_NOTE_OPEN + m.content() + SYSTEM_NOTE_CLOSE);
            } else {
                appendTurn(turns, "assistant".equals(m.role()) ? "assistant" : "user", m.content());
            }
        }

        for (int i = end; i < messages.size(); i++) addIfText(system, messages.get(i).content());

        boolean endsWithUser = !turns.isEmpty() && "user".equals(turns.get(turns.size() - 1).role());
        if (!endsWithUser) {
            appendTurn(turns, "user", "(계속)");
        }

        if ("assistant".equals(turns.get(0).role())) {
            turns.add(0, new Turn("user", "(입장)"));
        }
        return new Converted(system, cacheFirst, turns);
    }

    static final String SYSTEM_NOTE_OPEN = "<system_note>\n(시스템이 삽입한 서술·지시다. 유저의 발화가 아니다.)\n";
    static final String SYSTEM_NOTE_CLOSE = "\n</system_note>";

    /** Haiku만 사고를 끈다. 그 외(Sonnet·Opus 계열)는 disabled가 400이거나 거절을 부르므로 적응형. */
    private static boolean thinkingOff(String model) {
        return model.startsWith("claude-haiku");
    }

    private static boolean isSystem(OpenAiMessage m) {
        return "system".equals(m.role());
    }

    private static void addIfText(List<String> target, String content) {
        if (content != null && !content.isBlank()) target.add(content);
    }

    private static void appendTurn(List<Turn> turns, String role, String content) {
        if (!turns.isEmpty() && turns.get(turns.size() - 1).role().equals(role)) {
            Turn last = turns.remove(turns.size() - 1);
            turns.add(new Turn(role, last.content() + "\n\n" + content));
        } else {
            turns.add(new Turn(role, content));
        }
    }

    private MessageCreateParams buildParams(String model, boolean boost, List<OpenAiMessage> messages, int maxTokens) {
        Converted c = convert(messages);

        List<TextBlockParam> systemBlocks = new ArrayList<>();
        for (int i = 0; i < c.system().size(); i++) {
            TextBlockParam.Builder block = TextBlockParam.builder().text(c.system().get(i));
            if (i == 0 && c.cacheFirstSystem()) {
                block.cacheControl(CacheControlEphemeral.builder().build());
            }
            systemBlocks.add(block.build());
        }
        // OpenRouter 경로는 response_format=json_object로 강제하던 것 — 여기선 지시문으로 대신한다.
        systemBlocks.add(TextBlockParam.builder().text(JSON_ONLY_RULE).build());

        MessageCreateParams.Builder b = MessageCreateParams.builder()
            .model(model)
            .maxTokens((long) maxTokens + (thinkingOff(model) ? 0 : THINKING_TOKEN_HEADROOM))
            .systemOfTextBlockParams(systemBlocks)
            .outputConfig(OutputConfig.builder()
                .effort(OutputConfig.Effort.of(boost ? props.boostEffort() : props.effort()))
                .build());

        // [실측 2026-11-11] Sonnet 5.5는 사고를 끄면(between_tools) 출력 JSON의 `reasoning` 필드 요구를
        // "내부 추론 복제 요청"으로 보고 stop_reason=refusal(category=reasoning_extraction)로 거절한다.
        // 적응형 사고를 켜면 같은 프롬프트가 통과하므로 Sonnet은 adaptive + 낮은 effort로 보낸다.
        // Haiku 5.5는 사고를 꺼도 통과 — TTFT를 위해 disabled.
        if (thinkingOff(model)) {
            b.thinking(ThinkingConfigDisabled.builder().build());
        } else {
            b.thinking(ThinkingConfigAdaptive.builder().build());
        }

        for (Turn t : c.turns()) {
            if ("assistant".equals(t.role())) b.addAssistantMessage(t.content());
            else b.addUserMessage(t.content());
        }
        return b.build();
    }
}
