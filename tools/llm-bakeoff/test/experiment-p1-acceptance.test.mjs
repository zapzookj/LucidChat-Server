import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { hash, Ledger } from '../engine.mjs';
import { budgetedGenerate } from '../experiment.mjs';
import { sourceHash as serverSourceHash } from '../server.mjs';
import {
  sessionId, PRIOR_ANCHOR, FIXTURES, sourceHash, remainingAuthorization, variantsFor,
  validateManifest, validatePriorAnchor, validateResume, expandJobs, validatePhase, assertResult,
} from '../experiment-p1-acceptance.mjs';

const P0 = 'P0-service-fixed-state-v1', P1 = 'P1-service-repairs-v1';
const priorIds = ['prompt-eval-20260918', 'prompt-eval-round2-20260918', 'prompt-eval-round3-20260918', 'prompt-eval-final-20260918'];
const copy = value => structuredClone(value);
const settings = { javaHash: 'frozen-java', model: 'google/gemini-3-flash-preview', provider: 'Google AI Studio', tier: 'default' };
const endpoint = { name: settings.provider, tag: 'google-ai-studio', tier: settings.tier };
const priorEntries = () => priorIds.map((id, i) => ({
  id: `old-${i}`, runId: `${id}/phase1/old-${i}`, reserved: 1.1,
  actual: [0.5275483500000001, 0.5033124833333333, 0.11805078333333333, 0.32467311666666676][i],
  status: i === 0 ? 'invalid' : 'ok', at: '2026-09-18T00:00:00.000Z',
}));
function resumeFixture() {
  const records = [P0, P1].map((promptVersion, i) => {
    const job = { id: `CASE-r1-t1-P${i}`, caseId: 'CASE', rep: 1, turn: 0, promptVersion };
    const base = { runId: `${sessionId}/phase-core/${job.id}`, reserved: 1.08, actual: 0.005 + i / 1000, status: 'ok' };
    return {
      phase: 'phase-core', state: 'settled', sourceHash: settings.javaHash, job,
      globalReservation: { ...base, id: `global-${i}` }, sessionReservation: { ...base, id: `session-${i}` },
      result: { status: 'ok', costUsd: base.actual, actualModel: settings.model, actualProvider: settings.provider,
        actualTier: settings.tier, endpointSnapshot: endpoint, raw: '{"scenes":[]}' },
    };
  });
  return { globals: [...priorEntries(), ...records.map(r => copy(r.globalReservation))],
    session: records.map(r => copy(r.sessionReservation)), records };
}
function temporaryLedgers(t, remaining) {
  const directory = mkdtempSync(join(tmpdir(), 'lucid-p1-budget-test-'));
  t.after(() => {
    const target = resolve(directory), allowedPrefix = resolve(tmpdir()) + sep + 'lucid-p1-budget-test-';
    if (!target.startsWith(allowedPrefix)) throw new Error('Unsafe cleanup path');
    rmSync(target, { recursive: true });
  });
  const globalDirectory = join(directory, 'global'), sessionDirectory = join(directory, 'acceptance');
  const open = () => ({ globalLedger: new Ledger(globalDirectory, 5), sessionLedger: new Ledger(sessionDirectory, remaining) });
  return { open };
}

test('P1 acceptance deducts all four experiments from the original $3 approval, including invalid charges', () => {
  const entries = priorEntries(), before = copy(entries), a = remainingAuthorization(entries);
  assert.equal(a.total, 3);
  assert.ok(Math.abs(a.spent - 1.4735847333333347) < 1e-12);
  assert.ok(Math.abs(a.remaining - 1.5264152666666653) < 1e-12);
  assert.equal(a.entriesHash, hash(entries));
  assert.deepEqual(entries, before);
  assert.equal(PRIOR_ANCHOR.count, 376);
  assert.equal(PRIOR_ANCHOR.globalCount, 418);
  assert.equal(PRIOR_ANCHOR.hash, '47457502ef0bb007534d1efb4a189905423a1e588478b820c6006bff294a5b5d');
  assert.equal(PRIOR_ANCHOR.globalHash, 'ea6e3a01f398667a8fc1f82f972c57cc7a2a350091eb6ac78274c3a2cd81f671');
});

