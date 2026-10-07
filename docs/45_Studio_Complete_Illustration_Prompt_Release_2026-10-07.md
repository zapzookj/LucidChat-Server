# 원화 완성형 프롬프트 적용·운영 배포 — 2026-10-07

## 승인과 구현

출처: [docs/44](44_Studio_Complete_Prompt_Direction_2026-10-07.md)의 완성형 프롬프트 제안에 대한 사용자 “그래, 그 방식으로 수정해보자. 적용해서 프로드에 배포해라.” 이번 구현과 master 푸시·프로덕션 배포의 명시 승인이다.

첫 이미지 전용 요청은 WAI Illustrious·캐릭터 컨셉·매력적인 일본 서브컬처풍 2D 일러스트 목표를 전달하고, 실제 투입할 `positive_prompt`·`negative_prompt` 두 문자열을 요청한다. 외형·복장뿐 아니라 그림체·품질·인물의 매력·분위기·구도·조명을 자율 구성한다. 태그 개수·등록된 Danbooru 태그 한정·고정 품질/스타일 prefix·구체 조명 목록은 강제하지 않는다. LLM이 선택한 가중치·중복·공백과 빈 negative를 그대로 보존한다. 프로필 지시는 이 첫 요청에 포함하지 않는다.

후속 메타데이터 요청은 확정된 positive를 참조해 외형·복장·표정·씬 태그와 프로필을 생성한다. negative의 배제 대상은 태그로 추출하지 않도록 지시한다. 메타데이터가 반환한 `illustration_prompt`는 무시해 최초 쌍을 덮어쓰지 못하게 한다. 초기 나이·검수·외형·복장 필수값, 세 태그 배열과 항목 형식 검증은 유지한다.

신규 작업 JSON과 원화 배치 스냅샷에 `illustration_prompt`를 저장하고 WF1 positive node12·negative node13에 그대로 전달한다. 프로필 편집·감정 프롬프트 갱신에서도 보존한다. 과거 배치 선택 시 해당 후보의 쌍을 복원하며 빈 negative와 null을 구별한다. 구버전 후보는 null로 복원하고 현행 레거시 세 배열 조립+빈 negative로 폴백한다. 당시 HTTP 요청을 완전 복원한다는 뜻은 아니다. 어드민 원화 인스펙션도 저장쌍을 표시한다. DB 컬럼·마이그레이션은 추가하지 않는다.

원화 리롤도 동일한 이미지 지시를 사용하고 기존 디자인·변경 요청을 컨셉 데이터로 전달한다. 외형 메타데이터만 후속 갱신하며 최신 프로필 성격·서사·첫인사 등 사용자 편집을 보존한다. 두 논리 요청·같은 Stage0 모델/temperature0.7/8192 예산, 성공 산출 재사용·취소 체크·accepted/예약/중복 POST 억제·불명확 제출 후 재POST 금지·free/paid 분할 환불 경계를 유지한다. 실제 HTTP 재시도와 실행 수명 캐시의 한계·단일 JVM 전제는 [docs/43](43_Studio_Approved_1A_Implementation_Release_2026-10-07.md)와 같다.

WAI·LoRA·샘플러·해상도·FaceDetailer·기본 원화 배치2, neutral의 Qwen+WF2, 감정의 Sunburst Low/리롤 High, 누끼·TTS·FE·에너지 단가는 유지한다. 원화 WF1 template의 negative는 계속 빈 문자열이고 신규 요청에서 동적으로 주입한다. 추가 LoRA 이름을 프롬프트에 쓰더라도 Comfy graph의 로딩 설정을 바꾸지 않는다.

## 검증

로컬 전체 `gradlew.bat test --tests '*Test' bootJar --no-daemon` 성공: 50클래스/361건 실패·오류·skip0. 이 중 별도 미커밋 디오라마10건은 배포에서 제외하므로 배포 대상 검사351건이다. 최종 주석/로그·영속화 테스트 보완 후 원화 관련6클래스59건과 bootJar를 재검증했다. 필수값은 정상 fixture에서 해당 필드만 제거해 실제 실패 분기를 검사했다.

