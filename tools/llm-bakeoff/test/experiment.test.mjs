import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { Ledger } from '../engine.mjs';
import { budgetedGenerate } from '../experiment.mjs';

function fixture(t, limits = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'lucid-experiment-test-'));
  t.after(() => {
    const target = resolve(directory), parent = resolve(tmpdir()) + sep;
    if (!target.startsWith(parent + 'lucid-experiment-test-')) throw new Error('Unsafe cleanup path');
    rmSync(target, { recursive: true });
  });
  const globalLedger = new Ledger(join(directory, 'global'), limits.global ?? 5);
  const sessionLedger = new Ledger(join(directory, 'session'), limits.session ?? 3);
  const events = [];
  let calls = 0;
  const args = {
    globalLedger, sessionLedger, id: 'phase/case/P0', reserve: 1.1,
    generate: async () => { calls++; events.push('generate'); return { status: 'ok', costUsd: 0.007 }; },
    persist: value => events.push(value.state),
  };
  return { args, events, globalLedger, sessionLedger, calls: () => calls };
}

test('serial experiment persists both reservations before dispatch and evidence before settlement', async t => {
  const f = fixture(t), recorded = [];
  f.args.persist = value => {
    recorded.push(structuredClone(value));
    f.events.push(value.state);
    if (value.state !== 'settled') {
      assert.equal(f.globalLedger.snapshot().held, 1.1);
      assert.equal(f.sessionLedger.snapshot().held, 1.1);
    } else {
      assert.equal(f.globalLedger.snapshot().known, 0.007);
      assert.equal(f.sessionLedger.snapshot().known, 0.007);
    }
  };
  const result = await budgetedGenerate(f.args);
  assert.equal(result.costUsd, 0.007);
  assert.equal(f.calls(), 1);
  assert.deepEqual(f.events, ['reserved', 'generate', 'received', 'settled']);
  assert.equal(recorded[0].globalReservation.actual, null);
  assert.equal(recorded[2].globalReservation.actual, 0.007);
  assert.equal(f.globalLedger.snapshot().held, 0);
  assert.equal(f.sessionLedger.snapshot().held, 0);
});

test('unknown final charge retains both reservations and blocks the next paid dispatch', async t => {
  const f = fixture(t);
  f.args.generate = async () => ({ status: 'ok', costUsd: null });
  await assert.rejects(budgetedGenerate(f.args), /Unknown charge/);
  assert.equal(f.globalLedger.snapshot().held, 1.1);
  assert.equal(f.sessionLedger.snapshot().held, 1.1);
  let furtherCalls = 0;
  await assert.rejects(budgetedGenerate({ ...f.args, generate: async () => { furtherCalls++; } }), /Unresolved reservation/);
  assert.equal(furtherCalls, 0);
});

test('each budget independently blocks dispatch without creating reservations', async t => {
  for (const limits of [{ global: 1 }, { session: 1 }]) {
    const f = fixture(t, limits);
    await assert.rejects(budgetedGenerate(f.args), /Budget insufficient/);
    assert.equal(f.calls(), 0);
    assert.equal(f.globalLedger.entries.length, 0);
    assert.equal(f.sessionLedger.entries.length, 0);
  }
});

test('failure to persist reservations prevents dispatch and preserves both holds', async t => {
  const f = fixture(t);
  f.args.persist = () => { throw new Error('reservation disk failure'); };
  await assert.rejects(budgetedGenerate(f.args), /reservation disk failure/);
  assert.equal(f.calls(), 0);
  assert.equal(f.globalLedger.snapshot().held, 1.1);
  assert.equal(f.sessionLedger.snapshot().held, 1.1);
});

test('failure to persist received evidence keeps both charges unresolved', async t => {
  const f = fixture(t);
  f.args.persist = value => { if (value.state === 'received') throw new Error('evidence disk failure'); };
  await assert.rejects(budgetedGenerate(f.args), /evidence disk failure/);
  assert.equal(f.calls(), 1);
  assert.equal(f.globalLedger.snapshot().held, 1.1);
  assert.equal(f.sessionLedger.snapshot().held, 1.1);
  assert.equal(f.globalLedger.snapshot().known, 0);
  assert.equal(f.sessionLedger.snapshot().known, 0);
});

test('failure to persist settled state stops execution without erasing confirmed charges', async t => {
  const f = fixture(t);
  f.args.persist = value => { if (value.state === 'settled') throw new Error('settled disk failure'); };
  await assert.rejects(budgetedGenerate(f.args), /settled disk failure/);
  assert.equal(f.calls(), 1);
  assert.equal(f.globalLedger.snapshot().known, 0.007);
  assert.equal(f.sessionLedger.snapshot().known, 0.007);
});

test('second ledger reservation failure cannot dispatch or release the first hold', async t => {
  const f = fixture(t);
  f.sessionLedger.reserve = () => { throw new Error('session reservation disk failure'); };
  await assert.rejects(budgetedGenerate(f.args), /session reservation disk failure/);
  assert.equal(f.calls(), 0);
  assert.equal(f.globalLedger.snapshot().held, 1.1);
  assert.equal(f.sessionLedger.entries.length, 0);
});

test('generation exceptions preserve both reservations without an implicit retry', async t => {
  const f = fixture(t);
  let calls = 0;
  f.args.generate = async () => { calls++; throw new Error('transport failed'); };
  await assert.rejects(budgetedGenerate(f.args), /transport failed/);
  assert.equal(calls, 1);
  assert.equal(f.globalLedger.snapshot().held, 1.1);
  assert.equal(f.sessionLedger.snapshot().held, 1.1);
});

test('failure settling the second ledger leaves a blocking hold and received evidence', async t => {
  const f = fixture(t);
  f.sessionLedger.settle = () => { throw new Error('session settlement disk failure'); };
  await assert.rejects(budgetedGenerate(f.args), /session settlement disk failure/);
  assert.equal(f.calls(), 1);
  assert.equal(f.globalLedger.snapshot().known, 0.007);
  assert.equal(f.sessionLedger.snapshot().held, 1.1);
  assert.deepEqual(f.events, ['reserved', 'generate', 'received']);
});

test('known invalid output is charged and stops; charge above reservation also stops', async t => {
  for (const result of [{ status: 'invalid', costUsd: 0.007 }, { status: 'ok', costUsd: 1.2 }]) {
    const f = fixture(t);
    f.args.generate = async () => result;
    await assert.rejects(budgetedGenerate(f.args), result.status === 'invalid' ? /Generation invalid/ : /exceeded reservation/);
    assert.equal(f.globalLedger.snapshot().known, result.costUsd);
    assert.equal(f.sessionLedger.snapshot().known, result.costUsd);
  }
});

test('negative cost is treated as unknown and cannot report a successful settlement', async t => {
  const f = fixture(t);
  f.args.generate = async () => ({ status: 'ok', costUsd: -0.007 });
  await assert.rejects(budgetedGenerate(f.args), /Unknown charge/);
  assert.equal(f.globalLedger.snapshot().held, 1.1);
  assert.equal(f.sessionLedger.snapshot().held, 1.1);
});
