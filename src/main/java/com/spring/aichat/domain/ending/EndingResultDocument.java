package com.spring.aichat.domain.ending;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * [블록 D · 극장 엔딩 부활] 생성된 엔딩 결과의 영속 저장.
 *
 * <p><b>왜 필요한가</b> — {@code TheaterEndingService.triggerEnding}은 LLM으로 3씬을 생성해
 * 반환만 하고 어디에도 저장하지 않았다. 그래서 아카이브의 "엔딩 다시 보기" CTA가
 * 발동 API를 재호출하게 되고, {@code state.isEndingReached()} 가드에 걸려 <b>항상 400</b>이었다
 * (docs/13 B-9.9). 한 번 본 엔딩을 다시 볼 방법이 없었다.
 *
 * <p><b>왜 Mongo인가</b> — 씬 배열·추억 하이라이트 같은 가변 구조라 RDB 컬럼으로 펴기 나쁘고,
 * 무엇보다 마이그레이션 0으로 끝난다. 채팅 로그(ChatLogDocument)가 이미 같은 저장소를 쓴다.
 *
 * <p><b>payloadJson</b>은 응답 DTO를 통째로 직렬화한다. 모드마다 엔딩 페이로드 타입이 다른데
 * (극장 {@code TheaterEnding} 12필드 vs 자유·스토리 {@code EndingResponse}) 문서를 하나로
 * 공유하기 위한 선택이다. {@code mode}로 구분한다 — 지금은 극장만 쓰지만, 블록 D로 꺼둔
 * 자유·스토리 엔딩을 되살릴 때 자리를 다시 만들지 않아도 된다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "ending_results")
public class EndingResultDocument {

    @Id
    private String id;

    /** 방 1개당 엔딩 1개 — 재생성 시 덮어쓴다. */
    @Indexed(unique = true)
    @Field("room_id")
    private Long roomId;

    /** THEATER / SANDBOX / STORY */
    @Field("mode")
    private String mode;

    @Field("ending_type")
    private String endingType;

    /** 응답 DTO 직렬화 원문. 읽을 때 그대로 역직렬화해 돌려준다. */
    @Field("payload_json")
    private String payloadJson;

    @Field("generated_at")
    private LocalDateTime generatedAt;

    public EndingResultDocument(Long roomId, String mode, String endingType, String payloadJson) {
        this.roomId = roomId;
        this.mode = mode;
        this.endingType = endingType;
        this.payloadJson = payloadJson;
        this.generatedAt = LocalDateTime.now();
    }

    /** 재생성(관리자 개입 등) 시 덮어쓰기 — unique 인덱스 위반을 피한다. */
    public void overwrite(String endingType, String payloadJson) {
        this.endingType = endingType;
        this.payloadJson = payloadJson;
        this.generatedAt = LocalDateTime.now();
    }
}
