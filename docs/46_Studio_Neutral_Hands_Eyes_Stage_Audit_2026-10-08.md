# 원화→neutral 손·눈 품질 단계 분석 — 2026-10-08

## 요청과 범위

출처: 사용자 최종 검증에서 원화 품질은 충분하다고 평가했고, neutral 변환의 손 뭉개짐·원화보다 과한 눈 화장이 리롤로 해결되지 않아 Qwen과 WF-2/FaceDetailer 중 발생 단계를 확인해 달라고 요청했다. 이번 작업은 단계별 진단이며 제품 코드/배포·캐릭터 교체·에너지/DB·RunPod 설정 변경은 없다. 과거 실험 승인을 승계하지 않고 이번 검증에 필요한 최소 1회 RunPod 진단을 실행했다.

## 관측과 자료

운영 최근 작업6개를 읽기 조회해 최신 완료 작업30 ‘강세라’를 특정했다. 2026-10-08 00:06 KST 관측에서 READY·neutral 후보12개였고 실제 선택 후보는1이다. 선택 원화는 원화 배열index4의 배치 스냅샷과 대응한다. 저장된 Qwen 패스2 이미지12·기존 WF2/Face 최종12·선택 원화1, 총25PNG를 확보했다. 모두1024²이며 파일/RGB SHA·12후보 키 매칭에 누락/중복은 없다. 기존 PNG에는 workflow/seed 메타데이터가 없다.

