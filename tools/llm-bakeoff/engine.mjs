import { createHash, randomUUID, randomInt } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync, renameSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

export const API = 'https://openrouter.ai/api/v1';
export const MODELS = [
  ['google/gemini-3-flash-preview', 'Gemini 3 Flash · 기준', ['default', 'minimal', 'low'], 'google-ai-studio'],
  ['aion-labs/aion-3.0-mini', 'Aion 3.0 Mini', ['default'], 'aion-labs'],
  ['google/gemini-3.5-flash-lite', 'Gemini 3.5 Flash-Lite', ['default', 'minimal', 'low'], 'google-ai-studio'],
  ['google/gemini-3.8-flash', 'Gemini 3.8 Flash', ['default', 'minimal', 'low']],
  ['openai/gpt-5.6-luna', 'GPT 5.6 Luna', ['default', 'none', 'low']],
  ['deepseek/deepseek-v4.1-flash', 'DeepSeek V4.1 Flash', ['default']],
  ['minimax/minimax-m3', 'MiniMax M3', ['default']],
  ['inception/mercury-2.5', 'Mercury 2.5', ['default']]
].map(([id, name, reasoning, preferredProvider]) => ({ id, name, reasoning, preferredProvider }));
export const hash = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
export const BASELINE_PROMPT = 'P0-service-fixed-state-v1';
export const PROMPT_VARIANTS = [
  { id: BASELINE_PROMPT, label: 'P0 · 수리 전 서비스 (2026-09-18)', parent: null, description: '2026-09-18 수리 전 시스템 메시지를 보존한 역사적 기준. 로제타·일반 모드·고정 상태 전용입니다.' },
  { id: 'C1-repairs-v1', label: 'C1 · 결함 수리', parent: BASELINE_PROMPT, description: '외형 누락, 행동/대사 해석 충돌, 스토리 배경 중복을 수리하는 비교실 전용 후보.' },
  { id: 'S1-character-v1', label: 'S1 · 인물 구조', parent: 'C1-repairs-v1', description: 'C1에 로제타의 동기·관계·상황별 표현 연결과 공통 인격 예문 정리를 적용한 후보.' },
  { id: 'E1-examples-v1', label: 'E1 · 한국어 예시', parent: 'S1-character-v1', description: 'S1에 제작자 검수 전 한국어 행동 예시를 더한 후보. 예시는 실제 기억이 아닙니다.' },
  { id: 'F2-appearance-v1', label: 'F2 · 외형만 보완', parent: BASELINE_PROMPT, description: '원본에 공식 외형·기본 의복 정보만 추가한 분리 대조군.' },
  { id: 'A2-facts-v1', label: 'A2 · 사실과 주장', parent: 'F2-appearance-v1', description: 'F2에 확정 설정·서버 상태와 사용자 주장을 구분하는 지시를 적용.' },
  { id: 'U2-agency-v1', label: 'U2 · 사용자 선택권', parent: 'F2-appearance-v1', description: 'F2에 행동·대사 해석 수리와 미실행 사용자 행동 보존을 적용.' },
  { id: 'T2-turn-v1', label: 'T2 · 현재 발화 응답', parent: 'F2-appearance-v1', description: 'F2에 현재 발화 의도와 맥락 적용 지시를 더하되 인물의 자율성을 유지.' },
  { id: 'J2-json-v1', label: 'J2 · JSON 예시', parent: 'F2-appearance-v1', description: 'F2의 출력 구획에서 유효 JSON 예시와 필드 설명을 분리.' },
  { id: 'R2-combined-v1', label: 'R2 · 개선 조합', parent: 'F2-appearance-v1', description: 'A2·U2·T2·J2의 변경을 조합한 후속 실험 후보. 운영 미반영.' },
  { id: 'D3-turn-boundary-v1', label: 'D3 · 한 턴의 씬 경계', parent: 'R2-combined-v1', description: 'R2의 씬 구성 지시를 한 번의 사용자 입력에 대한 반응으로 재정리. 다음 사용자 행동 전까지 장면을 나누는 후보.' },
  { id: 'K4-current-context-v1', label: 'K4 · 현재 턴 인용 컨텍스트', parent: 'J2-json-v1', description: 'J2에 최근 사용자 원문 3개와 현재 입력을 구분한 인용 구획 추가. 수동 해석·정답·추가 모델 호출 없이 출처와 현재 턴을 강조.' },
  { id: 'P1-service-repairs-v1', label: 'P1 · 현재 서비스 · 정합 수리', parent: BASELINE_PROMPT, description: '수리한 운영 assembler의 실제 출력. 외형·행동 해석·중복·사용자 내면 지시를 정리하고 전 인물용 유효 JSON 예시와 필드 설명을 분리. 대사 품질 상승을 입증한 후보는 아닙니다.' }
];
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
const stableHash = value => hash(canonical(value));
function comparisonKind(input) {
  const comparison = input.comparison ?? 'model';
  if (!['model', 'prompt'].includes(comparison)) throw new Error('Invalid comparison type');
  return comparison;
}
function validatePromptCandidates(input) {
  if (!Array.isArray(input.candidates) || input.candidates.length !== 3) throw new Error('프롬프트 비교에는 후보 세 개가 필요합니다.');
  const first = input.candidates[0];
  if (!first || typeof first.provider !== 'string' || !first.provider || typeof first.reasoning !== 'string') throw new Error('Invalid prompt comparison settings');
  for (const candidate of input.candidates) {
    if (!candidate || candidate.model !== 'google/gemini-3-flash-preview' || candidate.provider !== first.provider || candidate.reasoning !== first.reasoning)
      throw new Error('프롬프트 비교는 Gemini 3 Flash와 동일한 provider·추론 설정을 사용해야 합니다.');
    if (!PROMPT_VARIANTS.some(v => v.id === candidate.promptVersion)) throw new Error('Unknown prompt version');
  }
}