test('unknown or malformed old charges, a foreign session, and resetting the limit all block authorization', () => {
  for (const change of [{ actual: null }, { actual: NaN }, { actual: -1 }, { reserved: 0.001 },
    { reserved: Infinity }, { status: 'reserved' }, { runId: `${sessionId}/phase-core/new` }]) {
    const entries = priorEntries(); Object.assign(entries[0], change);
    assert.throws(() => remainingAuthorization(entries), /fully reconciled/);
  }
  assert.throws(() => remainingAuthorization([]), /fully reconciled/);
  const entries = priorEntries(); entries.push(copy(entries[0]));
  assert.throws(() => remainingAuthorization(entries), /fully reconciled/);
  for (const total of [0, 1.5264152666666653, 4, 5, NaN, Infinity]) {
    assert.throws(() => remainingAuthorization(priorEntries(), total), /exhausted authorization/);
  }
});

test('the old evidence anchor rejects reordering, deleting, or changing any historical ledger prefix', () => {
  const old = priorEntries(), globals = [{ id: 'pre-prompt-comparison', actual: 0.1694 }, ...copy(old)];
  const anchor = { count: old.length, hash: hash(old), spent: remainingAuthorization(old).spent,
    globalCount: globals.length, globalHash: hash(globals) };
  assert.doesNotThrow(() => validatePriorAnchor(old, globals, anchor));
  assert.doesNotThrow(() => validatePriorAnchor(old, [...globals, { id: 'new-call' }], anchor));
  for (const altered of [old.slice(1), [...old].reverse(), old.map((e, i) => i ? e : { ...e, actual: e.actual - 0.01 })]) {
    assert.throws(() => validatePriorAnchor(altered, globals, anchor), /anchor changed/);
  }
  for (const altered of [globals.slice(1), [...globals].reverse(), [{ ...globals[0], actual: 0 }, ...globals.slice(1)]]) {
    assert.throws(() => validatePriorAnchor(old, altered, anchor), /anchor changed/);
  }
});

test('all phases freeze the deducted budget, Java resources/source and lab source, and generation controls', () => {
  const authorization = remainingAuthorization(priorEntries());
  const manifest = { id: sessionId, authorization, limit: authorization.remaining, javaHash: 'java', labHash: 'lab',
    model: settings.model, provider: endpoint.tag, reasoning: 'default', stateFrozen: true, automaticRetry: false };
  assert.equal(sourceHash, serverSourceHash, 'use the server source/resource hash contract');
  assert.doesNotThrow(() => validateManifest(manifest, authorization, 'java', 'lab'));
  for (const change of [{ id: 'new-approval' }, { limit: 3 }, { javaHash: 'changed' }, { labHash: 'changed' },
    { model: 'other' }, { provider: 'other' }, { reasoning: 'low' }, { stateFrozen: false }, { automaticRetry: true }]) {
    assert.throws(() => validateManifest({ ...manifest, ...change }, authorization, 'java', 'lab'), /drift/);
  }
  assert.throws(() => validateManifest(manifest, { ...authorization, spent: 0 }, 'java', 'lab'), /drift/);
});

test('only P0/P1 can be dispatched and every extended fixture permits P1 alone', () => {
  assert.deepEqual(variantsFor({ variants: [P0, P1] }, {}).map(v => v.id), [P0, P1]);
  assert.deepEqual(variantsFor({ variants: [P0, P1] }, { variants: [P1] }).map(v => v.id), [P1]);
  for (const variants of [undefined, [], [P0, P0], [P1, 'unknown'], 'P1']) {
    assert.throws(() => variantsFor({ variants }, {}), /Invalid explicit candidate list/);
  }
  assert.throws(() => variantsFor({ variants: ['J2-json-v1'] }, {}), /Invalid explicit|Only final/);
  for (const fixture of FIXTURES) {
    assert.deepEqual(variantsFor({}, { fixture, variants: [P1] }).map(v => v.id), [P1]);
    for (const variants of [[P0], [P0, P1]]) assert.throws(() => variantsFor({ variants }, { fixture }), /Extended fixtures require P1/);
  }
  assert.throws(() => variantsFor({ variants: [P1] }, { fixture: 'unknown-fixture' }), /known fixture/);
});

