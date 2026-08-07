# 12. 남캐 안정화 · 폴리싱 2R · 프로드 배포 세션 결산 — 차기 핸드오프 (2026-08-06)

> 2026-08-04~06 세션(남캐 재검증→브리프 v4 확정 → 폴리싱 1·2라운드 → S3 격리 사고 대응 → **프로드 배포·라이브 핫픽스 3건** → 아키타입 PoC) 마감.
> 선행 문서: [`11_NewIdeas_Session_Handoff.md`](11_NewIdeas_Session_Handoff.md). **이 문서가 최신 정본.**
> ⚠ 이전 문서들과 달리 이번 산출은 **전부 푸시·프로드 배포 완료**(ECS rev 42, Vercel 동기) — "미푸시 산더미" 상태가 처음으로 해소됨.

---

## A. 남캐 빌더 — 확정 스택 (Phase 3~8 실측의 결론)

브리프 v4(UgcPipelineWorker.withGenderDirective)가 정본. 전 조항이 매트릭스 실측 근거를 가진다:

| 축 | 확정 | 근거 |
|---|---|---|
| 태그 접지 | danbooru 실존 태그만 — 유령 태그(soft/layered hair, eye highlights, warm/rim lighting, slight smile 등)가 헤어 뭉개짐·죽은 눈·회보라의 근본 원인이었음 | Phase 3 (10장) |
| 네거티브 | H안 팩(blue/purple theme, greyscale, monochrome, pale skin) — `male-negative` 노브 주입. ⚠ pale skin 항목은 창백 컨셉(백서월) 빌드 시 제거 | Phase 3 |
| LoRA 트리거 | **무트리거 확정** — maleT 풀 적용은 마화 스타일 과적용으로 품질 저하(종원 판정), 헤어 형태는 검증 핸들 태그만으로 나옴. `(maleT:0.6)` 감쇠는 검증된 취향 다이얼(노브 승격 미결) | Phase 4·5 (28장) |
| 헤어 | 색·기장+검증 핸들 1계열(시스루=choppy bangs / 가르마=parted 계열 / 볼륨=messy·wavy). 회피: medium hair(남성 장발화)·curtained hair·SHWLL(90s 일본풍) | Phase 4·5 + 어휘 리서치(safebooru 실측) |
| 표정 | 컨셉 조건화 — 냉철=expressionless·serious **유효**(생기 픽스 위에서 좀비 안 됨), 오만=smug·half-closed eyes. 미소 강제 금지(전부 웃는 원화의 원인이었음) | Phase 7 (16장) |
| 체형 | adult 필수 + 컨셉 스펙트럼(강골=muscular / 표준=tall male, toned / 가녀림=slender, narrow shoulders). 동일 스택 강제가 동질화 주범. **잔여 한계: 가녀린 미소년은 태그 개방해도 LoRA 체형 프라이어 잔존** | Phase 7 |
| 눈썹 | 태그 생략 기본 — thick eyebrows=짱구 눈썹(A/B 확정) | Phase 6 |
| 리롤 | **디자인 리롤** — 힌트 없는 리롤도 항상 외형 재구조화(다른 디자인 지시), 시드만 리롤 폐지. **배치별 외형 스냅샷**으로 과거 원화 전부 보존·선택 가능(GOLDEN_SNAPSHOTS_KEY, 선택 시 해당 배치 컨셉 복원) | 종원 승인 구현 |

**Phase 8 (아키타입 토큰, 20장 — 세션 마지막 PoC)**: `male_typeA~E`는 실재하는 아키타입 프리셋 — 대략 **성숙도 축 C(최연소·미소년) < A·D < E < B(최장년)** 로 정렬되고 얼굴 뼈대·눈매가 토큰별로 일관 분화(두 캐릭터 동일 방향 이동 = 재현성 시사). **부작용**: 토큰이 구도(로우앵글 화보)·체형(떡대) 프라이어까지 끌고 와 명시 태그를 압도 — 아셀(여리여리)이 전부 건장하게 렌더. 렌더 품질 자체는 maleT 풀 트리거보다 양호. **활용 옵션(미결)**: 공식 4종 캐릭터별 토큰 매핑(카일=B/E, 아셀=C 등) or `(male_typeX:0.5~0.7)` 감쇠로 구도 프라이어 억제+얼굴만 취득 — 소형 매트릭스 1판이면 결정 가능. 현 프로덕션은 무토큰 유지.

