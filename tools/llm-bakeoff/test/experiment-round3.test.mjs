import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { hash, Ledger } from '../engine.mjs';
import { budgetedGenerate } from '../experiment.mjs';
import {
  remainingAuthorization, variantsFor, validateManifest, validateResume, validatePriorAnchor,
  expandJobs, validatePhase,
} from '../experiment-round3.mjs';

const PRIOR = 'prompt-eval-20260918';
const ROUND2 = 'prompt-eval-round3-20260918';
const P0 = 'P0-service-fixed-state-v1';
const C1 = 'C1-repairs-v1';
const S1 = 'S1-character-v1';
const copy = value => structuredClone(value);
const resumeSettings = { javaHash: 'frozen-java', model: 'google/gemini-3-flash-preview', provider: 'Google AI Studio', tier: 'default' };
const priorEntry = (id, actual, status = 'ok') => ({
  id, runId: `${PRIOR}/phase1/${id}`, reserved: 1.1, actual, status,
  at: '2026-09-18T00:00:00.000Z',
});

function resumeFixture() {
  const records = ['P0', 'C1'].map((variant, index) => {
    const job = { id: `CASE-r1-t1-${variant}`, caseId: 'CASE', rep: 1, turn: 0, promptVersion: index ? C1 : P0 };
    const base = { runId: `${ROUND2}/phase1/${job.id}`, reserved: 1.1, actual: 0.004 + index / 1000, status: 'ok', at: '2026-09-18T01:00:00.000Z' };
    return {
      phase: 'phase1', job, state: 'settled', sourceHash: 'frozen-java',
      globalReservation: { ...base, id: `global-${index}` },
      sessionReservation: { ...base, id: `session-${index}` },
      result: {
        status: 'ok', costUsd: base.actual,
        requestedModel: 'google/gemini-3-flash-preview', actualModel: 'google/gemini-3-flash-preview',
        requestedProvider: 'google-ai-studio', actualProvider: 'Google AI Studio',
        requestedTier: 'default', actualTier: 'default', reasoning: 'default',
        endpointSnapshot: { name: 'Google AI Studio', tag: 'google-ai-studio', tier: 'default' },
      },
    };
  });
  return {
    globals: [priorEntry('historical', 0.2), ...records.map(r => copy(r.globalReservation))],
    session: records.map(r => copy(r.sessionReservation)), records,
  };
}

test('remaining approval deducts settled prior charges, including paid invalid output', () => {
  const entries = [priorEntry('one', 0.5), priorEntry('invalid', 0.02754835, 'invalid')];
  const authorization = remainingAuthorization(entries);
  assert.equal(authorization.total, 3);
  assert.ok(Math.abs(authorization.spent - 0.52754835) < 1e-12);
  assert.ok(Math.abs(authorization.remaining - 2.47245165) < 1e-12);
  assert.equal(authorization.count, 2);
  assert.equal(authorization.entriesHash, hash(entries));
  assert.deepEqual(entries, [priorEntry('one', 0.5), priorEntry('invalid', 0.02754835, 'invalid')]);
});

test('unresolved, malformed, foreign, or duplicate prior charges cannot create a fresh approval', () => {
  for (const actual of [null, undefined, NaN, Infinity, -0.001]) {
    assert.throws(() => remainingAuthorization([priorEntry('one', actual)]), /fully reconciled/);
  }
  for (const override of [{ status: 'reserved' }, { reserved: 0.001 }, { runId: 'another-session/job' }]) {
    assert.throws(() => remainingAuthorization([{ ...priorEntry('one', 0.01), ...override }]), /fully reconciled/);
  }
  assert.throws(() => remainingAuthorization([]), /fully reconciled/);
  assert.throws(() => remainingAuthorization([priorEntry('one', 0.1), priorEntry('one', 0.1)]), /fully reconciled/);
});

test('remaining approval cannot reset or raise the original three-dollar authorization', () => {
  const entries = [priorEntry('one', 0.1)];
  for (const total of [4, 5, 100, NaN, Infinity, 0]) {
    assert.throws(() => remainingAuthorization(entries, total), /exhausted authorization/);
  }
  assert.throws(() => remainingAuthorization([{ ...priorEntry('one', 3), reserved: 3 }]), /exhausted authorization/);
  assert.throws(() => remainingAuthorization([{ ...priorEntry('one', 3.1), reserved: 4 }]), /exhausted authorization/);
});

