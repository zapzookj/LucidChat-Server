package com.spring.aichat.service.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.chat.ChatLogDeadletter;
import com.spring.aichat.domain.chat.ChatLogDeadletterRepository;
import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * [Phase6/Tier3 / C-9] ChatLogDocument 저장 wrapper.
 *
 * MongoDB ASSISTANT 저장이 실패하면 단순 로그만 남기고 SSE는 정상 전송했던 흐름을 차단.
 * 결과: history 누락 + 다음 LLM 컨텍스트 손실 + 새로고침 시 응답 영구 손실 + 스탯
 * 변화 원인 추적 불가 → *정합성 파괴*.
 *
 * 정책:
 * 1. exponential backoff(200/400/800ms)로 최대 3회 retry.
 * 2. 모든 retry 실패 시 ChatLogDeadletter에 페이로드 보존 + null 반환.
 * 3. deadletter 저장 자체도 실패하면 ERROR 로그 + null 반환 — 채팅 흐름은 깨지 않는다.
 *
 * 호출처는 null 반환 시 운영 alert을 발행하되 유저에게는 정상 응답을 유지한다 (SSE는
 * 이미 전송됨).
 *
 * Spring Retry 의존성 없이 수동 구현 — build.gradle 변경 회피.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatLogPersister {

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 200L;
    private static final long BACKOFF_MULTIPLIER = 2L;

    private final ChatLogMongoRepository chatLogRepository;
    private final ChatLogDeadletterRepository deadletterRepository;
    private final ObjectMapper objectMapper;

    /**
     * 재시도와 데드레터 fallback을 포함한 안전한 저장.
     *
     * @return 저장된 문서. 모든 재시도 실패 시 null. 호출자는 null 체크로 alert 발행.
     */
    public ChatLogDocument saveWithRetry(ChatLogDocument doc) {
        Exception lastException = null;
        long backoff = INITIAL_BACKOFF_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return chatLogRepository.save(doc);
            } catch (RuntimeException e) {
                lastException = e;
                log.warn("[CHAT-LOG] save failed | attempt={}/{} | roomId={} | err={}",
                    attempt, MAX_ATTEMPTS, doc.getRoomId(), e.getMessage());

                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoff *= BACKOFF_MULTIPLIER;
                }
            }
        }

        // 모든 재시도 소진 — 데드레터로 보존
        log.error("[CHAT-LOG] All {} retries failed, deadlettering | roomId={}",
            MAX_ATTEMPTS, doc.getRoomId(), lastException);

        // [D-6.7] ★ 데드레터는 **방금 실패한 그 MongoDB**에 저장된다. Mongo 장애로 여기 온 경우
        //   이 저장도 같이 실패하므로 안전망 전체가 무의미해진다. 저장소를 분리하는 근본 수정은
        //   스키마 결정이 필요하므로(별도 안건), 최소한 **페이로드가 컨테이너 로그에는 남게** 한다 —
        //   그러면 Vultr 로그에서 수동 복구가 가능하다. 이 경로는 3회 재시도 실패 후에만 도므로
        //   로그 볼륨 리스크는 사실상 없다(개인정보 보존기간 정책과 함께 판단할 것).
        String payloadJson = null;
        try {
            payloadJson = objectMapper.writeValueAsString(doc);
            ChatLogDeadletter deadletter = ChatLogDeadletter.builder()
                .originalRoomId(doc.getRoomId())
                .originalRole(doc.getRole())
                .payloadJson(payloadJson)
                .errorMessage(lastException != null ? lastException.getMessage() : "(unknown)")
                .attemptCount(MAX_ATTEMPTS)
                .failedAt(LocalDateTime.now())
                .build();
            deadletterRepository.save(deadletter);
        } catch (JsonProcessingException jpe) {
            // 직렬화 자체가 실패한 경우라 payloadJson이 아직 null이다 — 원시 필드를 직접 찍는다.
            //   여기서 hoist된 변수만 찍으면 null이라 '로그로 수동 복구 가능'이 절반만 성립한다.
            log.error("[CHAT-LOG] Deadletter serialization failed — payload lost! | roomId={} | role={} | raw={} | clean={} | inner={}",
                doc.getRoomId(), doc.getRole(), doc.getRawContent(), doc.getCleanContent(),
                doc.getInnerThought(), jpe);
        } catch (Exception dle) {
            log.error("[CHAT-LOG] Deadletter save also failed — payload lost! | roomId={} | payload={}",
                doc.getRoomId(), payloadJson, dle);
        }

        return null;
    }
}
