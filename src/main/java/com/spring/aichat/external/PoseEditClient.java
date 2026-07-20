package com.spring.aichat.external;

import java.util.concurrent.CompletableFuture;

/**
 * [UGC v1] 이미지 편집(자세 변환·감정 파생) 클라이언트 전략 인터페이스.
 *
 * <p>현행 구현은 fal.ai Qwen({@link FalQwenEditClient})이며, GPT Image 2 등으로의
 * 공급자 교체에 대비해 오케스트레이터는 이 인터페이스에만 의존한다.
 *
 * <p>계약: 입력 이미지 1장 + 편집 지시 프롬프트 → 편집된 이미지 1장의 URL.
 * 출력 해상도는 입력 해상도를 유지한다(공급자별 보장 방식은 구현체 책임).
 */
public interface PoseEditClient {

    /**
     * 이미지 편집 실행. 완료까지 내부 처리(큐·폴링) 후 결과를 완성하는 future 반환.
     *
     * @param req prompt/negative/입력 이미지 URL/seed(null이면 공급자 랜덤)
     */
    CompletableFuture<EditResult> edit(EditRequest req);

    /**
     * @param imageUrl 공개 접근 가능한 URL(presigned 포함 — 큐 대기를 커버할 만료 시간 필요)
     * @param seed     null이면 공급자 랜덤. 감정 파생 시 베이스 seed 고정, 리롤 시에만 변경 권장.
     */
    record EditRequest(String prompt, String negativePrompt, String imageUrl, Long seed) {}

    /**
     * @param seed 공급자가 실제 사용한 seed (재현·파생 고정용)
     */
    record EditResult(String requestId, String imageUrl, Long seed) {}
}
