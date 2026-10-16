# Neutral 얼굴·손 보정 운영 릴리스 — 2026-10-16

## 사용자 승인과 구현

사용자가 [세 태그 샘플](48_Studio_Neutral_Core_Face_Tags_Bakeoff_2026-10-08.md)의 품질을 확인한 뒤, FaceDetailer는 기본 태그 + 캐릭터별 눈 색 + 표정 세 개만 적용하고 HandDetailer도 함께 푸시·프로덕션 배포하도록 명시했다.

Neutral 전용 workflow를 WF-2 전역 리파인 → FaceDetailer0.4 → HandDetailer0.3 → SaveImage1개로 분리했다. 얼굴은 전용 CLIP conditioning에 `detailed beautiful eyes, <eye color>, <expression>`만 전달하고 wildcard는 빈 값이다. 손은 별도 `detailed hands`/빈 wildcard이며 hand_yolov8n bbox를 사용한다. 전역 외형·복장·persona는 얼굴/손 conditioning으로 합쳐지지 않는다. 남성 LoRA는 두 전용 CLIP·detailer에도 연결한다. 원화WF1·레거시 감정WF2·GPT감정·WF3와 에너지 정책은 유지한다.

기존 프로필/외형 메타데이터 LLM 요청에서 선택적으로 `face_detail_tags.eye_color/expression`을 함께 추출해 StructuredConcept JSON에 저장한다. 추가 LLM 호출/DB migration은 없다. 비정형 optional 데이터는 전체 프로필 실패 대신 fallback한다. 프로필 편집·감정 병합에서 보존하고 외형 리롤·이전 원화 선택에서는 해당 스냅샷 값으로 바꾼다. 과거 JSON은 appearance/persona/저장 positive에서 짧은 눈색·표정을 선택하며 없으면 `same eye color`/`neutral expression`으로 원래 정보를 보존하도록 지시한다. 샘플의 amber/smug를 다른 캐릭터에 고정하지 않는다. 쉼표·가중치·wildcard 지시·화장/복합 태그는 얼굴 슬롯에서 제외한다. 원화 생성 프롬프트의 자유로운 구성에는 이 제한을 적용하지 않는다.

어드민 인스펙션에 neutralFacePositive/neutralHandPositive를 추가해 신규 neutral의 실제 conditioning과 레거시 감정 wildcard를 구별한다. 과거 제출 HTTP 원본을 복원한 것이라고 주장하지 않는다.

## 검증과 배포 준비

최종 전체 unit359/실패0/오류0/skip0·bootJar 성공, 네 workflow의 JAR bytes가 소스와 일치했다. exact3/다른 눈색·표정/누락·비정형 optional·JSON 왕복·snapshot교체/서비스 metadata 재추출·원화 prompt 보존·남캐 재배선·독립seed·단일Save·기존경로 보존을 검사했다. 최초 실패는 새 BREAK 거부 누락과 기존 Story 계약fixture의 Windows CRLF 차이였고, guard를 positive grammar로 보완했다. fixture는 Git HEAD의 원래 LF bytes로 읽어 추적 파일 변경 없이 재검사했다. [로컬 증빙](52_assets/local-validation.json).

원래 checkout feature/diorama의 별도 작업은 보존하고, origin/master72e530d에서 관리 worktree/codex/neutral-detailers로 분리했다. 운영 기준선은 V39·캐릭터31행·진행UGC0·앱health200/OK, UGCmin0max1/image0.3.0·씬min0max2/image0.2.0이었다. 환경·compose·컨테이너 및 PG를 운영 private backup에 저장했다.

기존 실험에서 검증한 손 모델 포함 worker bb12158/pinned6a9d54d6…를 재사용한다. 운영 UGC의 기존 template env3개·disk40GB 등을 복제하고 volume0을 명시한 새 template로 UGC만 rolling 전환했다. 원래 template는 롤백용으로 남긴다. 새 worker 준비와 전체 neutral graph 단일실행 검증을 위해 UGCmin을 잠시1로 유지하며, 성공/실패 후 원래0으로 복원한다. 씬 template/endpoint는 변경하지 않는다. **이 문단 작성 시점에는 BE push/배포 전이며, 실제 운영 결과는 아래 후속 기록으로 확정한다.**