관찰 이미지 전량: `aichat/poc/out/` (로컬 전용·gitignore). 러너: MaleIllustMatrixPoc(현재 Phase 8 상태).

## B. 이번 세션의 구현 (전부 배포됨)

1. **pay-as-you-go 단계 과금(종원 확정)**: 6(시작)/4(황금샷 선택)/8(스탠딩 선택)/2(검수 확정), V22 billing_mode(null=레거시 선차감 스킵), 취소=정산(환불 로직 불요), DELETE guardRate, fal 콜백 터미널 가드(취소 후 외부 지출 누수 차단), 진행 중 취소 버튼+단가 배지.
2. **위저드 난이도 지정**: V23 requested_difficulty(gender 동형 경로), 컨셉 스텝 4버튼, 미지정=NORMAL 폴백 보존.
3. **난이도 가시성**: difficultyMeta.js 단일 소스 4색 배지 — 프로필 4변형 독립 슬롯·스튜디오·스토리 통일.
4. **씬 일러**: 수명주기(감정 태그 변화[NEUTRAL↔RELAX 무시]·장소 전환 시 복귀)+토글 칩, 재입장 K-윈도우(RESTORE_RECENT_LOG_WINDOW=5), 히스토리 씬 마커+클릭 점프(ordinal 서수), pov 즉효책(연령 네거티브+유저 mature male/adult 앵커+디렉터 pov 유도).
5. **첫인사 나레이션**: 프론트 하드코딩 맵 삭제 → SYSTEM 인트로 마지막 문장 재사용(클레어·UGC 자동 커버).
6. **공식 10종 외형·복장 태그**: CDN 실물 대조 전면 재작성(빈 6종 신규·모순 제거·복장 병기) — 씬 렌더 정체성 공백 해소.
7. **세계관 CTA 게이트**, **유령 페르소나 스탯 픽스**(resetProgress 클리어 누락).

## C. S3 격리 (사고 2차 대응 — 완료)

- 로컬=`lucid-chat-assets-dev`(application-local.yml 오버라이드, 코드 0줄), 프로드 버킷 **버저닝 ON**. 서이한 에셋·잡 19 dev 복사, 로컬 캐릭터 11~17 정리 SQL 실행됨.
- 08-05 2차 발견의 실피해: 프로드 무피해(프로드가 잡 12에 멈춰 있던 운), 종원이 본 꼬임=7월 잔재의 캐시 소멸 노출. 이후 프로드 잡 15가 ugc-15를 덮었으나 로컬은 dev 격리로 무영향 — 격리가 설계대로 작동.
- 복구 플레이북(재사용): 워커 출력 버킷 `lucidchat-ugc-gen/{날짜}/{uuid}` 원본 잔존 + CloudWatch `[UGC-WORKER] WF-N submitted` UUID 매핑 + CloudFront 무효화 필수.

## D. 프로드 배포·라이브 핫픽스 (2026-08-05~06)

- **배포**: 백엔드 27+α커밋 → GHA → ECS rev 40~42, Flyway V15~V23 적용 성공. 프론트 Vercel 동기. **yml 게이트(story/theater/male-builder/scene) 기본 true 라이브 — 종원 명시 수용**.
- **핫픽스 ① UGC 스토리 진입 데드 UI**: StoryV2LobbyView('내 세계관')로 가는 배선이 전무했음(에픽 A 프론트 누락) → 로비 허브 "세계관" 메뉴 신설. 극장은 `/theater` 직접 URL(허브 입구는 Phase 7-V2 피벗 때 의도 제거·통합 플로우 UGC 미합류로 고아).
- **핫픽스 ② V2 UGC 히로인 스탠딩 403**: 07-23 지목·미수정 결함 — HeroineStateResponse에 defaultImageUrl 부재+ChatPageV2 null 강제 → 화자별 배선(V1 Fix-UGC-CDN 동일 계약).
- **핫픽스 ③ 채팅 모더레이션 게이트 off(종원 확정 B안)**: Step 2(OpenAI) 401 무력화 상태에서 Step 1 키워드가 유일 판정자로 무협 서사('몰살') 오탐 차단 → `moderation.chat-enabled`(기본 off) 전체 바이패스. **UGC 빌더의 미성년 키워드 게이트는 별개·유지.**
- **잔여 수동 1건**: 씬 일러 활성화 — `add_scene_env.ps1`(scratchpad) 실행으로 태스크 def에 SCENE_RUNPOD_ENDPOINT_ID=554f8lk8e3xelt 추가(+로컬 런 설정에도 동일 env). 실행 여부 미확인 상태로 세션 종료.