test('job expansion gives each core candidate equal turns/repeats and continuation only its own P1 branch', () => {
  const plan = { id: 'phase-core', variants: [P0, P1], cases: [
    { id: 'CORE', mode: 'STORY', input: 'first', repeats: 2 },
    { id: 'CHAIN', mode: 'SANDBOX', inputs: ['one', 'two', 'three'], variants: [P1] },
    { id: 'EXT', mode: 'SANDBOX', input: 'fixture', fixture: FIXTURES[0], variants: [P1] },
  ] };
  const jobs = expandJobs(plan, a => [...a].reverse());
  assert.equal(jobs.length, 8);
  assert.equal(new Set(jobs.map(j => j.id)).size, 8);
  for (const rep of [1, 2]) assert.deepEqual(jobs.filter(j => j.caseId === 'CORE' && j.rep === rep).map(j => j.promptVersion), [P1, P0]);
  assert.deepEqual(jobs.filter(j => j.caseId === 'CHAIN').map(j => [j.turn, j.promptVersion]), [[0, P1], [1, P1], [2, P1]]);
});

test('case collisions, path traversal, unknown modes, blank inputs, and invalid repeats block dispatch', () => {
  const c = { id: 'CASE', mode: 'STORY', input: 'normal' }, plan = { id: 'phase-core', variants: [P0, P1], cases: [c] };
  assert.throws(() => expandJobs({ ...plan, cases: [c, copy(c)] }), /duplicate case IDs/);
  for (const change of [{ id: '../escape' }, { id: 'a/b' }, { mode: 'THEATER' }, { input: ' ' },
    { inputs: [] }, { inputs: ['valid', null] }, { repeats: 0 }, { repeats: 5 }, { repeats: 1.5 }]) {
    assert.throws(() => expandJobs({ ...plan, cases: [{ ...c, ...change }] }), /Invalid/);
  }
});

test('a persisted phase binds inputs, fixture, variants, history and randomized job order', () => {
  const plan = { id: 'phase-core', variants: [P0, P1], cases: [{ id: 'CASE', mode: 'STORY', input: 'first', history: [] }] };
  const jobs = expandJobs(plan, a => [...a].reverse()), phase = { plan, planHash: hash(plan), jobs, jobsHash: hash(jobs) };
  assert.doesNotThrow(() => validatePhase(phase, plan));
  for (const change of [{ input: 'second' }, { history: [{ input: 'prior', raw: '{}' }] }, { variants: [P1] }, { fixture: FIXTURES[1], variants: [P1] }]) {
    assert.throws(() => validatePhase(phase, { ...plan, cases: [{ ...plan.cases[0], ...change }] }), /drift/);
  }
  assert.throws(() => validatePhase({ ...phase, jobs: [...jobs].reverse() }, plan), /drift/);
  const missing = jobs.slice(1);
  assert.throws(() => validatePhase({ ...phase, jobs: missing, jobsHash: hash(missing) }, plan), /drift/);
});

test('every charged result must map one-to-one to both ledgers before another phase can run', () => {
  const f = resumeFixture(), before = copy(f);
  assert.doesNotThrow(() => validateResume(f.globals, f.session, f.records, settings));
  assert.deepEqual(f, before);
  for (const remove of ['globals', 'session', 'records']) {
    const broken = resumeFixture(); broken[remove].pop();
    assert.throws(() => validateResume(broken.globals, broken.session, broken.records, settings), /count mismatch/);
  }
  for (const mutate of [
    f => { f.globals[5].id = f.globals[4].id; }, f => { f.session[1].runId = f.session[0].runId; },
    f => { f.records[1] = copy(f.records[0]); },
  ]) {
    const broken = resumeFixture(); mutate(broken);
    assert.throws(() => validateResume(broken.globals, broken.session, broken.records, settings), /Duplicate/);
  }
});

test('unknown costs, partial persistence, wrong source or identity, and over-reservation charges block resume', () => {
  for (const mutate of [
    f => { f.records[0].state = 'received'; }, f => { f.records[0].phase = 'phase-other'; },
    f => { f.records[0].result.costUsd = null; }, f => { f.records[0].result.costUsd = -1; },
    f => { f.globals[4].actual = 0.02; }, f => { f.session[0].actual = null; },
    f => { f.globals[4].reserved = 0.001; }, f => { f.session[0].reserved = 1.2; },
    f => { f.records[0].sourceHash = 'changed'; },
    ...['actualModel', 'actualProvider', 'actualTier'].flatMap(key => [null, '', 'unexpected'].map(value => f => { f.records[0].result[key] = value; })),
  ]) {
    const f = resumeFixture(); mutate(f);
    assert.throws(() => validateResume(f.globals, f.session, f.records, settings), /mismatch|drift/);
  }
});

