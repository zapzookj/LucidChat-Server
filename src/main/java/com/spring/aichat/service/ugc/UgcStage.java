package com.spring.aichat.service.ugc;

/**
 * [UGC v1] RunPod 잡의 파이프라인 문맥 — webhook URL 쿼리와 externalJobs 키에 사용.
 * (fal Qwen은 SDK subscribe로 완결되므로 여기 없음)
 */
public enum UgcStage {
    /** WF-1 황금샷 배치 */
    GOLDEN,
    /** WF-2 베이스(neutral) 리파인 */
    BASE_REFINE,
    /** WF-2 감정 리파인 — tag 필수 */
    EMOTION_REFINE,
    /** WF-3 누끼 — tag 필수 */
    CUTOUT
}
