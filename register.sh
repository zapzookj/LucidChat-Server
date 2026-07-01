#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
#  WAI-illustrious-SDXL → ModelsLab 1회 등록 스크립트 (옵션 A)
# ═══════════════════════════════════════════════════════════════════
#
#  [원인] text2img의 model_id에는 "이미 ModelsLab 워크스페이스에 로드된
#         모델의 별칭"만 들어간다. CivitAI 다운로드 URL을 직접 넣으면
#         "Model not found" 발생. → Load Model V2로 *먼저 등록*해야 함.
#
#  [절차] 이 스크립트를 운영 배포와 무관하게 1회만 실행한다.
#         등록은 영속적이라 이후 모든 text2img 호출이 별칭을 재사용한다.
#
#  [사용]
#    export MODELSLAB_API_KEY="여기에_엔터프라이즈_키"
#    bash register_wai_model.sh
#
#  ───────────────────────────────────────────────────────────────
#  ⚠️ CivitAI 버전 ID 확정 필요
#  ───────────────────────────────────────────────────────────────
#  WAI-illustrious-SDXL(모델 827184)은 버전이 계속 갱신된다.
#  반드시 *특정 버전*의 다운로드 URL로 고정할 것 (재현성/일관성).
#
#  버전 ID 확인 방법:
#    1) https://civitai.com/models/827184 접속
#    2) 원하는 버전 선택 (예: v15.0)
#    3) Download 버튼 우클릭 → 링크 복사
#       형식: https://civitai.com/api/download/models/<VERSION_ID>
#
#  알려진 버전 ID 예시 (확인 후 사용):
#    v15.0 → 2167369   (검색 시점 기준, 반드시 직접 재확인)
#  → 아래 WAI_VERSION_ID를 확정 값으로 교체.
#
#  ⚠️ CivitAI 인증
#  일부 모델/버전은 다운로드에 CivitAI 토큰이 필요할 수 있다.
#  필요 시 URL 뒤에 ?token=<CIVITAI_API_TOKEN> 를 붙인다.
#  (CivitAI → Manage Account → API Keys 에서 발급)
# ═══════════════════════════════════════════════════════════════════
MODELSLAB_API_KEY="3QFHleWPDWalClrjQyQDFenHm3JO0ofzOTTR9RXVyiwROxKHs6PXcrqtabxo"

set -euo pipefail

: "${MODELSLAB_API_KEY:?MODELSLAB_API_KEY 환경변수를 먼저 export 하세요}"

# ── 확정 필요 값 ─────────────────────────────────────────────────
WAI_VERSION_ID="2883731"                       # ⚠️ 직접 재확인 후 교체
CIVITAI_TOKEN="${CIVITAI_API_TOKEN:-}"         # 필요 시에만 (없으면 무시)
MODEL_ALIAS="wai-illustrious-sdxl"             # text2img에서 쓸 별칭 (yml에 동일하게)
# ────────────────────────────────────────────────────────────────

DOWNLOAD_URL="https://civitai.red/api/download/models/2883731?type=Model&format=SafeTensor&size=pruned&fp=fp16"
if [ -n "${CIVITAI_TOKEN}" ]; then
  DOWNLOAD_URL="${DOWNLOAD_URL}?token=${CIVITAI_TOKEN}"
fi

echo "▶ Registering WAI-illustrious-SDXL to ModelsLab..."
echo "  alias        : ${MODEL_ALIAS}"
echo "  version id   : ${WAI_VERSION_ID}"
echo "  category     : stable_diffusion_xl (WAI는 Illustrious=SDXL 베이스)"
echo ""

# ── 1) Load Model V2 ────────────────────────────────────────────
LOAD_RESPONSE=$(curl -s --request POST \
  'https://modelslab.com/api/v1/enterprise/load_model_v2' \
  --header 'Content-Type: application/json' \
  --data @- <<EOF
{
  "key": "${MODELSLAB_API_KEY}",
  "url": "${DOWNLOAD_URL}",
  "model_id": "${MODEL_ALIAS}",
  "model_category": "stable_diffusion_xl",
  "model_format": "safetensors",
  "revision": "fp16"
}
EOF
)

echo "── Load Model V2 응답 ──"
echo "${LOAD_RESPONSE}"
echo ""

# ── 2) Verify Model (로드 완료 확인) ────────────────────────────
# 모델 로딩은 비동기일 수 있다(수십 초~수 분, 모델 크기 ~6GB).
# 아래를 반복 실행하여 status가 사용 가능 상태가 될 때까지 확인.
echo "── Verify Model (로드 상태 확인) ──"
curl -s --request POST \
  'https://modelslab.com/api/v1/enterprise/verify_model' \
  --header 'Content-Type: application/json' \
  --data @- <<EOF
{
  "key": "${MODELSLAB_API_KEY}",
  "model_id": "${MODEL_ALIAS}"
}
EOF
echo ""
echo ""
echo "✅ 등록 요청 완료."
echo "   - 응답 status가 'success'/'loading'이면 정상 (대형 모델은 로딩에 시간 소요)."
echo "   - verify_model을 수 분 간격으로 재실행하여 사용 가능해질 때까지 확인."
echo "   - 사용 가능해지면 application.yml의 modelslab.default-model-id 를"
echo "     '${MODEL_ALIAS}' 로 설정하고 앱을 재기동하세요."
echo ""
echo "   참고: 등록은 영속적이라 1회만 하면 됨. 이후 코드 변경 불필요."