# ElevenLabs TTS 준비 도구

관측·기록: 2026-10-06. 제품 계약은 [docs/35](../../docs/35_TTS_Implementation_Contract_2026-10-06.md), 결정 정본은 [§L](../../docs/19_assets/decisions_confirmed.md#l-2026-10-06-tts-화면보이스-매핑초기-요금).

## 비밀 설정

`.local/` 전체를 Git에서 제외한다. 키나 voice ID를 로그·문서·명령행에 넣지 않는다.

- `.local/elevenlabs.json`: `apiKey`, `voiceIds`(airi, yeonhwa, taeri, luna, claire, rosetta, chaerin, sierra, edel, seolah). rosetta·seolah의 의도한 보이스 공유를 허용한다.
- `.local/storage.json`: `accessKey`, `secretKey`, `endpoint`, `region`, `publicBucket`, `privateBucket`. 비공개 버킷은 `lucid-chat-tts-private`, 공개 이미지 버킷과 분리한다. r2.dev·커스텀 도메인을 활성화하지 않는다. 기존 공개 이미지 키는 새 버킷 접근/생성이 403으로 거부됐다. 별도 private 키를 사용한다.
- `capture_storage_config.py`는 운영 endpoint 등 메타데이터만 준비하며 키 두 값은 빈 값이다. 기존 파일은 덮어쓰지 않는다.

## 실행

Java 17/Python 3 표준 라이브러리를 사용한다. Windows classpath 제한 때문에 Java `@argfile`을 쓴다.

```powershell
.\gradlew.bat --no-daemon prepareBakeoff
python tools/tts/preflight.py
python tools/tts/run_prepare.py all
python tools/tts/run_storage.py
python tools/tts/verify_greetings.py
python tools/tts/review_greetings.py
```

`preflight.py --account`는 추가 모델/계정 조회 권한이 필요하다. 현재 키는 models_read가 없어 조회 401이지만 실제 v4 합성은 성공했다. 합성에 불필요한 키 권한을 확대하지 않는다.

**run_prepare는 유료 합성이다.** 공식 시드의 firstGreeting을 `eleven_v4`로 생성한다. 호출 전 REQUESTING 영수증을 저장하며 기존 영수증은 DONE/FAILED_OR_UNKNOWN 모두 자동 재호출하지 않는다. 기존 결과 삭제로 과금 재시도를 우회하지 않는다. 이번 10개는 DONE이며 각 캐릭터의 첫 후보 샘플이다. 최상 샘플의 사람 평가·선정은 별도다.

run_storage는 이미 생성된 파일만 저장한다. 인증 GET 바이트 일치, 서명 없는 S3 URL의 접근 거부, 10개 전체 개수를 대조한다. 서명 없는 API 거부만으로 Cloudflare의 모든 공개 도메인 설정을 확인했다고 판단하지 않는다. 제어판의 r2.dev·커스텀 도메인 비활성 설정도 유지한다.

2026-10-06 사용자 청취 후 7개 교체본과 3개 유지본을 `.local/greetings-selected`에 선정했다. 원본 `greetings-v4`의 유료 합성 영수증과 MP3는 보존한다. 선택본의 별도 영수증은 출처·원본/선택 SHA-256·현재 firstGreeting+voice 매핑 키를 기록하며, 사용자 제공 파일의 실제 합성 모델/요청 지연을 추정하지 않는다. [선정 원장](../../docs/35_assets/greeting-selection.json).

`run_storage`·`verify_greetings`·`review_greetings`는 선택 디렉터리가 있으면 우선 사용한다. `run_storage.py --check-only`는 네트워크 없이 10종 집합·중복·파일 크기·영수증/키 형식·선택 음성 SHA를 전량 검증한다. SHA가 없는 예외는 기존 `.local/greetings-v4` 경로의 source 없는 레거시 영수증만 허용한다. 선정본과 모든 다른 경로는64hex SHA 필수다. 업로드도 이 검증을 첫 네트워크 작업 전에 수행한다. `verify_greetings`도 로컬 SHA/전체 집합을 네트워크 전에 검사하고, 실제 운영 firstGreeting과 현재 로컬 매핑으로 object key를 재계산한다. 테스트용 `--samples`는 다른 샘플 디렉터리를 지정한다. 이는 배치 배정/파일 무결성 확인이며 MP3 내용의 발화나 보이스를 역으로 인증하는 검사는 아니다.

## 제품 설정

`TTS_ENABLED` 기본 false. 활성화 시 `ELEVENLABS_API_KEY`/`TTS_VOICE_*`, `TTS_AUDIO_BUCKET`, `TTS_S3_ACCESS_KEY`/`TTS_S3_SECRET_KEY`/`TTS_S3_ENDPOINT`를 서버 비밀 환경변수로 설정한다. 개발용으로는 `TTS_SECRET_FILE`, `TTS_STORAGE_SECRET_FILE`을 읽을 수 있다. FE에 키를 전달하지 않는다.

`TTS_MODEL=eleven_v4` 초기 기본, `eleven_v4_turbo`로 교체 가능. `TTS_ENERGY_COST=1` 초기 요금, `TTS_FORMATTER_MODEL=google/gemini-3-flash-preview` 수동 입력 변환 모델. 가격 변경 시 기존 자동 모드의 승인 가격과 다르면 새 자동 차감을 중단하고 재승인을 받는다. 기존 준비된 응답은 모델을 바꿔도 재생 무료다.

프런트 개발 화면 `http://127.0.0.1:18772/__design/tts`는 실제 컴포넌트와 모의 API/지갑을 사용한다. `public/__tts-sample.mp3`에 별도 Git 제외 로컬 음성을 두면 실제 재생을 검증할 수 있다. DEV 전용 라우트이며 실제 사용자 방·에너지·공급자 호출에는 연결되지 않는다.
