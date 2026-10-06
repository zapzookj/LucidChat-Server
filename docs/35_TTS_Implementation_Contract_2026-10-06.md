# ElevenLabs TTS 구현·검증 기록

관측일·기록일 2026-10-06. [스튜디오 운영 반영](34_Studio_Dynamic_Expressions_Release_2026-10-06.md) 이후 착수했다. 사용자 결정 정본은 [docs/19 §L](19_assets/decisions_confirmed.md#l-2026-10-06-tts-화면보이스-매핑초기-요금)이며 [사전 분석](29_Studio_TTS_Preimplementation_Review.md)의 미확정 제안을 대체한다.

## 확정한 제품 동작

- 승인 배치: Boost 옆 자동 보이스 토글, 화자 옆 듣기·멈춤·다시 듣기. [승인 목업](35_assets/tts-mockup/index.html), [실제 컴포넌트 검증 화면](http://127.0.0.1:18772/__design/tts).
- 초기 요금 **응답 전체 추가 1E**. 지원되는 여러 캐릭터 대사가 있어도 최초 해금 1회, 재생 무료. 실패 시 음성 비용만 원래 free/paid 분할로 한 번 환불한다. 환불 후 명시적 새 생성은 다시 1E. 텍스트 비용은 환불하지 않는다.
- 고정 firstGreeting은 사전 제작 음성을 무료 재사용한다. 스토리 V2 동적 오프닝은 일반 응답 해금 규칙이다.
- 자동 ON이면 채팅 LLM이 별도 `tts_input`을 출력한다. 수동 요청 및 부적합/누락 자동 입력은 저장 발화만 별도 LLM으로 변환한다. 내레이션·속마음·cleanContent 전체를 합성하지 않는다.
- 초기 `TTS_MODEL=eleven_v4`, `eleven_v4_turbo`로 교체 가능. 가격은 별도 `TTS_ENERGY_COST=1`. Turbo 비교는 사용자가 구현 후 수행한다. rosetta·seolah의 같은 voice ID는 의도한 공유다.
- 자유·스토리의 공식 보이스 화자를 지원한다. UGC/NPC/시스템 내레이션에 공식 목소리를 임의 배정하지 않는다. 현재 보이는 씬만 재생하며 자동으로 다음 씬으로 이동하지 않는다. 이력에는 새 자동 생성/자동 재생을 적용하지 않는다.

## 서버·데이터 구현

`TtsController`, `service/tts/*`, `TtsProperties`, `TtsResponseAudio`를 추가했다. V39는 방의 자동 모드/승인 가격 2개 컬럼과 해금 테이블을 만든다. unique(user,room,log), User→job 잠금, 새 isolated TX로 차감·attempt 생성·환불을 직렬화한다. 여러 워커의 QUEUED→GENERATING claim도 잠금 아래 수행한다.

서버가 저장 ASSISTANT 로그의 소유권·방·숨김·씬을 검사한다. FE의 임의 대사·voice ID·객체 키를 받지 않는다. 해금 시 대사/voice/모델 snapshot을 고정한다. 태그는 허용 목록의 최대 2개, 태그 제외 본문은 dialogue와 같아야 한다. 발화 자체 ASCII 대괄호는 전각으로 치환해 명령과 구별한다. 잘못된 보조 필드는 채팅 JSON 전체 파싱을 실패시키지 않는다.

자동 요청은 ASSISTANT 저장 성공 후 시작하고, OFF/승인 가격은 실제 차감 직전 User 잠금 아래 재검사한다. HTTP 재요청·FAILED 조회로 재차감하지 않는다. 실패 재시도는 현재 failed attempt+새 요청 키가 필요하다. 대상 씬 전체 READY 전 부분 음성은 공개하지 않는다. 긴 발화는 자르지 않고 1,800 UTF-16 단위 조각으로 합성하며 surrogate/태그를 쪼개지 않는다. 씬 내 MP3 조각을 연결한다.

개인 음성은 공개 이미지와 별도 `lucid-chat-tts-private`에 저장하고 인증 API로 바이트를 반환한다. `audio/mpeg`, `no-store`, `nosniff`를 설정한다. 공개 API는 서버가 현재 공식 firstGreeting 키를 결정하는 `/tts/greetings/{slug}`뿐이다. 공급자/R2 비밀은 FE에 전달하지 않는다.

삭제/초기화는 접근 폐기·pending 1회 환불을 연결한다. 초기화 전에 ID/attempt 대상을 캡처하고 commit 후 취소해 Room/User 교착과 새 응답 오취소를 피한다. 취소 snapshot은 저장소 삭제 성공 여부와 무관하게 제거한다. PUT 전 키 원장을 기록하며 늦은 업로드·불명확한 접수 결과를 폐기한다. opaque cleanup tombstone을 유지하고 일일 재대조한다. 10분 지난 GENERATING은 실패/환불하며 공급자 합성을 자동 재전송하지 않는다.

## 프런트 구현

V1/V2 final_result·현재 첫 씬·final-only 오프닝·큐·복원 이력·리플레이에 parentLogId/sceneIndex를 보존한다. 안정 ID를 받은 뒤 음성 버튼을 연다. `useTtsPlayback`은 단일 재생·epoch/씬/삭제 guard·상태 재조회·autoplay 차단 시 수동 재생 전환을 처리한다. 씬/방 전환·삭제·화면 숨김·로그아웃/언마운트 때 중단하고 Blob URL을 폐기한다.

보이스/BGM 볼륨을 별도로 저장하고 재생 중 BGM을 기존의 25%로 낮춘다. 안내 dialog에 focus trap·Escape·focus 복귀를 연결했다. 서버의 현재 가격을 보내며 가격 변경으로 이전 승인이 무효하면 자동 차감을 중단한다.

## 외부 계약·실제 합성

[모델 문서](https://elevenlabs.io/docs/overview/models)와 [TTD WebSocket 계약](https://elevenlabs.io/docs/eleven-api/guides/how-to/websockets/realtime-tdd)의 v4/Turbo를 따른다. `/v1/text-to-dialogue/stream-input`에 모델을 명시하고 voice 등록→inputs→close_socket, base64 audio/is_final을 받는다. Turbo의 연결당 voice 1개 제한에 맞춰 두 모델 모두 씬당 한 화자/연결이다. HTTP TTD 기본 v3 예제를 v4 계약으로 대신하지 않는다.

사용자 키는 models_read가 없어 목록 조회 401이지만 **공식 10종 v4 합성 모두 성공**했다. 합성에 불필요한 키 권한을 확대하지 않았다. 실제 MP3/호출 전 영수증은 ignored `tools/tts/.local/greetings-v4`에 있다. 캐릭터별 첫 후보이며 사람의 최상 샘플 선정은 미실행. [10종 청취](http://127.0.0.1:18773/index.html).

[가격 페이지](https://elevenlabs.io/pricing/api)의 10/6 관측은 v4 $0.022/1,000자(정상 $0.08), Turbo $0.011(정상 $0.04), 10/12까지 할인 표기였다. 실제 계정 청구/요금제와 구별한다. 1E는 사용자 초기 선택이며 운영 전체의 수익성 검증값은 아니다. Enterprise 전용 zero-retention을 현재 계정 기능으로 가정하지 않는다.

## 저장소·검증

기존 이미지 키는 새 비공개 버킷 접근/생성 모두 403이었다. 확인 전에 복사한 키를 파일에 남겨 사용자에게 혼동을 줬다. 두 값을 비운 뒤 사용자가 별도 private 키를 입력했고, 버킷 접근·10개 업로드·인증 GET 원본 바이트 일치가 성공했다. 익명 S3 URL은 10개 모두 HTTP 400으로 접근되지 않았다. 이는 S3 직접 API 검사이며 Cloudflare의 모든 공개 도메인 설정을 독립 확인한 것은 아니다. r2.dev·커스텀 도메인 비활성은 사용자에게 설정 요청한 조건이다. [R2 기본 비공개 정책](https://developers.cloudflare.com/r2/buckets/create-buckets/).

운영 공식 10종 firstGreeting+로컬 voice 매핑으로 재계산한 키와 준비 음성 **10/10 일치**. DB 수정·합성 재요청 없이 읽기 전용으로 대조했다. [대조 JSON](35_assets/production-greeting-verification.json). 샘플/키/원본 provider 응답은 Git 제외. [도구 README](../tools/tts/README.md).

- `gradlew.bat --no-daemon test --tests '*Test' bootJar` 성공: **44클래스/309검사**, 실패·오류·스킵0. 별도 기존 디오라마 미커밋10건을 제외한 릴리스 대상299건. 새 TTS23건(동시성/과금12·계약5·화자/원본5·V39 SQL1).
- 실제 JPA 동시 잠금, 중복 최초 해금/환불/재시도, paid 환불·stale attempt, OFF 중 대기한 자동 요청, 첫 조각 이후 취소, 늦은 PUT, 삭제 본문/cleanup 원장, commit 대상·cached JPA context, 이전 견적 거부, 다른 소유자/준비 전 오디오 차단을 검사했다. H2 PostgreSQL 모드에서 실제 V39 실행/재실행: 기존 방2 유지·OFF/null 기본값·응답 유일성·상태/분할 금액 제약·유효2행 분해합을 대조했다.
- FE build·TDZ139파일·진입69검사·씬 식별12 통과. production JS에 DEV 검증 라우트/모의 API 없음.
- 실제 컴포넌트+v4 MP3: 수동·무료 인사·자동 새 응답 재생, 멈춤/무료 재생(모의 지갑), 상태 조회 실패/무료 복구, 다운로드 중 삭제 후 늦은 재생 차단, Shift+Tab/focus, 390px 넘침 없음/44px 버튼 확인. [데스크톱](35_assets/tts-ui/desktop.png), [모바일 재생](35_assets/tts-ui/mobile-playing.png), [실패/환불](35_assets/tts-ui/mobile-failed.png). 실제 계정·지갑·채팅 LLM 통합 E2E와 구별한다.
- 독립 컨텍스트 소스 검토의 과금 잠금·늦은 PUT·초기화/삭제·FE 식별 지적을 수정했다. 최종 cancel 경계 `1bf40aef…` 재검토에서 추가 문제 없음. 검토자는 키 조회·유료 호출·테스트를 하지 않았다.

미실행: Turbo 실제 합성/품질, 계정 청구/요금제, 사람의 샘플 선정, 운영 로그인/유료 자동·수동 E2E, 실제 autoplay 거부 환경, PostgreSQL 다중 인스턴스 동시성. 추가 전체 `test`의 `AichatApplicationTests.contextLoads`는 로컬 JWT placeholder Base64 해석 실패로 통과하지 못했다. 외부 DB/env가 필요한 이 검사는 기존 CI의 `*Test` 범위에서 제외된다.

## 운영 상태·적용 순서

제품 구현·검증과 private R2 첫인사 준비를 마쳤다. **TTS 푸시/배포·V39 운영 적용·TTS env 활성화는 아직 실행하지 않았다.** 완료된 스튜디오 릴리스와 구별한다.

운영 시 ElevenLabs 매핑·별도 R2 키를 서버 비밀 env로 설정하고 `TTS_ENABLED=true`, `TTS_MODEL=eleven_v4`, `TTS_ENERGY_COST=1`을 적용한다. V39/서버/FE 반영 후 인증·무료 인사·삭제·실제 생성 정산을 확인한다. `TTS_ENABLED=false`로 신규 생성/자동 차감을 중단할 수 있다. 모델 변경은 새 생성에만 적용하고 이미 준비한 음성은 재합성하지 않는다.