완성쌍의 형식 실패/빈 negative·문자열 보존, 후속 응답 오염 차단, profile/감정 copy, modern/legacy 후보 양방향 복원, 최신 프로필 편집 보존, 양 성별 WF1 node12/13·어드민 표시, WF2 불변, 리롤 스냅샷, 취소·accepted·예약·환불 경계를 확인했다. 과거 실제 1-A 전체 워크플로 fixture는 레거시 호환 검증으로 유지한다.

`studio_preflight`가 실제 diff와 영속화·가드 경계를 읽기 전용 독립 검토했고 차단 결함은 없었다. 필수 프로필값 테스트와 오래된 설명 주석을 보완했다. `AichatApplicationTests` 전체 Spring 컨텍스트, 운영 유료 생성/실청구·새 이미지 미관 비교는 실행하지 않았다. 미관과 실제 빌드/리롤은 배포 후 사용자 테스트 대상으로 남긴다. 이번 릴리스에서 추가 유료 실험을 수행하지 않았다.

로컬 미커밋 UIUX23·디오라마·docs40 후속 조사·실험도구·.gitignore는 제외한다. 로컬 JAR를 배포하지 않고 GitHub Actions가 커밋 소스만 checkout해 전체 검사를 다시 수행한다.

## 운영 관측

배포 직전 직접 조회: 컨테이너 running, Flyway V39, 캐릭터26행, env 파일600, UGC min0/max1·씬 min0/max2. 앞선 docs/43의 UGC min1은 당시 관측이며 이번 배포가 RunPod 설정을 변경하지 않는다. 배포 후 실제 코드 SHA·Actions·이미지 digest·health 및 설정/데이터 보존 대조 결과를 아래에 추가한다.


2026-10-07 23:12 KST 운영 배포 완료. 코드 `66d7ce00fc5c3890b3a3db81ed4287a8e6d4508c` master 푸시 후 [Actions 37634337038](https://github.com/zapzookj/LucidChat-Server/actions/runs/37634337038) 테스트·빌드·GHCR 업로드·SSH 배포 전체 success. 실행 컨테이너 이미지 digest와 이 코드 SHA의 GHCR manifest가 일치한다. 이후 문서 커밋이 생겨도 실제 배포 코드는 이 SHA다.

공개 health HTTP200/`OK`, 컨테이너 running을 확인했다. env 전체·env파일600·Flyway V39·기존26캐릭터 모든행hash·RunPod 비교설정 보존. UGC min0/max1·씬 min0/max2 유지. Stage0 설정 `openai/gpt-5.6-sol`·환경 override 없음. 운영 JAR에 신규 완성형 이미지 지시가 존재하고 WF1 template/WF2 전체 JSON이 소스와 일치한다. 신규 negative는 template가 아니라 요청 빌더에서 node13에 주입된다.

ConceptStructuringService·UgcWorkflowFactory·StructuredConcept·IllustrationPrompt는 로컬 compiled class와 전체 hash가 일치한다. UgcPromptAssembler·UgcPipelineWorker·CharacterCreationService·AdminUgcReviewService는 전체 hash가 달라 변경 메서드를 disassembly로 대조했다. constant-pool 참조번호/주석 정렬 공백을 정규화한 원화 조립·Stage0/리롤·예약/제출·후보 선택·어드민 표시와 관련 lambda는 일치한다. 프로필 편집 lambda는 수정하지 않은 상태 오류 메시지의 컴파일러 String.valueOf 삽입으로 주소가 달라, 상태 가드 뒤 JSON 읽기부터 저장까지 전체 명령과 분기 대상(명령 순번으로 치환)을 대조해 일치를 확인했다. 전체 클래스나 lambda가 원문 그대로 같다고 표현하지 않는다. 라이브 이미지 생성/미관·실청구는 이 운영 확인에 포함하지 않는다.

검증 [validation-summary](45_assets/validation-summary.json), CI [actions-release-proof](45_assets/actions-release-proof.json), 운영 [production-release-proof](45_assets/production-release-proof.json), 메서드 [runtime-method-proof](45_assets/runtime-method-proof.json). 비밀 env·전체 DB 관측·운영 JAR는 Git 제외 로컬에만 보존한다.
