# 10. V2 이식 세션 결산 · 차기 세션 핸드오프 (2026-07-31)

> 2026-07-30~31 세션(축0 체리픽 → 축1 UGC V2 → 축2 Diorama 이식 + 사고 수습) 마감 문서.
> 차기 세션 과제: **① 패치 결과 테스트 ② UGC V2 잔여 에픽(World PK 마이그레이션→STORY 개방) ③ 신규 아이디어 검토·구현(종원 설명 대기)**.
> 계획 정본은 [`07_UGC_V2_Plan.md`](07_UGC_V2_Plan.md)·[`09_Diorama_Insights.md`](09_Diorama_Insights.md) — 이 문서는 그 실행 결과와 잔여분.

---

## A. 세션 산출 — 전부 로컬 커밋 · **미푸시** (⚠ master 푸시 = GHA 프로드 배포 트리거)

**백엔드(aichat) 13커밋** `48e7d07..63bc1b2`:

| 영역 | 내용 |
|---|---|
| 축0 체리픽 | OpenRouterClient completeJson length 재시도 (48e7d07). CharacterDisplay 모바일 픽스는 **이미 master에 있었음**(3874a47 — docs/09 §C 스테일) |
| 폴리싱 | UGC mood 태그 한글 분리 신설(Stage0 `mood_tags` — persona_tags는 영문 이미지용 유지, 구버전 잡 폴백) |
| A-4/B-3 | **V15**: Character.appearance_tags/persona_tags/base_pose(TEXT) + 바인딩 저장 + IllustrationPromptAssembler 7-arg DB 우선 + 공식 4종 YAML 이관 + **applySeed appearance/clothing/age 미복사 버그 동반 픽스**(재배포 시 공식 프로필 응답 일제 갱신 — YAML=정본) |
| P0 철회 | Character.unpublish + 어드민 강제(`POST /admin/characters/ugc/{id}/unpublish`) + 소유자 자진(`POST /ugc/characters/{id}/unpublish`) + **전 SSE 경로 접근 재검증**(메시지+이벤트/지켜보기/시간넘기기/자동응답 — blockIfUgcInaccessible) |
| P1 | 프로필 빈값=삭제 의미론(patch(): null=유지·빈문자열=삭제, 이름·첫인사 제외) |
| A-1/B-2 | **씬 렌더 백본**(`illustration.scene.*` 기본 off): Scene{PromptAssembler·WorkflowFactory·RenderService·ComfyClient·AssetService} + **V16** scene_illustrations + wf_scene_render.json + sceneRenderExecutor 전용 풀 + sfw 수위 게이트(비시크릿 강제) + 인플라이트 디덥 + AiJsonOutput additive 스키마(프롬프트 지시는 플래그 시만) + 폴링/목록 API(`GET /illustrations/scenes{,/{id}}?roomId=`) |
| P0 VLM | `ugc.vlm-prefilter.*`(기본 off) — 공개 신청 시 자문 스캔 → moderation_events(자동 차단 없음) |
| P2 배경 | location_change→정적 배경 브리지 + 결정론 캐시 키(`{WORLD}__{KEY}`) + 방 진입 시딩 |
| P2 루틴 | UgcRoutineGenerationService(바인딩·linkWorld afterCommit 트리거, locationKey 실존 검증, 폴백) |
| 모더레이션 | minor_signal 기준 정밀화(7bfa25a: '미성년 성적 맥락'이 기준) + **2차 확정 판정**(63bc1b2: 플래그 시 엄격 재검증, 확정만 차단, CONFIRMED/OVERTURNED 이벤트 적재 — 오탐률 측정 가능) |
| 리뷰픽스 | 멀티에이전트 적대적 리뷰(37에이전트) 확정 8건 일괄 수정(ae854b0 — @Async 자기호출 크리티컬 포함) |

**프론트(LucidChat-Front) 2커밋** `481fd4e·b04bf76`: useSceneIllustrations 훅 + SceneIllustrationStage(상주 표시 A-3 + 셰브론 네비 A-2, ChatPage 접점 15줄, 씬 없으면 회귀 제로) + 폴링 백필 픽스.

검증 상태: 백엔드 클린 전체 테스트 그린 · 프론트 빌드 그린 · 신규 단위 테스트(SceneRenderServiceTest 8종) 그린.

## B. 차기 세션 ① — 패치 결과 테스트 체크리스트 (로컬)