// Assemble on one serial bridge, then verify shared facts before any reservation or inference.
export async function prepareComparison(bridge, input) {
  const comparison = comparisonKind(input);
  if (comparison === 'prompt') validatePromptCandidates(input);
  else if ((input.promptVersion && input.promptVersion !== BASELINE_PROMPT) || input.candidates?.some(c => c.promptVersion && c.promptVersion !== BASELINE_PROMPT))
    throw new Error('모델 비교는 P0 원본만 사용합니다. 개선안은 프롬프트 비교를 선택하세요.');
  const baseline = await bridge.call('prepare', { ...input, promptVersion: BASELINE_PROMPT });
  if (comparison === 'model') return baseline;
  const shared = p => ({ mode: p.mode, fixture: p.fixture, fixtureProfile: p.fixtureProfile, maxTokens: p.maxTokens,
    historyLogs: p.historyLogs, stateFrozen: p.stateFrozen, dialogue: p.messages.filter(m => m.role !== 'system') });
  const sharedHash = stableHash(shared(baseline));
  const snapshotHash = stableHash({ baseline, input: input.input, history: input.history ?? [] });
  const assembled = new Map([[BASELINE_PROMPT, baseline]]), variants = [];
  for (const candidate of input.candidates) {
    if (!assembled.has(candidate.promptVersion)) assembled.set(candidate.promptVersion,
      await bridge.call('prepare', { ...input, promptVersion: candidate.promptVersion }));
    const p = assembled.get(candidate.promptVersion);
    if (p.promptVersion !== candidate.promptVersion || stableHash(shared(p)) !== sharedHash)
      throw new Error('후보의 프롬프트 버전 또는 고정 상태·대화 기록이 일치하지 않습니다.');
    const definition = PROMPT_VARIANTS.find(v => v.id === candidate.promptVersion);
    variants.push({ ...p, promptLabel: definition.label, parentPromptVersion: definition.parent, promptHash: hash(p.messages), snapshotHash });
  }
  return { comparison, snapshotHash, variants };
}
const finite = n => typeof n === 'number' && Number.isFinite(n) && n >= 0;

