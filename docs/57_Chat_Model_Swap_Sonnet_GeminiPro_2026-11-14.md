# 채팅 모델 전환 — 일반 Sonnet 5.5 / 부스트 Gemini 3.1 Pro

기록: 2026-11-14 KST. 종원 지시("일반을 Sonnet 5.5로, Boost를 Gemini Pro 3.0(1)로 바꿔 배포").

## 바뀐 것

| 모드 | 이전 | 이후 |
|---|---|---|
| 일반 | Claude 직결 Haiku 5.5 → 폴백 OpenRouter Gemini 3 Flash | Claude 직결 **Sonnet 5.5**(적응형 사고·effort low) → 폴백 동일 |
| 부스트 | Claude 직결 Sonnet 5.5 → 폴백 OpenRouter `pro-model`(운영값 Gemini 3.8 Flash) | Claude를 건너뛰고 OpenRouter **`google/gemini-3.1-pro-preview`** 직행 |

- 코드(BE `e460399`): `claude-console.boost-model` 공란 = 부스트 턴은 Claude 시도 없이 OpenRouter로 간다(`ClaudeChatStreamClient.tryStream`). 일반 모델·effort 기본값을 Sonnet/low로.
- 운영 env: `/opt/lucid/.env`의 `OPENAI_PRO-MODEL`을 `google/gemini-3.8-flash` → `google/gemini-3.1-pro-preview`로 한 줄 교체(docs/53에서 넣은 오버라이드). 백업 `/opt/lucid/.env.bak-20261114`.
- OpenRouter 모델 목록에 Gemini 3.0 Pro 텍스트 모델은 없다(3.1 Pro preview만) — 3.1로 확정.
- 시크릿 모드는 종전대로 Claude를 타지 않는다(일반 시크릿 = Gemini 3 Flash).

## 검증

- 유닛 + 실 API 라이브 테스트 통과. 별도 에이전트 리뷰에서 정합성 버그 없음.
- 운영: 컨테이너 교체 후 `/health` 200, 재시작 0, 컨테이너 env `OPENAI_PRO-MODEL=google/gemini-3.1-pro-preview`, 기동 로그 `[CLAUDE] 직결 경로 활성 | model=claude-sonnet-5-5 | boostModel=(없음 — 부스트는 OpenRouter 직행)`.
- **미측정**: 운영 실채팅 턴(일반·부스트 각각)의 응답 품질·지연, Gemini 3.1 Pro의 OpenRouter 실응답.

## 알려진 위험 (배포 후 관찰 대상)

1. 일반 모드 지연 증가 — 더미 프롬프트 1회 실측 Sonnet 첫 토큰 3.2s·첫 씬 5.0s (Haiku 0.55s·1.5s).
2. 부스트는 이제 전량 OpenRouter 서킷 브레이커의 첫 토큰 2초 데드라인(`LlmCircuitBreaker.TTFT_DEADLINE_CLOSED_MS`)을 지난다. Gemini 3.1 Pro(사고 모델)가 2초를 넘기면 턴마다 2초 낭비 후 Vertex 재시도, 3연속이면 서킷 5분 OPEN. 로그 `TTFT 워치독 발동 … gemini-3.1-pro-preview`가 잦으면 부스트만 데드라인을 풀 것.
3. `pro-model`은 극장(분기 직후·마지막 챕터·CLIMAX·엔딩)과 공용 — 그 구간도 3.8 Flash → 3.1 Pro로 비용 상승.
4. 일반 모드 전량이 Sonnet이라 Claude 무료 크레딧 소진이 빨라진다. 소진 시 일반은 조용히 Gemini 3 Flash로 폴백.

## 되돌리기

- 부스트만: `.env`의 `OPENAI_PRO-MODEL` 값을 되돌리고 app 재생성.
- 전체: `e460399` revert 푸시 + `.env` 한 줄 복원.