test('manifest freezes prior approval, remaining limit, Java source, and runner source across phases', () => {
  const authorization = remainingAuthorization([priorEntry('one', 0.5)]);
  const manifest = {
    authorization, limit: authorization.remaining, javaHash: 'java', labHash: 'lab',
    id: ROUND2, model: 'google/gemini-3-flash-preview', provider: 'google-ai-studio',
    reasoning: 'default', stateFrozen: true, automaticRetry: false,
  };
  assert.doesNotThrow(() => validateManifest(manifest, authorization, 'java', 'lab'));
  assert.throws(() => validateManifest({ ...manifest, limit: 3 }, authorization, 'java', 'lab'), /authorization drift/);
  assert.throws(() => validateManifest(manifest, remainingAuthorization([priorEntry('one', 0.4)]), 'java', 'lab'), /authorization drift/);
  assert.throws(() => validateManifest(manifest, authorization, 'changed-java', 'lab'), /Frozen source drift/);
  assert.throws(() => validateManifest(manifest, authorization, 'java', 'changed-lab'), /Frozen source drift/);
  for (const changed of [{ id: 'another-round' }, { model: 'different-model' }, { provider: 'different-provider' }, { reasoning: 'low' }, { stateFrozen: false }, { automaticRetry: true }]) {
    assert.throws(() => validateManifest({ ...manifest, ...changed }, authorization, 'java', 'lab'), /drift/);
  }
});

test('prior anchor accepts the audited records and rejects pre-manifest truncation or mutation', () => {
  const entries = [priorEntry('one', 0.5), priorEntry('two', 0.02754835, 'invalid')];
  const globals = [{ ...priorEntry('older', 0.1), runId: 'older-session/job' }, ...copy(entries)];
  const a = remainingAuthorization(entries);
  const anchor = { count: entries.length, hash: hash(entries), spent: a.spent, globalCount: globals.length, globalHash: hash(globals) };
  assert.doesNotThrow(() => validatePriorAnchor(entries, globals, anchor));
  assert.doesNotThrow(() => validatePriorAnchor(entries, [...globals, { id: 'new-round2', runId: `${ROUND2}/phase1/new` }], anchor));
  assert.throws(() => validatePriorAnchor(entries.slice(0, 1), globals, anchor), /anchor changed/);
  assert.throws(() => validatePriorAnchor([{ ...entries[0], actual: 0.4 }, entries[1]], globals, anchor), /anchor changed/);
  assert.throws(() => validatePriorAnchor([{ ...entries[0], at: 'changed' }, entries[1]], globals, anchor), /anchor changed/);
  assert.throws(() => validatePriorAnchor(entries, globals.slice(1), anchor), /anchor changed/);
  assert.throws(() => validatePriorAnchor(entries, [{ ...globals[0], actual: 0 }, ...globals.slice(1)], anchor), /anchor changed/);
  assert.throws(() => validatePriorAnchor(entries, [...globals].reverse(), anchor), /anchor changed/);
});

test('candidate lists are explicit, unique, supported, and overridable for one case', () => {
  const plan = { variants: [P0, C1] };
  assert.deepEqual(variantsFor(plan, {}).map(v => v.id), [P0, C1]);
  assert.deepEqual(variantsFor(plan, { variants: [C1, S1] }).map(v => v.id), [C1, S1]);
  for (const variants of [undefined, [], [P0], [P0, P0], [P0, 'unknown'], 'P0,C1']) {
    assert.throws(() => variantsFor({ variants }, {}), /Invalid explicit candidate list/);
  }
});

test('job expansion balances only the chosen candidates for every repeat and turn', () => {
  const plan = { id: 'phase1', variants: [P0, C1], cases: [
    { id: 'CASE-A', mode: 'STORY', inputs: ['first', 'second'], repeats: 2 },
    { id: 'CASE-B', mode: 'SANDBOX', input: 'single', variants: [C1, S1] },
  ] };
  const jobs = expandJobs(plan, a => [...a].reverse());
  assert.equal(jobs.length, 10);
  assert.equal(new Set(jobs.map(j => j.id)).size, 10);
  for (const rep of [1, 2]) for (const turn of [0, 1]) {
    assert.deepEqual(jobs.filter(j => j.caseId === 'CASE-A' && j.rep === rep && j.turn === turn).map(j => j.promptVersion), [C1, P0]);
  }
  assert.deepEqual(jobs.filter(j => j.caseId === 'CASE-B').map(j => j.promptVersion), [S1, C1]);
});