## 잔여 한계와 롤백

한 캐릭터의 검증은 모든 외형·겹친손·장신구 품질을 보장하지 않는다. WF2에서 이미 바뀐 제스처/눈 화장선을 완전히 복원한다는 보장도 없다. 사용자의 앱 전체 유료 생성·리롤 UX 확인은 별도다. 실 provider 청구액은 미확인이다.

문제 발생 시 BE를 기존 그래프로 먼저 되돌리고, 손 검출 모델이 필요 없는 상태가 확인된 뒤 worker template를 이전 것으로 되돌린다. 손 모델이 없는 worker에 새 BE만 배포해서는 안 된다. 새 source/worker 검토는 studio_preflight, 운영 설정/데이터/릴리스 경계는 incident_review가 별도 컨텍스트에서 수행한다.

## 실제 운영 배포 완료 (2026-10-16)

BE `5dfce4dc3dd05a75c678685fe82c7a550eee28b7`을 master로 푸시했고 [Actions38860126987](https://github.com/zapzookj/LucidChat-Server/actions/runs/38860126987)이 success로 완료됐다. worker 소스도 `bb1215833e0a59a771fb9f235dc89c3fd47e0be3`으로 origin/master를 fast-forward했다. worker workflow는 수동 빌드만 지원하므로 이미 검증한 immutable digest를 운영에 사용하고 불필요한 재빌드를 하지 않았다.

운영 새 이미지의 실제 nonstale worker를 확인한 뒤 최종 WF2→세 태그Face0.4→Hand0.3 단일 graph를 **1POST/DONE1/PNG1**로 실행했다. GPU39.207초/queue1.467초. 실행 응답 workerId와 실제 worker 목록의 candidate digest를 독립 연결했고, 운영 env를 보존한 s3_url 응답은 원래 BUCKET endpoint host에만 무인증 GET하여 PNG1024²·바이트/SHA를 대조했다. 별도 앱 생성/에너지 차감은 없고 유료 앱 전체 E2E는 미실행이다. 외부 runner의 finally에서 UGC min을 원래0으로 복원했다.

실행 image RepoDigest는 커밋5dfce4d manifest의 `sha256:8a4ef607b0e1f1f8e13fe31f84d7ebbc6307cdef79c5b2b2fdf7ffa5c0f7542d`와 일치하고 공개health는200/OK다. 운영 JAR의 workflow4종은 최종 소스/로컬 JAR와 JSON 동일했다. 변경 class11개 중8개는 raw byte 동일, 나머지3개는 reference 문자열 결합의 compiler 생성 방식 차이가 있어 javap 명령열·분기/예외 흐름을 보조 대조했다. 정규화 일치만으로 일반적인 class 의미 동등성을 입증한다고 주장하지 않는다. 실제 배포 커밋 관계는 CI 성공 이미지 manifest와 실행 digest로 확인한다. 기존 workflow3종은 CRLF 외 내용 불변이며 Windows와 Linux의 raw resource bytes 차이와 구별한다.

독립 배포 후 대조에서 전체 env·env파일SHA/권한600·기존 캐릭터31행 전체hash·V39·씬 endpoint 전체설정/template가 보존됐다. UGC 전체 GET 설정의 변경은 templateId/version뿐이며 min0/max1·CUDA12.6·FlashBoottrue와 그 밖의 설정은 원래 값이다. template env3개/disk40GB도 보존했다. 최종 queue0·진행0·활성앱UGC0. old template는 롤백용으로 보존한다. 실청구액과 사용자의 앱 미관/리롤 UX는 미확인이다.

비밀 없는 [실제 배포 증빙](52_assets/production-verification.json), 원본 payload/로그/원장/운영 snapshot은 Git 제외 `tools/tts/.local/neutral-release-20261016`에 보관했다. 구현 commit와 이 문서 후속 commit를 구별한다. 관측·기록2026-10-16 KST.
