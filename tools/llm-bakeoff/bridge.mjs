import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';

export class PromptBridge {
  constructor(runtimeFile) {
    const runtime = JSON.parse(readFileSync(runtimeFile, 'utf8'));
    this.pending = new Map(); this.sequence = 0;
    const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL/i.test(k)));
    this.child = spawn(runtime.java, [`@${runtime.argsFile}`], { env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    // Bridge stderr is application diagnostics, never returned to browser or result export.
    this.child.stderr.on('data', () => {});
    createInterface({ input: this.child.stdout }).on('line', line => {
      try {
        const reply = JSON.parse(line), waiter = this.pending.get(reply.id);
        if (!waiter) return;
        this.pending.delete(reply.id); clearTimeout(waiter.timer);
        reply.error ? waiter.reject(new Error(reply.error)) : waiter.resolve(reply.result);
      } catch { this.fail(new Error('Java bridge returned an invalid protocol frame')); }
    });
    this.child.on('error', () => this.fail(new Error('Cannot start Java prompt bridge')));
    this.child.on('exit', () => { this.dead = true; this.fail(new Error('Java prompt bridge stopped')); });
  }
  fail(error) { for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(error); } this.pending.clear(); }
  call(operation, body) {
    if (this.dead) return Promise.reject(new Error('Java prompt bridge is unavailable'));
    return new Promise((resolve, reject) => {
      const id = ++this.sequence;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('Java bridge timeout')); }, 30000);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(JSON.stringify({ ...body, operation, id }) + '\n', error => { if (error) this.fail(new Error('Java bridge input closed')); });
    });
  }
  close() { this.child.kill(); this.fail(new Error('Lab stopped')); }
}