test('duplicate cases, traversal IDs, invalid modes, empty inputs, and invalid repeats are rejected before dispatch', () => {
  const c = { id: 'CASE', mode: 'STORY', input: 'normal' };
  const plan = { id: 'phase1', variants: [P0, C1], cases: [c] };
  assert.throws(() => expandJobs({ ...plan, cases: [c, copy(c)] }), /duplicate case IDs/);
  for (const change of [{ id: '../elsewhere' }, { mode: 'UNKNOWN' }, { input: '' }, { inputs: [] }, { inputs: ['normal', 7] }, { repeats: 0 }, { repeats: 5 }, { repeats: 1.5 }]) {
    assert.throws(() => expandJobs({ ...plan, cases: [{ ...c, ...change }] }), /Invalid/);
  }
});

test('phase records bind the exact plan, candidate set, and randomized job order', () => {
  const plan = { id: 'phase1', variants: [P0, C1], cases: [{ id: 'CASE', mode: 'STORY', input: 'normal' }] };
  const jobs = expandJobs(plan, a => [...a].reverse());
  const phase = { plan, planHash: hash(plan), jobs, jobsHash: hash(jobs) };
  assert.doesNotThrow(() => validatePhase(phase, plan));
  assert.throws(() => validatePhase(phase, { ...plan, variants: [P0, S1] }), /drift/);
  assert.throws(() => validatePhase({ ...phase, jobs: [...jobs].reverse() }, plan), /drift/);
  const missing = jobs.slice(1);
  assert.throws(() => validatePhase({ ...phase, jobs: missing, jobsHash: hash(missing) }, plan), /drift/);
  const wrong = jobs.map(j => ({ ...j, turn: 1 }));
  assert.throws(() => validatePhase({ ...phase, jobs: wrong, jobsHash: hash(wrong) }, plan), /drift/);
});

test('resume accepts a complete one-to-one set of settled result and ledger evidence', () => {
  const f = resumeFixture();
  const before = copy(f);
  assert.doesNotThrow(() => validateResume(f.globals, f.session, f.records));
  assert.deepEqual(f, before);
  assert.doesNotThrow(() => validateResume([priorEntry('prior-only', 0.2)], [], []));
});

test('deleting a charged result or either ledger entry cannot silently enable another call', () => {
  for (const missing of ['record', 'global', 'session']) {
    const f = resumeFixture();
    if (missing === 'record') f.records.pop();
    if (missing === 'global') f.globals.pop();
    if (missing === 'session') f.session.pop();
    assert.throws(() => validateResume(f.globals, f.session, f.records), /count mismatch/);
  }
});

test('duplicate ledger IDs, duplicate billed jobs, and duplicated result files block resume', () => {
  for (const change of [
    f => { f.globals[2].id = f.globals[1].id; },
    f => { f.globals[2].runId = f.globals[1].runId; },
    f => { f.session[1].id = f.session[0].id; },
    f => { f.session[1].runId = f.session[0].runId; },
    f => { f.records[1] = copy(f.records[0]); },
  ]) {
    const f = resumeFixture(); change(f);
    assert.throws(() => validateResume(f.globals, f.session, f.records), /Duplicate/);
  }
});

test('resume rejects mismatched amounts, statuses, phase identity, and unconfirmed records', () => {
  for (const change of [
    f => { f.records[0].state = 'received'; },
    f => { f.records[0].phase = 'phase2'; },
    f => { f.records[0].result.costUsd = null; },
    f => { f.records[0].result.costUsd = -0.01; },
    f => { f.globals[1].actual = 0.01; },
    f => { f.session[0].actual = null; },
    f => { f.session[0].status = 'invalid'; },
    f => { f.globals[1].reserved = 0.001; },
    f => { f.session[0].reserved = 1.2; },
    f => { f.records[0].sessionReservation.id = 'missing'; },
  ]) {
    const f = resumeFixture(); change(f);
    assert.throws(() => validateResume(f.globals, f.session, f.records), /mismatch/);
  }
});

