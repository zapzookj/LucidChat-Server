# GPT Image 2.5 최적화 실험·동적 바리에이션 검토

관측일·기록일: 2026-10-06. 사용자 2차 평가를 반영한 3차 실험이다. [2차 정본](31_Studio_Controlled_Gestures_GPT_Image_25_2026-10-06.md) · [3차 비교 화면](http://127.0.0.1:18769/round3/index.html).

## 1. 사용자 결정과 이번 범위

사용자는 2차 **GPT Image 2.5 편집 직후**의 원본 인상·제스처 보존과 자연스러운 표정/동작을 가장 좋게 평가했다. 감정 파생을 GPT로 교체하고 그 단계의 WF-2·FaceDetailer를 제거하는 방향을 선택했다. 앞선 Qwen 유지 권고는 이 사용자 평가로 대체한다. 최종 variant/quality 선택과 제품 적용은 아직 하지 않았다.

같은 프로덕션 UGC 연 neutral에서 JOY/ANGRY/SHY/PANIC/PLEADING을 비교했다. 기존 F Sunburst High 5장을 재사용하고, Sunburst Medium/Low와 Flare High/Medium/Low 각5장, **신규25장**을 생성했다. 2차 F와 positive prompt·입력·1024²·opaque·PNG·num_images=1이 같고 variant/quality만 달랐다. mask·seed·리롤·RunPod·WF-3·DB/에너지/웹훅 호출은 없다. 새 배치는 동시5이며 기존 F 배치는 동시2였다.

실제 모델 ID는 `openai/gpt-image-2.5/sunburst/edit`와 `openai/gpt-image-2.5/flare/edit`. 여기서 quality는 출력 품질/연산량이며 표정이나 동작의 강도 설정이 아니다. 감정 강도는 동일 프롬프트에 고정했다.

## 2. 비용·지연·시각 관측

| 조건 | 장당 공개 편집 추정 | 종단 중앙값 | 종단 범위 | 표본 |
|---|---:|---:|---:|---|
| F Sunburst High | $0.0610 | 46.99초 | 41.49–47.71초 | 이전5 재사용 |
| G Sunburst Medium | $0.0215 | 28.27초 | 22.66–30.46초 | 신규5 |
| H Sunburst Low | $0.0142 | 25.28초 | 22.37–28.80초 | 신규5 |
| I Flare High | $0.0610 | 28.57초 | 28.12–31.03초 | 신규5 |
| J Flare Medium | $0.0215 | 24.83초 | 22.56–107.26초 | 신규5 |
| K Flare Low | $0.0142 | 22.88초 | 18.94–24.83초 | 신규5 |

[fal 가격표](https://fal.ai/gpt-image-2.5)의 1024² 참조1장 포함 편집 추정이다. 두 variant는 같은 quality에서 단가가 같다. 신규25장 합은 **약 $0.662 + 프롬프트 추가분**, 토큰 usage/계정 실청구 미확인이다. Low는 High보다 약76.7%, 기존 Qwen 1024² 편집 $0.03145728보다 약54.9% 낮다. Medium도 Qwen보다 약31.7% 낮다. neutral·WF-3·저장·LLM·재시도는 제외한다.

신규25장의 POST 시작→모두 다운로드/검증 완료는 **158.858초(2분39초)**. 종단 시간에는 queue·5초 polling·다운로드/검증이 포함된다. F는 다른 시점/동시성이라 High 지연 개선을 variant/quality의 단독 효과로 확정하지 않는다. J SHY 107.26초의 꼬리도 보존한다. 5장 중앙값을 서비스 SLA로 사용하지 않는다.

전신·동일좌표 얼굴 크롭과 개별 원본을 검토했다. 별도 컨텍스트 검토자는 30개 개별 원본도 확인했다.

- **H Sunburst Low: 비용 우선 후보.** 얼굴·헤어·의상·화풍과 낮은 동작이 대체로 유지되고 SHY 소매 잡기/PLEADING 손바닥 요청도 살아 있다. SHY의 홍조·고개 회전은 F보다 강하며 손가락 각도·문양은 달라진다. F와 완전히 동등하다는 판단은 아니다.
- **G Sunburst Medium: 보수적인 절충 후보.** F에 가까운 인상과 작은 동작을 유지한다. 이번 셀당 한 장에서는 Low 대비 비용 상승에 상응하는 일관된 품질 개선까지 입증하지 못했다.
- **Flare I/J/K: 얼굴 품질은 좋지만 일부 동작 전달이 약했다.** SHY 3장 모두 반대 소매 대신 허리끈 부근을 잡으며 PLEADING 손도 더 낮거나 덜 펼쳐진다. 같은 단가에서 Sunburst를 대체할 뚜렷한 이점을 확인하지 못했다. 이번 표본 관측이지 variant 전반의 우위 판정은 아니다.
- Low에서도 전반적 선명도/화풍 붕괴나 뚜렷한 손가락 추가·융합은 발견하지 못했다. 미세한 손·장식 변화, 약한 ANGRY/PLEADING 대비는 남는다. 한 캐릭터·감정/설정당 한 장이므로 최적값 확정은 사용자 시각 평가와 구별한다.

[OpenAI 공식 지침](https://developers.openai.com/api/docs/guides/image-prompting)도 같은 입력/quality/size로 비교한 뒤 quality를 낮춰 직접 측정하도록 안내한다. 실제 fal 요청 계약은 [Flare edit API](https://fal.ai/models/openai/gpt-image-2.5/flare/edit/api)와 대조했다.

## 3. 후처리 제거의 범위

사용자 선택을 구현할 때 **감정 파생 경로**를 `승인된 neutral RGB → GPT edit → WF-3 배경 제거 → 자산 저장`으로 바꾸는 것이 적절하다. 모든 감정은 같은 neutral에서 독립 파생한다. 앞 감정 이미지를 다음 편집 입력으로 연결하지 않는다.

neutral 베이스는 현재 품질이 좋고 자체 WF-2 단계가 있다(`UgcPipelineWorker` base 단계). 감정 단계 WF-2/FaceDetailer 제거를 neutral 단계까지 일괄 확대하지 않는다. WF-3은 원본 RGB에 추출 alpha를 결합하며 샘플러/얼굴 재생성이 없다. **새 GPT 결과의 WF-3 실제 cutout·앱 표시·머리/손/흰 소매 경계는 미검증**이다. 진행 중인 이전 job은 동결 pipelineVersion에 따라 기존 경로로 마치도록 설계한다.

## 4. 캐릭터별 동적 바리에이션

도입 가치가 있다. 현재도 `StructuredConcept.emotionPrompts`로 캐릭터별 표정/자세를 개인화하지만 `ConceptStructuringService`는 EmotionTag 키만 허용하고 `UgcPipelineWorker`는 고정14개를 순회한다. **개별 연출만 달라지고 감정 목록 자체는 모든 캐릭터에게 같다.**

권고 계약은 캐릭터별 expression catalog를 job 단계에서 확정·동결해 영속화하고, 완성 캐릭터에 같은 revision으로 전달하는 것이다.

| 필드 | 역할 |
|---|---|
| 안정 `expressionId` | 서버가 부여하는 캐릭터 소유 ID. 표시명/프롬프트/리롤 이미지와 분리 |
| `label`·`selectionCondition` | 제작자에게 보이는 이름과 채팅 LLM이 선택할 상황 |
| `semanticEmotion` | 기존 의미감정 enum에 연결. 같은 JOY에 서로 다른 고유 expression 여러 개 허용 |
| 짧은 `expressionPrompt`·`gesturePrompt` | 이미지 편집용. 작은 제스처를 허용하며 전체 외형 보존 공통지시와 합성 |
| catalog/asset revision·assetKey | 리롤/재편집 이후의 자산과 과거 로그가 가리키는 버전 구별 |

예를 들어 연은 ‘냉소적 미소’, ‘자존심 상한 불쾌함’, ‘승부욕에 찬 눈빛’, ‘약점 들킨 당황’, ‘마지못한 인정’, ‘티 내지 않는 안도’, ‘조심스러운 온기’가 기존 고정 세트보다 개성을 잘 전달할 수 있다. 이는 검토 예시이며 새 이미지를 생성하거나 확정한 목록은 아니다. 각 표현에 사용 조건을 붙여 이름만 바꾼 동일 감정 풀에 머무르지 않게 한다.

neutral은 필수 fallback으로 유지한다. 초기 범위는 **neutral + 고유8–12개** 정도가 비용·구별 가능성의 절충 후보이며 개수/과금 확정은 아니다. 14개 전체를 Medium으로 편집하면 약$0.301, Low 약$0.1988; 고유8–12개면 Medium 약$0.172–0.258, Low 약$0.1136–0.1704다. 현재 감정 단계8에너지/리롤2에너지는 이 편집 단가만으로 변경하지 않는다. 생성 전 확정 개수·전체 단계 비용·실패 정산 정책을 함께 검토한다.

시스템 프롬프트에는 **화자별** 허용 expressionId·이름·선택 조건을 넣고 `scenes[].expressionId`를 출력하도록 한다. 편집용 긴 지시 전체를 채팅 프롬프트에 넣지 않는다. 화자 소유 여부를 서버가 검증하고 알 수 없거나 다른 캐릭터 ID면 그 캐릭터 neutral로 fallback한다. 기존 `emotion`은 의미/연출·레거시 로그 호환을 위해 함께 유지한다.

필드 하나 추가만으로 완성되지는 않는다. 다음 경계를 같이 연결해야 한다.

- `CharacterCreationService`/worker의 고정15장 완성 판정 → catalog 필수 ID 집합과 준비된 asset ID 집합의 정확한 일치. 개수만 같은 누락/중복을 완료로 판단하지 않는다.
- enum 기반 리롤 API·FE StudioCreateFlow의 고정 EMOTION_ORDER/14 진행률·심사 AdminUgcReviewService·worker publish 자산 승격 루프 → 동결 catalog 기반.
- LLM 출력 파서·scene/log DTO·FE CharacterDisplay의 `{outfit}_{emotion}.png` 경로 → expressionId/asset revision 조회. 늦은 이전 리롤 응답이 새 자산을 덮지 않도록 generation revision 확인.
- 기존 공식/UGC 캐릭터는 현재15자산에서 가상 legacy catalog를 제공하고 재생성하지 않는다. expressionId 없는 과거 로그는 기존 emotion 경로로 복원한다. UGC 자유 모드뿐 아니라 연결된 세계관의 STORY/THEATER도 확인한다.

실제 구현 검증에는 정상 custom 선택·다른 화자 ID 거절·legacy 로그 복원·누락/중복 완료 차단·늦은 리롤 응답·WF-3 alpha 경계가 필요하다. 제품 UI가 크게 바뀌는 부분은 목업을 먼저 검토한다. 이번 독립 구조 검토는 코드 읽기이며 이 경계를 구현/실행한 검증은 아니다.

## 5. 지난 35분의 구성

이전 턴 메타데이터는 03:43:37Z 시작, 04:19:09Z 완료(정밀 duration 2,131,782ms), **35분32초**였다. 시간 대부분을 모델 생성 시간으로 설명할 수 없다.

| 비중 | 기록상 시간 |
|---|---:|
| 시작→첫 fal POST: 조사·입력/도구 준비·검토 | 7분47초 |
| 편집10장 POST→마지막 raw 검증 | 4분00초 |
| 후처리 진입 전 간격 | 15초 |
| 후처리 첫 제출→마지막 완료 | 12분46초 |
| 이후 운영 복원·감사·갤러리/분석·문서·응답 | 10분44초 |

후처리에는 cold start/초기화의 첫 queue 약6분56초와 HTTP409 거절 대조/보존 후 접수가 포함됐다. 동시 작업의 대기 시간을 단순 합산하지 않았다. 실제 RunPod executionTime 합은 215.304초로 queue/유휴와 구별된다. 샘플 비교에 비해 도구 확장·검증/기록 작업을 크게 잡은 수행상의 비효율도 있었다.

이번에는 기존 runner/fixture를 재사용하고 raw만 동시5로 실행했다. 25장 생성 배치2분39초는 **이번 턴 전체 소요 시간이 아니다**. 구조 검토·원장 감사·화면 확인·결과 전달은 별도다. [분해 근거](../tools/studio-bakeoff/.local/yeon-20261006-round2/elapsed-breakdown.json).

## 6. 검증과 미실행 범위

신규25 DONE/고유ID25·요청 SHA/neutral/input pairing25·출력1024²·산출SHA, 갤러리 신규25+기존F5 원본SHA와 분해합30을 대조했다. RunPod 원장0·실제 새 제출25·오류증거0·미확정0. 동결 manifest의 정상25 및 누락/중복/모델/quality/개수/후처리/동시성 변조 거절을 오프라인 검증했다. 별도 컨텍스트로 과금 경계/원장/개별 품질·동적 catalog 구조를 검토했다.

브라우저 전신/얼굴 각35개 표시 이미지 로딩, neutral 확대 비교·Escape 닫기·가로 넘침을 확인했다. gallery는 credential 없는 review/round3만 사용하고 키/원장/provider 응답은 ignored `.local`에 보존한다. 이번 턴 RunPod 호출/설정 변경0이며 DB·제품 코드·에너지·자산 쓰기/커밋/푸시/배포는 없다. 전체 앱 테스트·WF-3 실제 처리·다른 캐릭터·동일 셀 반복·실청구 대조는 미실행이다.

공개 도구/README/새 정본·gallery 텍스트17개에서 확보한 API credential 값 일치0, gallery 안 credential 파일0을 확인했다. FE 작업트리 clean, BE 기존 UIUX/diorama/공유 ignore 변경을 보존했다. 1차 세션 출력 키 노출/회전 미실행 잔여는 이전 정본에 보존하며 이번에는 worker env를 조회하지 않았다.

[실행 도구](../tools/studio-bakeoff/README.md) · [새 원장 audit](../tools/studio-bakeoff/.local/yeon-20261006-round3/final-results-audit.json) · [전신 비교표](../tools/studio-bakeoff/.local/yeon-20261006/review/round3/raw_full.jpg) · [얼굴 비교표](../tools/studio-bakeoff/.local/yeon-20261006/review/round3/raw_faces.jpg).

후속 순서는 스튜디오→TTS. TTS 자동ON의 LLM 별도 태그 입력/수동 변환/env 모델 계약은 기존 합의를 유지하며 이번 턴 구현하지 않았다.

## 후속 사용자 선택·최종 실험 (2026-10-06)

사용자가 **감정 파생 Sunburst+Low를 채택**했다. 원화→neutral에도 GPT High를 검토하도록 범위가 확장됐으므로 위 §3의 ‘neutral 현행 공정 유지’는 이전 단계 제안이다. 원화 quality Max/High/Medium/Low4장과 현행 ANGRY Low1장, 신규5의 [최종 실험 docs/33](33_Studio_Final_Base_Quality_Current_Angry_2026-10-06.md)에 결과를 기록했다. 베이스 High는 권고/최종 평가 전이며 제품 적용은 미실행이다.