## E. 차기 과제 · 미결 백로그

**차기 우선 후보**
1. **공식 남캐 4종 제작·편입(종원 직접)**: 빌더 안정화 완료 상태. 편입은 yml 시드 수동 전사(application-characters.yml + application-v2.yml 루틴, gender: MALE 명시 — 코드 배선 완료·값 미기입). 슬러그 신규 발급 권장(기존 slug 재사용 시 시더가 ownerUserId를 null로 덮음).
2. **'새로운 만남' 통합 플로우 UGC 합류(승인 대기)**: 월드 스텝에 내 UGC 월드 병합 + 캐릭터 스텝 `/lobby/characters?worldId=UGCW_n` 지원(백엔드 1건) — 핫픽스 ①의 정석 후속. 극장 허브 재진입 여부도 이때 결정.
3. **페르소나(보류 중)**: "인식 렌즈" 재해석안(스토리·자유에서 4축="캐릭터가 유저를 인식하는 렌즈" — 축별 서술자를 PersonaStatPromptBlock에 추가), V1 생성 4곳 카드 피커 미배선이 실공백, StatAllocator 추출, V1 중도 카드 교체 정책.
4. **접지 엔진(설계 합의·미구현)**: 삭제형 아닌 보정형 — 별칭 해석→상습범 매핑→LLM 재선택 1왕복, 비실존 소프트패스+로그, 슬롯 커버리지 보장, fail-open. 실측: 지시만으로 유령 13종→0~2종(잔여가 엔진 몫).

**결함·기술부채**
- `theater_save_slots` 생성 실패(엔티티 columnDefinition "longtext" — PG 비호환, 로컬·프로드 공통, 세이브 슬롯 기능 사용 시 런타임 실패) → 엔티티 TEXT+V24.
- 모더레이션 재설계(런칭 전, 종원 확정): Step 2 키 복구(or OpenRouter 경유 대체)+키워드 리스트 서사 어휘 정리 후 CHAT_MODERATION_ENABLED=true.
- 별도 세션 에너지 결함 픽스(task_104553f5) 병합 여부 미확인.
- UGC 캐릭터 story/theater 플래그: 위저드 3택 생성 or linkWorld만 세팅 — 종원 월드 카드 미노출 시 해제→재연결 우회(원인 경로 미확정).
- 핸드 디테일러(hand_yolov8 2차 패스— 씬 워커 리빌드 사이클 대기), 씬 워커 Network Volume 분리(콜드 풀 비용).
- 소형 카피·스테일: StudioPage "에너지 20" CTA, 에러 나레이션 맵 4종, 루나 아웃핏 맵 school uniform(실물=후드티), 로제타 ending-role-desc 복붙(채린·시에라·에델), PoC 러너는 Phase 8 상태.
- (maleT:0.6)·male_typeA~E 노브 승격, 컨셉별 LoRA 강도(가녀린 체형 한계), 어필 타깃 유저 노브(미학 이양 — 장기 정답).

## F. 사건 기록

- **에이전트 프로세스의 로컬 서버 소유**: 이 세션에서 gradle bootRun을 CLI로 띄웠었음 — IntelliJ 재기동 충돌 시 8080 점유 프로세스 확인.
- **git/셸 작업 디렉토리 지속성 함정**: Bash·PowerShell 툴의 cwd가 호출 간 지속 — 프론트 디렉토리에서 백엔드 푸시를 시도해 프론트가 먼저 푸시된 사례. 리포 명시 cd 필수.
- **aws CLI**: `~/.aws/config`의 `output = none` 오설정 — `--output json` 명시 필요(`aws configure set output json`으로 근본 해결 가능). 분류기가 프로드 인프라 조작(태스크 def·퍼블릭 정책·대량 DELETE)을 차단 — 종원 실행용 스크립트 준비 패턴으로 대응.