test('all prior phases must preserve the frozen source and actual model/provider/tier before another phase starts', () => {
  const normal = resumeFixture();
  assert.doesNotThrow(() => validateResume(normal.globals, normal.session, normal.records, resumeSettings));
  for (const change of [
    r => { r.sourceHash = 'different-source'; },
    r => { r.result.actualModel = 'different-model'; },
    r => { r.result.actualProvider = 'different-provider'; },
    r => { r.result.actualTier = 'priority'; },
    r => { r.result.actualModel = null; },
    r => { r.result.actualProvider = null; },
    r => { r.result.actualTier = null; },
  ]) {
    const f = resumeFixture(); change(f.records[0]);
    assert.throws(() => validateResume(f.globals, f.session, f.records, resumeSettings), /source drift|model\/provider\/tier mismatch/);
  }
});

test('switching phase cannot bypass an unreviewed known-cost invalid result', () => {
  const f = resumeFixture();
  f.records[0].result.status = 'invalid';
  f.records[0].result.raw = '{"scenes":[{"emotion":"PLAYFUL"}]}';
  f.records[0].result.validation = { valid: false, issues: ['Unknown emotion'] };
  for (const entry of [f.globals[1], f.session[0], f.records[0].globalReservation, f.records[0].sessionReservation]) entry.status = 'invalid';
  assert.throws(() => validateResume(f.globals, f.session, f.records, resumeSettings), /Unreviewed invalid result/);

  const { phase, ...original } = f.records[0];
  const key = `${phase}/${f.records[0].job.id}`;
  const review = { reason: 'Confirmed charged PLAYFUL enum failure; preserve without retry.', fileHash: hash(original) };
  const settings = { ...resumeSettings, reviews: { [key]: review } };
  const before = copy(f);
  assert.doesNotThrow(() => validateResume(f.globals, f.session, f.records, settings));
  assert.deepEqual(f, before, 'approval must not rewrite the failed result or its charge');
  assert.throws(() => validateResume(f.globals, f.session, f.records, { ...settings, reviews: { [key]: { ...review, reason: '' } } }), /Unreviewed invalid result/);
  assert.throws(() => validateResume(f.globals, f.session, f.records, { ...settings, reviews: { [key]: { ...review, fileHash: 'outdated' } } }), /Unreviewed invalid result/);
  f.records[0].result.raw += ' ';
  assert.throws(() => validateResume(f.globals, f.session, f.records, settings), /Unreviewed invalid result/);
});

test('a new phase and a process restart reuse the reduced session budget instead of resetting three dollars', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'lucid-round2-budget-test-'));
  t.after(() => {
    const target = resolve(directory), parent = resolve(tmpdir()) + sep;
    if (!target.startsWith(parent + 'lucid-round2-budget-test-')) throw new Error('Unsafe cleanup path');
    rmSync(target, { recursive: true });
  });
  const authorization = remainingAuthorization([priorEntry('prior', 0.52754835)]);
  const globalDirectory = join(directory, 'global'), sessionDirectory = join(directory, 'round2');
  let globalLedger = new Ledger(globalDirectory, 5), sessionLedger = new Ledger(sessionDirectory, authorization.remaining);
  let calls = 0;
  const generate = async () => { calls++; return { status: 'ok', costUsd: 0.7 }; };
  const persist = () => {};
  for (const phase of ['phase1', 'phase2']) {
    await budgetedGenerate({ globalLedger, sessionLedger, id: `${ROUND2}/${phase}/CASE`, reserve: 1.1, generate, persist });
    globalLedger = new Ledger(globalDirectory, 5);
    sessionLedger = new Ledger(sessionDirectory, authorization.remaining);
  }
  assert.equal(calls, 2);
  assert.equal(sessionLedger.snapshot().known, 1.4);
  assert.ok(Math.abs(sessionLedger.snapshot().available - 1.07245165) < 1e-12);
  assert.ok(authorization.spent + sessionLedger.snapshot().known < 3);
  await assert.rejects(budgetedGenerate({ globalLedger, sessionLedger, id: `${ROUND2}/phase3/CASE`, reserve: 1.1, generate, persist }), /Budget insufficient/);
  assert.equal(calls, 2, 'the third call must be blocked before dispatch');
  assert.equal(sessionLedger.entries.length, 2, 'a failed budget preflight must not create another reservation');
});

test('third round deducts both prior experiments within the same original authorization', () => {
  const first=priorEntry('first',0.52754835), second={...priorEntry('second',0.5033124833333333),runId:'prompt-eval-round2-20260918/phase2/second'};
  const a=remainingAuthorization([first,second]);
  assert.ok(Math.abs(a.remaining-1.9691391666666667)<1e-12);
  assert.throws(()=>remainingAuthorization([first,{...second,actual:null}]),/fully reconciled/);
});
