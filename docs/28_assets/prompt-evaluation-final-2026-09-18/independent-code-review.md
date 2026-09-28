# 마지막 프롬프트 실험·정합 수리 독립 검토

검토일: 2026-09-18. 검토자: 별도 컨텍스트 `experiment_audit` 에이전트. 소스 수정·추가 유료 호출·원장 변경 없이 수행했다. 감사 산출물은 이 문서와 [billing-audit.json](billing-audit.json)이다.

## 결론

현재 변경에서 남아 있는 배포 차단 수준의 결함은 발견하지 못했다. 운영 변경은 두 prompt assembler의 정합 수리이며, 실제 생성 품질이 좋아졌다는 검증과 구분해야 한다. 새 중립 JSON 예시는 실험 J2의 문구와 다르므로 J2의 평가를 P1에 그대로 이전할 수 없다.

검토 중 역사적 후보의 동결 경계에서 누락을 발견했고 수정 후 재검증했다. `RosettaFixture.profile()`에는 appearance/clothing/effectiveOocExample/characterId가 없지만 역사적 후보 변환이 현재 Character의 해당 값을 읽었다. profile만 비교하면 향후 설정 변경 때 같은 후보 버전의 메시지가 달라질 수 있었다. 현재 `HistoricalPromptBaseline`은 별도 `candidateFacts`를 원본 resource와 비교하고, 네 필드의 변조 거부 검사도 포함한다. 기존 fixtureProfile의 구조를 바꾸지 않고 보완했다.

## 과금·증거 무결성

- 이전 결과 319개 파일은 사전 보존 목록의 SHA-256과 모두 일치한다.
- 마지막 실험은 계획된 3단계 × 24개 = 72개이며 누락·중복이 없다. 유료 호출 전 계획 파일 hash, 단계 plan/jobs hash, 결과 job/plan 연결이 일치한다.
- 마지막 72개 결과 ↔ 세션 원장 ↔ 전역 원장은 정확히 대응한다. generation ID도 모두 다르다. 전역의 이전 346개 항목은 그대로 보존됐다.
- 기존 프롬프트 실험 304개 $1.148911616666667 + 마지막 72개 $0.32467311666666676 = $1.4735847333333338. 원래 $3 승인 대비 잔여 $1.5264152666666662이며 미확정 예약은 0이다. 별도 과거 모델 비교 비용까지 포함한 전역 원장은 418개, $1.6429894366666682다.
- 요청·실제 모델, provider/tier, reasoning default, temperature 0.8, max_tokens, provider fallback 금지와 가격 상한을 대조했다. 예약 공식, 최종 usage.cost, 원장 금액, upstream 입력/출력 비용 분해합과 토큰 합계가 일치한다.
- 이는 로컬 증거 및 provider가 반환한 usage의 대조다. 별도 청구 명세 API는 조회하지 않았다.

strict JSON 실패 2개는 모두 P0이며 첫 root 객체 뒤에 `] }`가 더 붙었다. `FINAL-H05-r1-t1-P0`, `FINAL-SEQ-01-r1-t2-P0`이다. 기존 Java 검증은 둘 다 수용했다. 실패를 삭제하거나 0원으로 처리하지 않았으며 전체 72개 분모에 포함된다. 이번 변경은 실제 응답 parser의 후행 토큰 허용을 바꾸지 않는다.

## 운영 코드 경계

- `CharacterPromptAssembler`: 외형·기본 복장 전달, 현재 복장 우선순위, 행동·대사·미실행 제안의 구별 및 좁은 서비스 입장 표시 예외를 확인했다. 일반/이벤트·시크릿 스탯·기존 enum·동적 허용 장소/의상·speaker·속마음·topic_concluded 조건은 유지된다. JSON 예시는 Jackson으로 직렬화하고 필드 설명은 예시 밖으로 분리한다.
- `StoryDirectorPromptAssemblerV2`: 중복 배경 제거 후 원본 배경은 한 번 남는다. 일반/시크릿 성격·말투와 폴백은 유지된다. 화자가 없는 장면과 opening에서 유저 내면을 대신 만들라는 충돌을 없앴다. 예시는 현재 인물·숫자 ID·장소를 하드코딩하지 않으며 static part가 현재 화자에 따라 바뀌지 않는다.
- V2 예시에서 생략된 선택 필드도 필드 설명과 기존 Critical Rules에 남아 있다. normal 5종/secret 8종 스탯, 등장 히로인의 실제 숫자 ID, relation/ending 신호 조건을 유지한다. 빈 stat_changes나 환경 전개를 복사하지 말라는 설명이 있다.
- `git diff -- src/main`의 변경 파일은 위 두 assembler뿐이다. theater, stream, parser, DTO, 실제 과금·권한 처리에는 변경이 없다. 새 DB 조회도 추가하지 않았다.
- 역사적 P0와 이전 12개 후보는 별도 bakeoff resource를 사용하며 P1은 live assembler 메시지를 사용한다. 기존 user/assistant history와 현재 cache_control은 보존한다. V1의 3/20 로그 cache 표식, V2의 static cache 표식 검사가 포함된다.
- server의 sourceHash는 `src/bakeoff/resources`까지 포함하고 Gradle의 allSource hash와 일치한다. 현재 runtime hash가 유료 실험 당시와 다른 것은 운영 수리 및 동결 resource 추가에 따른 의도된 변화다.

## 독립 실행·확인

- 현재 Java bridge로 **사전 12후보 행렬 72개**를 재조립하고 messages JSON 바이트가 원본과 전부 같음을 확인했다.
- 별도로 **최종 유료 72개 각각의 실제 input/history/후보**를 현재 bridge로 재조립했다. 전송된 request.messages와 전부 동일했다. 외부 추론은 호출하지 않았다.
- 독립 재조립 runtime/source hash: `00101dc1cb63cc9543e086c2cb1060ceff9c2e05792bafe2154f47327b1a9442`. 유료 실험 hash: `4180da25cb640eb352ec1674ab0d8b093ebf4ae4972783e7e787ce1a792f2fae`.
- 독립 실행한 Node `experiment-final.test.mjs` + `bridge.test.mjs`: 19개 통과. 실제 Java bridge의 history/cache/P1 출처 검사와 승인 잔액·원장/결과 대응·변조/중복·재시작 경계를 포함한다.
- Java 계약 테스트 소스와 결과 XML을 읽어 23개(자유/legacy 15, V2 8) 성공을 확인했다. 이 Java 테스트 전체를 감사자가 별도 재실행한 것은 아니다. root의 최신 bakeoff smoke 완료 보고와 네 candidateFacts 변조 거부 구현을 대조했다.

운영 연결·사용자 장기 세션·다른 인물의 실제 생성 품질·시크릿 생성 품질은 이번 독립 검토에서 실행하지 않았다. 프롬프트 문구의 정합 및 데이터 경계 검사와 생성 품질의 일반화는 별개다.