test('the same response provenance gate blocks the next call immediately, including omitted identity fields', () => {
  const result = resumeFixture().records[0].result;
  assert.doesNotThrow(() => assertResult(result, 1.08, settings.model, endpoint));
  for (const key of ['actualModel', 'actualProvider', 'actualTier']) for (const value of [undefined, null, '', 'unexpected']) {
    assert.throws(() => assertResult({ ...result, [key]: value }, 1.08, settings.model, endpoint), /mismatch/);
  }
  for (const costUsd of [null, -1, Infinity, 1.081]) {
    assert.throws(() => assertResult({ ...result, costUsd }, 1.08, settings.model, endpoint), /not safe/);
  }
  assert.throws(() => assertResult({ ...result, status: 'invalid' }, 1.08, settings.model, endpoint), /not safe/);
});

test('a billed invalid result needs a hash-bound review and cannot become a silent retry', () => {
  const f = resumeFixture(), record = f.records[0];
  record.result.status = 'invalid';
  for (const entry of [f.globals[4], f.session[0], record.globalReservation, record.sessionReservation]) entry.status = 'invalid';
  assert.throws(() => validateResume(f.globals, f.session, f.records, settings), /Unreviewed invalid/);
  const { phase, ...original } = record, key = `${phase}/${record.job.id}`;
  const review = { reason: 'Preserve billed enum failure without retry.', fileHash: hash(original) };
  const approved = { ...settings, reviews: { [key]: review } }, before = copy(f);
  assert.doesNotThrow(() => validateResume(f.globals, f.session, f.records, approved));
  assert.deepEqual(f, before);
  assert.doesNotThrow(() => assertResult(record.result, 1.08, settings.model, endpoint, true));
  for (const change of [{ reason: '' }, { fileHash: 'changed' }]) {
    assert.throws(() => validateResume(f.globals, f.session, f.records, { ...settings, reviews: { [key]: { ...review, ...change } } }), /Unreviewed invalid/);
  }
  record.result.raw += ' ';
  assert.throws(() => validateResume(f.globals, f.session, f.records, approved), /Unreviewed invalid/);
});

test('restarting or changing phases preserves the reduced limit and checks the full next-call reservation', async t => {
  const a = remainingAuthorization(priorEntries()), { open } = temporaryLedgers(t, a.remaining);
  let ledgers = open(), calls = 0;
  const generate = async () => { calls++; return { status: 'ok', costUsd: 0.24 }; };
  for (const phase of ['phase-core', 'phase-extensions']) {
    await budgetedGenerate({ ...ledgers, id: `${sessionId}/${phase}/CASE`, reserve: 1.08, generate, persist: () => {} });
    ledgers = open();
  }
  assert.equal(calls, 2);
  assert.equal(ledgers.sessionLedger.snapshot().known, 0.48);
  assert.ok(a.spent + ledgers.sessionLedger.snapshot().known < 3);
  await assert.rejects(budgetedGenerate({ ...ledgers, id: `${sessionId}/phase-continuation/CASE`, reserve: 1.08, generate, persist: () => {} }), /Budget insufficient/);
  assert.equal(calls, 2);
  assert.equal(ledgers.sessionLedger.entries.length, 2);
  assert.equal(ledgers.globalLedger.entries.length, 2);
});

test('unknown usage keeps both holds after restart and blocks any following acceptance phase', async t => {
  const { open } = temporaryLedgers(t, remainingAuthorization(priorEntries()).remaining);
  let ledgers = open(), calls = 0;
  const generate = async () => { calls++; return { status: 'error', costUsd: null }; };
  await assert.rejects(budgetedGenerate({ ...ledgers, id: `${sessionId}/phase-core/CASE`, reserve: 1.08, generate, persist: () => {} }), /Unknown charge/);
  ledgers = open();
  assert.equal(ledgers.globalLedger.snapshot().held, 1.08);
  assert.equal(ledgers.sessionLedger.snapshot().held, 1.08);
  await assert.rejects(budgetedGenerate({ ...ledgers, id: `${sessionId}/phase-extensions/CASE`, reserve: 1.08, generate, persist: () => {} }), /Unresolved reservation/);
  assert.equal(calls, 1);
});
