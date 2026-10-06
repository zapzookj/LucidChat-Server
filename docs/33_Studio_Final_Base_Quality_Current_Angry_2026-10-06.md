# 스튜디오 최종 원화 변환 quality·현행 ANGRY 실험

관측일·기록일: 2026-10-06. [비교 화면](http://127.0.0.1:18769/round4/index.html) · [이전 최적화](32_Studio_GPT_Optimization_Dynamic_Expressions_2026-10-06.md).

**이후 최종 결정(2026-10-06):** 사용자는 원화→neutral의 기존 Qwen+후처리를 유지하고, 감정 파생만 Sunburst Low/사용자 리롤 High로 확정했다. 표정은 절제하고 모션은 현행 수준을 허용한다. 아래 원화 High·작은 동작 권고는 실험 직후의 잠정 의견이다. 구현·검증·배포 현황은 [릴리스 정본](34_Studio_Dynamic_Expressions_Release_2026-10-06.md)을 따른다.

## 사용자 결정·범위

사용자가 **감정 파생 Sunburst+Low를 채택**했다. 원화→neutral은 픽셀 변화가 커 High 사용을 제안하고, 최종 선택 전 Max/High/Medium/Low 비교를 요청했다. 현행 비절제 감정 문구는 Low로 한 번 확인한다. 파라미터 확정 후 동적 바리에이션과 워크플로 교체를 함께 구현하고 TTS로 이어간다.

프로덕션 UGC 연(character18/job12)의 실제 `selectedGoldenShotKey` 원화를 읽기 전용으로 확보했다. 원화1024² RGB SHA `abe74587f1355b9042ee48ee4a17a06994819552cd55991cbdc88cdd16c82528`. 기존 neutral SHA/structuredConcept/프로덕션 prompt class는 이전 fixture와 일치했다. **원화4장 + 현행 ANGRY1장 = 신규5**이며 다른 감정·리롤은 없다.

모두 `openai/gpt-image-2.5/sunburst/edit`, num_images1,1024²,PNG/opaque. 원화4장은 동일 source/prompt와 quality만 바뀐다. 실제 neutral 변환 목표는 현행 Qwen의 자세·구도/배경·조명 2패스를 **한 GPT 편집 지시로 통합**했다. 기존 ‘배경 유지’/‘같은 자세’ 상충 지시는 제외했다. 따라서 기존 neutral(Qwen2패스+WF-2/Face)은 공정이 다른 참고다.

현행 ANGRY는 **이전 A_angry의 prompt를 바이트 수준으로 그대로** 사용하고, 이전 A/H와 같은 기존 neutral을 입력했다. 새 neutral을 emotion 입력으로 쓰지 않았다. 비교 화면은 기존 Qwen A angry/절제 Sunburst Low H angry raw를 재사용한다.

`max` 지원은 [OpenAI 공식 image generation](https://developers.openai.com/api/docs/guides/image-generation)과 [fal Sunburst edit schema](https://fal.ai/models/openai/gpt-image-2.5/sunburst/edit/api)의 enum으로 확인했다. 다른 quality나 모델로 대체하지 않았다.

## 결과·시간·비용

| 신규 조건 | 종단 시간 | 공급자 inference | 장당 공개 편집 추정 |
|---|---:|---:|---:|
| L 원화→neutral Max | 101.94초 | 85.56초 | 미확인 |
| M 원화→neutral High | 44.73초 | 31.59초 | 약$0.0610 |
| N 원화→neutral Medium | 33.57초 | 19.99초 | 약$0.0215 |
| O 원화→neutral Low | 33.59초 | 17.88초 | 약$0.0142 |
| P neutral→현행 ANGRY Low | 26.53초 | 15.51초 | 약$0.0142 |

동시5 배치 **101.951초(1분42초)**. 종단은 POST→poll/download/검증 포함이며 이번 턴 전체 시간이 아니다. 조건당1장이므로 quality의 보편 속도/우위를 확정하지 않는다. [fal 가격표](https://fal.ai/gpt-image-2.5)는 Max 실측 가격을 제공하지 않아 **Max 제외4장 약$0.1109+prompt 추가분**만 계산했다. 전체실험비·Max 가격·계정 실청구/토큰 usage는 미확인이다.

- 원화4장 모두 배경 풍경·무기를 제거하고 중립 스탠딩으로 전환했다. Low/Medium도 주요 변환을 수행했고 전반적 붕괴는 없었다. High는 얼굴·머리·옷 주름의 표현이 안정적이고 원화 변환의 보수적 기본값 후보다.
- Max는 허리 장식 등 세부가 풍부하지만 얼굴/자세가 High보다 반드시 원화에 가깝지는 않고 지연이 2배 이상이었다. 추가 비용을 정당화하는 일관된 개선은 이 한 장에서 입증되지 않았다.
- **공통 잔여:** 신규4는 손가락까지 검은 장갑으로 덮여 기존 neutral의 노출 손가락과 다르며 가슴/소매 문양·일부 장식도 재해석됐다. 원화 자체의 손가락 피복/피부 경계는 짙은 명암 때문에 독립 검토에서 명확히 확정하지 못했으므로 원화 대비 장갑 변경으로 단정하지 않는다. 평평한 회색을 지시했지만 미세한 배경 그라데이션도 남았다. 높은 quality만으로 외형의 완전 보존을 보장하지 않는다. 중요 복장 디테일 보존과 최종 cutout 확인이 필요하다.
- **현행 ANGRY P:** 어깨 높이 주먹·단단한 다른 팔·노출된 이를 가진 강한 표정이 생성됐다. 현행 문구 자체가 ‘shoulder-height fist’와 ‘teeth clenched/hard sneer’를 요청한다. GPT는 이를 따르면서 얼굴 인상은 비교적 잘 유지했다. 모델 교체만으로 과한 연출이 없어지지는 않으므로 이미지용 표정 강도·작은 제스처 조정은 유지할 근거가 있다. 기존 H는 낮은 손·닫힌 입이다.

**권고:** 원화→neutral Sunburst High, neutral→동적 감정 Sunburst Low. 감정마다 캐릭터의 성격/선택 조건에 맞는 짧은 표정과 적당한 작은 동작을 만들고, 전체 감정의 자세를 고정하지 않는다. Max 기본 사용 근거는 부족하다. 감정 Low는 사용자 확정이며 베이스 High/최종 프롬프트 채택은 이번 결과에 대한 최종 평가와 구별한다.

## 무결성·구현 전 남은 범위

신규5 고유 요청/DONE5, 입력pairing 원화4+기존neutral1, 실제 요청SHA/입력bytes SHA/출력1024² RGB·PNG/산출SHA를 대조했다. 원장 오류·미확정·리롤0. RunPod·WF-2·FaceDetailer·WF-3·DB/에너지/자산 쓰기0. 기존 실험 원장/산출을 재접수하거나 덮지 않았다.

별도 컨텍스트 사전 검토는 정상5 동시 mock POST5/DONE5·DONE 재개 POST0, 두 입력 pairing·경로/SHA/추가/중복/quality/prompt/model/상한/동시성 실패 차단, HTTP409 1회/UNCERTAIN 재개 POST0을 확인했다. 이 mock은 실제 네트워크/파일쓰기/키조회 없이 수행했고 실제 공급자 정상5와 구별한다. 같은 프로세스에서 입력 그룹마다 PRIVATE를 바꾸지 않고 고정 PRIVATE+샘플별 승인 파일/SHA로 구현했다.

WF-3 실제 cutout/머리·손·흰 소매 alpha 경계, 앱 표시와 제품 통합 테스트는 미실행이다. 새로운 제품 UI는 목업 검토 후 구현한다. 이번 턴 제품 코드·커밋/푸시/배포는 없다. 동적 catalog의 안정ID/선택조건/의미감정/revision·화자별 LLM 허용목록·완성 집합/리롤/심사/publish/FE·레거시 호환 구조는 docs/32를 따른다. 이후 TTS 계약도 유지한다.

별도 최종 감사도 실제 요청5/DONE5·원화4+neutral1 pairing·PNG/RGB/1024²·출력SHA5고유·POST증거5/오류0·RunPod원장0을 확인했다. 브라우저 전신/얼굴 각10이미지 로딩·원화와High 확대 비교·Escape 닫기·가로 넘침 없음 확인. 공개 도구/README/정본·gallery 텍스트20개 API credential 값 일치0, gallery 원본10 SHA 일치 확인. HTTP root는 기존 credential 없는 review만 유지했다.

독립 최종 시각 검토는 High를 베이스의 잠정 기본값으로 타당하게 평가했다. Max의 머릿결/장식 세부와 Low의 문양 단순화 차이를 관측했으며 Max의 필수 우위를 확정하지 않았다. 갤러리10 SHA를 multiset으로 대조해 동일 neutral2개의 의도적 복사도 구별했다. 지출 추가·네트워크·파일/키 변경 없이 수행했다.

[도구](../tools/studio-bakeoff/README.md) · [audit](../tools/studio-bakeoff/.local/yeon-20261006-round4/final-results-audit.json) · [원화 전신 비교](../tools/studio-bakeoff/.local/yeon-20261006/review/round4/base_full.jpg) · [ANGRY 전신 비교](../tools/studio-bakeoff/.local/yeon-20261006/review/round4/angry_full.jpg).
