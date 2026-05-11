package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.ChatRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * [Phase6/Tier3 / C-9] ChatLog 저장 실패 데드레터.
 *
 * MongoDB ASSISTANT 저장이 모든 retry를 소진하고도 실패한 경우 보존되는 페이로드.
 * 정합성 파괴(history 누락 + 다음 LLM 컨텍스트 손실 + 새로고침 시 응답 영구 손실 +
 * 스탯 변화 원인 추적 불가)를 차단하기 위한 *복구 가능 잔존 데이터*.
 *
 * 운영자는 chat_log_deadletter 컬렉션을 주기 점검 + 수동 복구 도구로 chat_logs에
 * 재밀어 넣어 history를 봉합한다.
 *
 * [컬렉션 분리 이유]
 * - chat_logs는 인덱스가 시계열 쿼리에 최적화되어 있다. 실패 페이로드를 같은 컬렉션에
 *   섞으면 인덱스 손상 + 정상 쿼리 결과 오염.
 * - 별도 컬렉션은 자체 retention 정책 부여 가능 (예: 90일 후 삭제).
 */
@Document(collection = "chat_log_deadletter")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatLogDeadletter {

    @Id
    private String id;

    @Field("originalRoomId")
    @Indexed
    private Long originalRoomId;

    @Field("originalRole")
    private ChatRole originalRole;

    /** 직렬화된 ChatLogDocument(JSON). 복구 시 그대로 chat_logs에 재삽입 가능. */
    @Field("payloadJson")
    private String payloadJson;

    /** 마지막 실패의 예외 메시지 (디버그용) */
    @Field("errorMessage")
    private String errorMessage;

    /** 시도 횟수 — retry 정책으로 결정된 최대 시도 수 */
    @Field("attemptCount")
    private int attemptCount;

    @Field("failedAt")
    @Indexed
    private LocalDateTime failedAt;
}
