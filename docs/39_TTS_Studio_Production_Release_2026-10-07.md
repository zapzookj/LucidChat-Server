# TTS 타이밍·원화 스타일 보정 운영 배포

관측·기록일: 2026-10-07 00:31 KST. 출처: 사용자의 명시적 푸시·프로덕션 배포 요청, GitHub Actions/Deployment API, 운영 SSH 읽기 점검, 공개 서비스 번들 대조. 구현·로컬 검증은 [docs/38](38_TTS_Timing_And_Studio_Art_Style_2026-10-06.md)에 기록했다.

## 배포 결과

- BE `b0f8f1f21e10d95ca3456183ccacc8157f0cf2b2`, FE `a85ef381e387bfcdacd3054c0db3da610f49fa40`을 각각 master에 푸시했다.
- [BE Actions 37487036828](https://github.com/zapzookj/LucidChat-Server/actions/runs/37487036828) 성공. CI의 전체 `*Test` 검사·bootJar, GHCR 이미지 발행, 운영 SSH 배포가 성공했다.
- FE 해당 SHA의 Vercel Production 배포 상태는 success. 운영 도메인 `https://lucid-chat.com`의 `/assets/index-BrKvZIUq.js`는 해당 커밋을 운영 공개 환경 변수로 빌드한 전체 내용과 일치했다. Windows 빌드의 template literal에 포함된 escaped CR+LF 55곳을 LF로 정규화한 비교이며 raw hash가 같다는 뜻은 아니다. Vercel 개별 배포 URL은 로그인 보호되어 직접 번들 조회하지 못했다.
- 운영 `lucid-app` 실행 이미지가 위 BE SHA의 manifest digest `sha256:6c4a5134ee0960ae7c8fae7c0f02172e069da3f4c7d3489338df6323c1b7e72f`와 일치한다. 컨테이너 running, 공개 `/health` HTTP200·본문 `OK`. Docker healthcheck는 설정되지 않아 health 필드는 null이다.

## 보존과 검증 범위

운영 컨테이너 환경 변수 전체는 배포 전후 동일하며 `/opt/lucid/.env` 권한600을 유지했다. DB migration39 유지, 기존 캐릭터26개의 전체 컬럼 해시가26/26 동일하다. 새 환경 변수·마이그레이션·RunPod 설정 변경은 없다. Neutral 기존 Qwen+후처리, 감정 Sunburst Low/리롤 High 설정을 유지했다. 별도 UIUX23·디오라마·실험 도구 및 `.gitignore` 변경은 커밋에서 제외했다.

TTS 표시 ID 보강 시 재타이핑 방지, 자동 음성 최대6초 준비 후 재생과 타이핑 동기화·실패/지연 폴백, 다음2개 음성 준비, BGM320ms 감소/900ms 복원과 WF1 원화2D 앵커·명시 사진/3D 태그 보정을 반영했다. 과금 정책은 변경하지 않았다. 기존 로컬 동작 검증·독립 검토 결과는 docs/38을 참조한다.

운영 로그인 자동/수동 유료 TTS E2E와 공급자 지연 구간별 계측, 보정 후 새 원화 생성 품질 A/B, 실제 워커 모델 파일 해시는 이번 배포에서 확인하지 않았다. 배포 성공과 새 이미지 품질 검증을 구분한다.

안전한 배포 증빙: [운영 확인](39_assets/production-proof.json), [프론트 번들 비교](39_assets/frontend-bundle-proof.json), [파이프라인 상태](39_assets/pipeline-proof.json). 자격정보·전체 환경 스냅샷은 포함하지 않는다. 이 기록의 후속 문서 커밋은 배포 워크플로의 docs/Markdown 제외 조건에 따라 새 BE 이미지 배포를 만들지 않는다.