[단계 비교 화면](http://127.0.0.1:18769/round6-neutral/index.html)은 원화·Qwen·WF2·Face4단계, 실제 선택된 기존 후보1, 리롤12쌍과 얼굴/손 확대를 제공한다. 화면은 credential 없는 기존 review root 아래에만 제공한다. 원본 DB·로그·provider ID/URL·요청 payload·키는 Git 제외 `tools/tts/.local/neutral46/`에 보존한다.

Qwen은 원화→자세/프레이밍 변경→배경/조명 변경의2패스다. 이번 base_pose는 허리에 한 손·다른 팔은 앞으로 가리키기다. 패스2에서는 원화보다 얼굴이 단순화되고 표정이 약해졌으나 검지와 접힌 손이 비교적 구분된다. 이후 최종본 여러 후보에서 손 자세/손가락·손톱·장신구와 의상 재질까지 재구성되고, 12쌍의 최종 눈선·속눈썹·미소가 반복적으로 강해진다. 모든 후보의 손을 동일한 실패로 분류하거나 Qwen이 원화를 완전히 보존했다고 표현하지 않는다.

## WF2와 Face 단계 분리

실제 선택된 후보1의 저장 Qwen 패스2 PNG를 입력으로, 운영 WF2 template와 현행 assembler positive/Face wildcard를 그대로 사용하는 단일 진단을 수행했다. 새 고정 sampler/detailer seed260100801/260100802이며 과거 후보의 정확 재현이 아니다. 변경은 입력·seed·출력 prefix·SaveImage80 추가뿐이다. node80은 VAEDecode8(WF2 전역 sampler 직후), 기존 SaveImage9는 FaceDetailer17 직후를 저장하므로 두 출력은 같은 전역 샘플링 결과의 앞뒤다. 운영 template==소스 전체 그래프·generation override 없음·입력/요청 SHA·node경로를 확인했다.

RunPod 신규 POST1/DONE1·출력2, Qwen/LLM 새 POST0. unknown 제출은 자동 재POST하지 않는 별도 원장으로 관리했다. 큐18.735초·실행23.780초·종단47.312초. health completed1295→1296(+1), failed9/retried2 유지·최종queue/progress0. GPU 실청구는 확인하지 않았다.

- **손:** Face 이전 WF2 결과에서 이미 Qwen의 가리키는 손을 다른 손가락 배치/큰 손톱으로 바꿨다. 허리손·체인·의상 광택도 함께 변했다. Face 전후 앞손ROI(x315–445,y175–320)·허리손ROI(x545–635,y365–440)의 RGB는 각각 완전히 동일하다.
- **눈/인상:** WF2에서 먼저 강한 눈 색·두꺼운 눈선·미소가 나타난다. Face는 흐릿한 눈·눈꺼풀 연결을 더 정리하고 동공/속눈썹·입을 추가로 바꾼다. before→after 변경13,623픽셀의 경계는 x460–589,y107–215 얼굴 주변뿐이다. Face가 모든 문제를 처음 만든다는 결론은 이 관측과 맞지 않는다.

## 프롬프트·설정 가설

WF2 전역 denoise0.4, Face denoise0.4, WAI+detail LoRA model0.6/clip1이다. 단순 이미지 보정이 아니라 텍스트 조건으로 원본을 다시 그리는 공정이다. 이번 진단은 두 단계의 몫을 분리했지만 denoise·LoRA·개별 태그의 기여를 각각 분리한 실험은 아니다.

완성형 원화 positive 자체에 `subtle smoky eye makeup`, `long eyelashes`가 포함됐고, 후속 appearance_tags에 남아 WF2와 Face wildcard에 재주입된다. 원화 픽셀에는 그 요청이 강하게 구현되지 않았어도 후처리는 태그를 다시 실현하려 한다는 가설이다. Face wildcard는 `detailed beautiful eyes`·눈 관련 태그에 persona 전체를 더한다. neutral에도 `smug grin`, `raised eyebrow`, `condescending gaze`, `looking down at viewer` 등과 `neutral expression`이 함께 전달된다. 손 관련 `manicured nails`, `fashionable nail art`, `statement rings`도 WF2에 남는다. 원화의 표현을 재현하기보다 요청 태그를 다시 충족시키는 압력이 생길 수 있다.

원화 프롬프트 품질을 다시 제한하기보다 neutral 후처리의 역할을 줄이는 것이 우선이다. 후보는 (1) Qwen 패스2 직결+기존 누끼, (2) WF2 전역 denoise 축소(예:0.15–0.2)·원화용 persona 재주입 분리·Face 제외, (3) 필요할 때만 낮은 denoise의 최소 얼굴 보정이다. 이 수치/구성의 품질 검증이나 채택은 아직 하지 않았다. 원화 생성과 감정 Sunburst 파이프라인은 이번 분석에서 변경하지 않는다.

## 독립 검토와 한계

`studio_preflight` 별도 컨텍스트가 원화/기존4쌍·신규2PNG·코드와 원장을 읽기 검토했다. 기존25파일 SHA/RGB SHA·배치스냅샷·12후보 매칭, 입력base64/SHA·assembler 재조립·10개 계약 대조·1POST/앞뒤동일샘플 경계를 확인했다. 신규 출력의 손/눈 변화와 Face 변경영역도 독립 확인했다. 결론을 12장 전체 원인 비율이나 다른 캐릭터로 일반화하지 않는다.

Qwen 패스1 원본은 이번에 확보하지 않아 Qwen2패스 각각의 스타일 변화는 분리하지 않았다. 과거 WF2/detailer seed는 저장되지 않아 기존 후보1의 정확한 같은seed 재현도 아니다. 전구간 원인 중 이번 손·눈 증상이 후처리에서 강화된다는 직접 근거와, 구체 태그/denoise 상호작용의 추정을 구별한다. 운영 새 구성의 품질·비용/지연 개선, 실제 앱 빌드 변경은 미실행이다.

증빙: [단계 진단 요약](46_assets/stage-audit-summary.json). 작업 기록일/사용자 평가일2026-10-08, 원화 배치와 provider UTC timestamp는2026-10-07로 보존한다. 이번 커밋·푸시·배포는 수행하지 않았다.

## 2026-10-08 사용자 후속 결정

사용자가 수작업 공정의 목적을 추가 설명했다. WF-2는 i2i로 달라진 그림체·질감을 원래 모델로 복원하는 역할이며, 뒤에 Face와 Hand 보정을 함께 두려는 의도다. 따라서 위의 WF-2 제거 후보 제안은 후속 구현의 기준으로 사용하지 않는다. WF-2 유지·neutral Face 기본 태그 분리·Hand 추가 변경안의 구현과 검증은 [docs/47](47_Studio_Neutral_Face_Hand_Restoration_2026-10-08.md)에 기록한다. 앞의 단계별 관측은 그대로 유효하며, 새 미관 개선 여부는 별도 샘플로 판단한다.