export function cleanError(error, key = '') {
  let text = String(error?.message ?? error ?? 'Unknown error');
  if (key) text = text.split(key).join('[REDACTED]');
  return text.replace(/Bearer\s+\S+/gi, 'Bearer [REDACTED]').replace(/sk-or-[\w-]+/g, '[REDACTED]').slice(0, 500);
}

export class Ledger {
  constructor(directory, limit = 5) {
    if (!finite(limit) || limit <= 0 || limit > 100) throw new Error('Budget must be >0 and <=100 USD');
    mkdirSync(directory, { recursive: true }); this.file = join(directory, 'budget.json'); this.limit = limit;
    this.entries = existsSync(this.file) ? JSON.parse(readFileSync(this.file, 'utf8')).entries : [];
    if (!Array.isArray(this.entries) || this.entries.some(e => !finite(e.reserved) || (e.actual !== null && !finite(e.actual)))) throw new Error('Invalid billing ledger; inspect the file before restarting');
  }
  snapshot() {
    const known = this.entries.reduce((n, e) => n + (e.actual ?? 0), 0);
    const held = this.entries.reduce((n, e) => n + (e.actual === null ? e.reserved : 0), 0);
    return { limit: this.limit, known, held, available: Math.max(0, this.limit - known - held) };
  }
  save() { const temp = this.file + '.tmp'; writeFileSync(temp, JSON.stringify({ entries: this.entries }, null, 2)); renameSync(temp, this.file); }
  reserve(runId, amounts) {
    if (amounts.some(n => !finite(n)) || amounts.reduce((a,b) => a+b,0) > this.snapshot().available + 1e-10) throw new Error('실험 예산 부족: 비용이 미확정인 호출의 예약액도 유지됩니다.');
    const entries = amounts.map(reserved => ({ id: randomUUID(), runId, reserved, actual: null, status: 'reserved', at: new Date().toISOString() }));
    this.entries.push(...entries); this.save(); return entries;
  }
  settle(id, actual, status) {
    const e = this.entries.find(e => e.id === id); if (!e) throw new Error('Missing reservation');
    e.actual = finite(actual) ? actual : null; e.status = status; this.save();
  }
}

