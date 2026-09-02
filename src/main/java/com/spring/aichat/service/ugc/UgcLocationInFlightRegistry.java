package com.spring.aichat.service.ugc;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [D-3.5 · docs/17_assets/defect_register.md §D-3.5] 사후 장소 배경 생성의 프로세스 내 in-flight 레지스트리.
 *
 * <p>{@code retryLocation}은 '멈춘 GENERATING 복구'가 의도인데 경과 시간·진행 여부 조건이 없어 방금 시작한
 * 생성에도 그대로 통과했다 — 레이트리밋(5초 2회) 안에서 무과금 LLM 프롬프트화 + fal 배경 생성이 완주 전까지
 * 12~120회 중복됐다(유저 에너지는 줄지 않아 억지력 0). {@code ugc_world_locations}에는 타임스탬프 컬럼이 없어
 * 컷오프를 걸 수 없고, 컬럼을 추가해도 "지금 이 프로세스가 실제로 만들고 있는가"는 알 수 없다.
 *
 * <p>이 레지스트리는 정확히 그 질문에 답한다: 등록돼 있으면 이 JVM이 지금 만들고 있는 것이므로 재시도를
 * 거부하고, 없으면(서버 재시작으로 future가 유실됐거나 5분 타임아웃 후 정리됐거나) '멈춘 GENERATING'이므로
 * 재시도를 허용한다 — 원 의도를 컬럼 없이 정확히 보존한다.
 *
 * <p>⚠ 단일 인스턴스 전제(현행 Vultr compose app 1대). 다중 인스턴스로 가면 다른 인스턴스의 진행분을 볼 수
 * 없어 중복이 인스턴스 수만큼 열린다 — 그때는 {@code generating_since} 컬럼 + 컷오프로 교체할 것.
 */
@Component
public class UgcLocationInFlightRegistry {

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 원자적 선점 — 이미 진행 중이면 false. 호출측(서비스)이 워커 디스패치 <b>전</b>에 호출한다(TOCTOU 창 폐쇄). */
    public boolean tryAcquire(Long locationId) {
        return locationId != null && inFlight.add(locationId);
    }

    /** 워커의 finally에서 호출 — 완료·실패·타임아웃·디스패치 실패 어느 경우든 반드시 해제. */
    public void release(Long locationId) {
        if (locationId != null) inFlight.remove(locationId);
    }

    public boolean isInFlight(Long locationId) {
        return locationId != null && inFlight.contains(locationId);
    }
}