1. **mood 태그 한글**: 새 캐릭터 생성 → 프로필 무드 칩이 한글인지
2. **외형태그 영속화**: 바인딩 후 characters.appearance_tags 채워짐 + 공식 4종 재시딩 반영
3. **빈값=삭제**: 프로필 편집에서 tagline 등 빈 문자열 전송 → 삭제되는지
4. **공개 철회**: 어드민 철회 → 타 유저 기존 방에서 메시지·시간넘기기 차단(CHARACTER_UNAVAILABLE)
5. **모더레이션 2차 판정**: 무협 성장담 세계관(어제 반려됐던 텍스트) 재제출 → 통과 + moderation_events에 OVERTURNED 기록 확인
6. **루틴 자동생성**: 월드 연결된 UGC 캐릭터 바인딩 → character_routines 행 + 유령 키 없음
7. **배경 정적-우선**: V2 스토리에서 시드 장소 이동 → 배경 생성·영구 캐시(2회째 히트)
8. **씬 렌더**(env 필요 — SCENE_RUNPOD_API_KEY/ENDPOINT_ID + SCENE_ILLUST_ENABLED=true): SANDBOX 대화 → 상주 씬 일러 + 네비게이션 + 스킵 디덥
9. V15/V16 마이그레이션은 로컬 적용 완료(주의: 로컬 DB에서 diorama V900 히스토리 행을 제거했음 — feature/diorama 복귀 시 멱등 재적용됨)

## C. 차기 세션 ② — UGC V2 잔여 에픽

- **World enum PK 마이그레이션** (STORY 개방 2단, 최대 난제): 공식 World(WorldId enum PK) vs UgcWorld(Long PK) 이원화 통합. V2 전체(방 unique·라우팅·프레즌스·디렉터 프롬프트·시더)가 enum에 묶여 있음. **설계안 비교(enum 유지+브리지 vs 전면 Long PK 전환) → 종원 확정 → 구현** 순서 권장. 프로드 데이터 마이그레이션 포함.
- **STORY/THEATER 개방** (3단): createUgc 불변식 + LobbyService/StoryV2Service 가드 해제 + 어드민 토글. 루틴 자동생성(1단)은 완료 — 휴면 데이터 상태.
- P0 운영 잔여(종원 콘솔): fal 동시성 2→10~20, RunPod 보조워커 crash-loop, 전용 버킷+lifecycle 30일+퍼블릭 차단 원복, 웹훅 실환경 검증, 월드 E2E+flux-2 원가 실측.

## D. 종원 결정 대기 (구현 준비 완료 상태)

1. **푸시/배포 시점** — A절 전체가 미푸시. V15/V16은 additive·멱등이라 배포 안전. applySeed 픽스로 공식 캐릭터 age/외형/복장이 첫 영속되어 프로필 API 응답이 갱신됨(의도된 동작).
2. **씬 렌더 활성화** — 절차 3단계(전용 엔드포인트 웜 워커 → SCENE_RUNPOD_* env → SCENE_ILLUST_ENABLED=true). **에너지 정책 미정**(현재 무과금 — 디덥만으로 비용 통제).
3. **로컬 S3 격리 + 버저닝** — 혼합 사고 재발 방지(§E). `lucid-chat-assets-dev` 신설 + application-local.yml 오버라이드 + 두 버킷 버저닝 ON. **승인 시 즉시 실행 가능. 픽스 전 로컬 UGC 빌드 금지.**
4. feature/diorama의 application.yml 모델 스왑 stash(로컬 실험) — 커밋/폐기.
5. 공식 6종(claire~seolah) appearance-tags — 실제 에셋 대조 후 작성(백로그).
6. 시스템 어드바이스(원샷 연출 지시) 프로드 도입 — docs/09 §D 검토 대기.

## E. 사고 기록 (2026-07-30, 해결 완료)

로컬↔프로드 **버킷 공유**(lucid-chat-assets-v2) + jobId 기반 키(`characters/ugc-{jobId}/`) 충돌로 로컬 빌드가 프로드 캐릭터 16(ugc-10) 이미지를 덮어씀 → 워커 출력 버킷(lucidchat-ugc-gen) 원본 + CloudWatch 로그 매핑으로 **완전 복원**. 복구 방법론·환경 함정은 메모리 `s3-bucket-sharing-incident` 참조. **잔여 리스크: 격리 픽스(§D-3) 전 로컬 UGC 빌드 실행 금지 — 특히 로컬 job 11(BASE_WAIT) 이어가기 금지(프로드 ugc-11 덮어씀).** 로컬 잔재(ugc-7/9)는 정리 완료, 로컬 job 9·10·11은 로컬 DB에만 존재.

## F. 새 세션 시작 가이드

읽는 순서: 메모리 인덱스(자동 로드) → **이 문서** → 필요 시 docs/07(V2 계획)·docs/09(이식 정본)·docs/04(UGC 상세). 이번 세션의 상세 분석 산출물: 전수 코드베이스 맵 wf_1b3f946e-fd3 · 적대적 리뷰 wf_064d0e1d-d71.