export class Catalog {
  constructor(fetcher = fetch) { this.fetcher = fetcher; }
  async model(id) {
    const def = MODELS.find(m => m.id === id); if (!def) throw new Error('Model not allowed');
    const response = await this.fetcher(`${API}/models/${id}/endpoints`, { redirect: 'error', signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw new Error(`Catalog unavailable (${response.status})`);
    const { data } = await response.json();
    const endpoints = (data?.endpoints ?? []).filter(e => {
      const p = Number(e.pricing?.prompt), c = Number(e.pricing?.completion), request = Number(e.pricing?.request ?? 0);
      return e.status === 0 && typeof e.tag === 'string' && /^[\w./-]+$/.test(e.tag) && p > 0 && p <= 1e-6 && c > 0 && c <= 5e-6 && request === 0 &&
        Number(e.context_length) > 0 && Number(e.context_length) <= 2000000 && e.supported_parameters?.includes('response_format');
    }).map(e => ({ tag: e.tag, name: e.provider_name, input: Number(e.pricing.prompt)*1e6, output: Number(e.pricing.completion)*1e6,
      context: Number(e.context_length), maxOutput: e.max_completion_tokens, parameters: e.supported_parameters,
      tier: /\/(flex|priority|fast)$/.exec(e.tag)?.[1] ?? 'default', observedAt: new Date().toISOString() }));
    return { ...def, endpoints };
  }
  async all() { return Promise.all(MODELS.map(async m => { try { return await this.model(m.id); } catch { return { ...m, endpoints: [], error: '카탈로그 조회 실패' }; } })); }
}

// Incremental, quote-aware extraction; waits for an entire first scene JSON object.
export function firstScene(text) {
  const key = /"scenes"\s*:\s*\[\s*\{/.exec(text);
  if (!key) return null;
  const start = key.index + key[0].length - 1;
  let depth = 0, quoted = false, escaped = false;
  for (let i = start; i < text.length; i++) {
    const c = text[i];
    if (escaped) { escaped = false; continue; }
    if (c === '\\' && quoted) { escaped = true; continue; }
    if (c === '"') { quoted = !quoted; continue; }
    if (quoted) continue;
    if (c === '{') depth++;
    if (c === '}' && --depth === 0) {
      try { const s = JSON.parse(text.slice(start,i+1)); return typeof s.narration === 'string' && typeof s.dialogue === 'string' && (s.narration.trim() || s.dialogue.trim()) ? s : null; } catch { return null; }
    }
  }
  return null;
}

export async function readSse(body, onFrame) {
  const decoder = new TextDecoder(); let pending = '', data = [], total = 0;
  const line = value => {
    if (value === '') { if (data.length) { onFrame(data.join('\n')); data = []; } }
    else if (value.startsWith('data:')) data.push(value.slice(5).replace(/^ /, ''));
  };
  for await (const chunk of body) {
    total += chunk.length; if (total > 2000000) throw new Error('Stream exceeded 2MB');
    pending += decoder.decode(chunk, { stream: true });
    let end; while ((end = pending.indexOf('\n')) !== -1) { line(pending.slice(0,end).replace(/\r$/, '')); pending = pending.slice(end+1); }
  }
  pending += decoder.decode(); if (pending) line(pending.replace(/\r$/, '')); line('');
}

export function requestFor(prepared, candidate, endpoint) {
  const def = MODELS.find(m => m.id === candidate.model);
  if (!def || !def.reasoning.includes(candidate.reasoning)) throw new Error('Unsupported reasoning selection');
  if (!endpoint || endpoint.tag !== candidate.provider) throw new Error('Provider unavailable or exceeds $1/$5 cap');
  if (endpoint.maxOutput != null && endpoint.maxOutput < prepared.maxTokens) throw new Error('Provider output limit is below the service contract');
  const body = { model: candidate.model, messages: prepared.messages, stream: true, max_tokens: prepared.maxTokens,
    response_format: { type: 'json_object' }, provider: { only: [endpoint.tag], allow_fallbacks: false, require_parameters: true, max_price: { prompt: 1, completion: 5 } } };
  const omitted = [];
  for (const [key, value] of Object.entries({ temperature: 0.8, frequency_penalty: 0.3, presence_penalty: 0.15 })) {
    endpoint.parameters.includes(key) ? body[key] = value : omitted.push(key);
  }
  if (candidate.reasoning !== 'default') {
    if (!endpoint.parameters.includes('reasoning')) throw new Error('Provider does not support reasoning control');
    body.reasoning = { effort: candidate.reasoning, exclude: true };
  }
  return { body, omitted };
}

export class Engine {
  constructor({ bridge, catalog, ledger, directory, apiKey = '', fetcher = fetch, sourceVersion = 'unknown' }) {
    Object.assign(this, { bridge, catalog, ledger, directory, apiKey, fetcher, sourceVersion }); this.busy = false;
  }
  async run(input, emit = () => {}, signal = new AbortController().signal) {
    if (this.busy) throw new Error('이미 비교가 실행 중입니다. 완료하거나 중단하세요.');
    if (!this.apiKey) throw new Error('OPENROUTER_API_KEY가 서버 환경에 없습니다.');
    if (!['SANDBOX','STORY'].includes(input.mode) || !Array.isArray(input.candidates) || input.candidates.length !== 3 || !Number.isInteger(input.timeoutMs) || input.timeoutMs < 5000 || input.timeoutMs > 120000) throw new Error('Invalid run settings');
    this.busy = true;
    // A disconnected observer must not release the concurrency lock while upstream jobs run.
    const notify = event => { try { emit(event); } catch {} };
    try {
      const comparison = comparisonKind(input), preview = await prepareComparison(this.bridge, input);
      const preparedVariants = comparison === 'prompt' ? preview.variants : input.candidates.map(() => preview);
      const prepared = preparedVariants[0];
      for (const p of preparedVariants) if (Buffer.byteLength(JSON.stringify(p.messages),'utf8') > 100000) throw new Error('입력 100KB 제한을 넘었습니다. 새 대화로 비교하세요.');
      const catalogs = new Map();
      for (const candidate of input.candidates) if (!catalogs.has(candidate.model)) catalogs.set(candidate.model, this.catalog.model(candidate.model));
      const conditions = await Promise.all(input.candidates.map(async (candidate, index) => {
        const model = await catalogs.get(candidate.model);
        const endpoint = model.endpoints.find(e => e.tag === candidate.provider);
        const p = preparedVariants[index];
        return { candidate, endpoint, prepared: p, ...requestFor(p,candidate,endpoint) };
      }));
      if (comparison === 'prompt') {
        const controls = c => stableHash({ ...c.body, messages: undefined });
        if (conditions.some(c => controls(c) !== controls(conditions[0]))) throw new Error('프롬프트 외 실행 조건이 일치하지 않습니다.');
      }
      if (signal.aborted) throw new Error('Cancelled before dispatch');
      // Conservative reservation: entire provider context at the price cap, plus all output.
      // Does not infer native token counts from characters or treat cancelled work as free.
      const runId = randomUUID(), reservations = this.ledger.reserve(runId, conditions.map(c => c.endpoint.context/1e6 + c.prepared.maxTokens*5/1e6));
      const run = { id: runId, startedAt: new Date().toISOString(), sourceVersion: this.sourceVersion, comparison, prepared, preparedVariants,
        promptHash: hash(prepared.messages), snapshotHash: preview.snapshotHash ?? null,
        dispatchStrategy: 'parallel-randomized-start-order', cacheCondition: 'uncontrolled-observe-usage',
        input: { mode: input.mode, input: input.input, history: input.history ?? [] }, evaluationCase: input.evaluationCase ?? null,
        results: [], ratings: {}, stateFrozen: true };
      const order = [0,1,2]; for (let i = 2; i > 0; i--) { const j = randomInt(i+1); [order[i],order[j]]=[order[j],order[i]]; }
      const dispatch = [0,1,2]; for (let i = 2; i > 0; i--) { const j = randomInt(i+1); [dispatch[i],dispatch[j]]=[dispatch[j],dispatch[i]]; }
      notify({ type: 'start', id: runId, comparison, snapshotHash: run.snapshotHash, preparedVariants, promptHash: run.promptHash, prepared, budget: this.ledger.snapshot() });
      const jobs = dispatch.map((i,dispatchIndex) => this.generate(conditions[i], preparedVariants[i], String.fromCharCode(65+order.indexOf(i)), input.timeoutMs, signal, notify)
        .then(async result => {
          this.ledger.settle(reservations[i].id, result.costUsd, result.status);
          result.reservationUsd = reservations[i].reserved;
          result.dispatchIndex = dispatchIndex;
          run.results.push(result); notify({ type: 'result', result });
        }));
      const settled = await Promise.allSettled(jobs);
      if (settled.some(r => r.status === 'rejected')) throw new Error('결과 저장 오류. 미확정 예약액은 유지됩니다. 로컬 저장 공간을 확인하세요.');
      run.results.sort((a,b) => a.slot.localeCompare(b.slot)); run.completedAt = new Date().toISOString(); run.budget = this.ledger.snapshot();
      writeFileSync(join(this.directory, `${run.id}.json`), JSON.stringify(run,null,2));
      this.lastRun = run; notify({ type: 'complete', run }); return run;
    } finally { this.busy = false; }
  }
  async generate(condition, prepared, slot, timeoutMs, signal, emit) {
    const { candidate, endpoint, body, omitted } = condition;
    const result = { slot, promptVersion: prepared.promptVersion ?? BASELINE_PROMPT, promptLabel: prepared.promptLabel ?? PROMPT_VARIANTS[0].label,
      promptHash: hash(prepared.messages), snapshotHash: prepared.snapshotHash ?? null,
      dispatchedAt: new Date().toISOString(), requestedModel: candidate.model, requestedProvider: endpoint.tag, requestedTier: endpoint.tier, reasoning: candidate.reasoning, endpointSnapshot: { ...endpoint },
      omittedParameters: omitted, request: body, actualModel: null, actualProvider: null, actualTier: null, generationId: null, usage: null, costUsd: null,
      ttftMs: null, ttfsMs: null, totalMs: null, raw: '', status: 'error', validation: null, finishReason: null, error: null };
    const start = performance.now(), controller = new AbortController(); let timeout = false, done = false;
    const cancel = () => controller.abort(); signal.addEventListener('abort', cancel, { once: true }); if (signal.aborted) cancel();
    const timer = setTimeout(() => { timeout = true; controller.abort(); }, timeoutMs);
    try {
      const response = await this.fetcher(`${API}/chat/completions`, { method:'POST', redirect:'error', signal: controller.signal,
        headers: { Authorization: `Bearer ${this.apiKey}`, 'Content-Type':'application/json', 'X-OpenRouter-Title':'Lucid Chat Local Bakeoff' }, body: JSON.stringify(body) });
      result.generationId = response.headers.get('x-generation-id');
      if (!response.ok) throw new Error(`OpenRouter HTTP ${response.status}`);
      if (!response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('Expected an SSE response');
      await readSse(response.body, frame => {
        if (done) throw new Error('Unexpected data after stream completion');
        if (frame === '[DONE]') { done = true; return; }
        const chunk = JSON.parse(frame);
        result.generationId = chunk.id ?? result.generationId;
        result.actualModel = chunk.model ?? result.actualModel; result.actualProvider = chunk.provider ?? result.actualProvider;
        result.actualTier = chunk.service_tier ?? result.actualTier;
        if (chunk.usage) {
          result.usage = chunk.usage;
          if (finite(chunk.usage.cost)) result.costUsd = chunk.usage.cost;
        }
        if (chunk.error) throw new Error(`Upstream error: ${cleanError(chunk.error.message, this.apiKey)}`);
        const choice = chunk.choices?.[0]; result.finishReason = choice?.finish_reason ?? result.finishReason;
        const content = choice?.delta?.content;
        if (typeof content === 'string' && content) {
          if (result.ttftMs === null) result.ttftMs = Math.round(performance.now()-start);
          result.raw += content;
          if (result.raw.length > 200000) throw new Error('Output too large');
          if (result.ttfsMs === null) { const scene = firstScene(result.raw); if (scene) { result.ttfsMs = Math.round(performance.now()-start); emit({ type:'first_scene', slot, scene, ttfsMs:result.ttfsMs }); } }
        }
      });
      result.networkMs = Math.round(performance.now()-start);
      if (!done || result.finishReason !== 'stop') {
        result.status = result.finishReason === 'length' ? 'truncated' : 'incomplete';
      } else {
        try {
          result.validation = await this.bridge.call('validate', { mode: prepared.mode, raw: result.raw });
          result.status = result.validation.valid ? 'ok' : 'invalid';
        } catch (e) {
          result.status = 'invalid'; result.validation = { valid:false,issues:[cleanError(e,this.apiKey)] };
        }
      }
    } catch (e) {
      result.status = timeout ? 'timeout' : signal.aborted ? 'cancelled' : 'error';
      result.error = cleanError(e,this.apiKey);
    } finally {
      clearTimeout(timer); signal.removeEventListener('abort',cancel); controller.abort(); result.totalMs = Math.round(performance.now()-start);
    }
    // Some providers complete and charge after client abort. Preserve the reservation when uncertain.
    if (!done || ['timeout','cancelled','error'].includes(result.status)) { result.reportedPartialCostUsd = result.costUsd; result.costUsd = null; }
    return result;
  }
}
