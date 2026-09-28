import http from 'node:http';
import { randomBytes, timingSafeEqual, createHash } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync, unlinkSync } from 'node:fs';
import { dirname, join, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { PromptBridge } from './bridge.mjs';
import { Catalog, Engine, Ledger, cleanError, PROMPT_VARIANTS, prepareComparison, hash } from './engine.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const publicDir = join(here, 'public');
const json = (res, status, value) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(value)); };
const developmentCases = () => JSON.parse(readFileSync(join(root,'docs/28_assets/development-cases.json'),'utf8'));
export function evaluationCaseMetadata(input) {
  if (!input.evaluationCaseId) return null;
  const collection = developmentCases(), item = collection.cases.find(c => c.id === input.evaluationCaseId);
  if (!item) throw new Error('Unknown evaluation case');
  return { id: item.id, familyId: item.familyId, version: collection.version, sourceHash: hash(collection),
    inputMatches: input.input === item.input, modeMatches: input.mode === item.mode, initialHistory: !(input.history?.length) };
}

export function sourceHash() {
  const files = [];
  const walk = dir => { for (const item of readdirSync(dir, { withFileTypes: true })) { const path = join(dir,item.name); item.isDirectory() ? walk(path) : files.push(path); } };
  for (const dir of ['src/main/java','src/main/resources','src/bakeoff/java','src/bakeoff/resources']) walk(join(root,dir));
  const normalized = file => relative(root,file).replaceAll('\\','/');
  files.sort((a,b) => normalized(a) < normalized(b) ? -1 : normalized(a) > normalized(b) ? 1 : 0);
  const digest = createHash('sha256');
  for (const file of files) digest.update(normalized(file)).update('\0').update(readFileSync(file));
  return digest.digest('hex');
}

async function body(req) {
  if (!req.headers['content-type']?.startsWith('application/json')) throw new Error('JSON 요청만 허용됩니다.');
  let bytes = 0, chunks = [];
  for await (const chunk of req) { bytes += chunk.length; if (bytes > 2000000) throw new Error('요청 2MB 제한을 넘었습니다.'); chunks.push(chunk); }
  const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid request');
  return value;
}

export function createLabServer({ bridge, catalog, ledger, engine, port, capability = randomBytes(32).toString('hex') }) {
  const active = new Set();
  const server = http.createServer(async (req,res) => {
    const boundPort = port || server.address()?.port;
    const origin = `http://127.0.0.1:${boundPort}`;
    res.setHeader('Cache-Control','no-store');
    res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('Cross-Origin-Resource-Policy','same-origin');
    res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
    const path = req.url;
    const entry = req.method === 'GET' && path === '/';
    const userNavigation = entry && req.headers['sec-fetch-mode'] === 'navigate' &&
      req.headers['sec-fetch-dest'] === 'document' && req.headers['sec-fetch-user'] === '?1';
    const canonicalHost = req.headers.host === `127.0.0.1:${boundPort}`;
    const localhostEntry = entry && req.headers.host === `localhost:${boundPort}`;
    // A clicked link may be cross-site even though its destination is loopback.
    // Only the user-initiated top-level entry document gets this exception; APIs,
    // embedded frames and cross-origin fetches still require the original checks.
    if ((!canonicalHost && !localhostEntry) || (req.headers['sec-fetch-site'] === 'cross-site' && !userNavigation)) return json(res,403,{error:'Local origin required'});
    if (localhostEntry) { res.writeHead(302,{Location:origin+'/'}); return res.end(); }
    try {
      const assets = { '/':['index.html','text/html'], '/app.js':['app.js','text/javascript'], '/suggestions.js':['suggestions.js','text/javascript'], '/style.css':['style.css','text/css'] };
      if (req.method === 'GET' && Object.hasOwn(assets,path)) {
        const [file,type] = assets[path];
        let content = readFileSync(join(publicDir,file),'utf8');
        if (file === 'index.html') content = content.replace('__CAPABILITY__',capability);
        res.writeHead(200,{'Content-Type':`${type}; charset=utf-8`}); return res.end(content);
      }
      const token = Buffer.from(String(req.headers['x-lab-capability'] ?? ''));
      const expected = Buffer.from(capability);
      const validOrigin = req.headers.origin === origin || (req.method === 'GET' && !req.headers.origin);
      if (!validOrigin || token.length !== expected.length || !timingSafeEqual(token,expected)) return json(res,403,{error:'Lab session required'});
      if (req.method === 'GET' && path === '/api/status') return json(res,200,{keyReady:!!engine.apiKey,busy:engine.busy,budget:ledger.snapshot(),sourceVersion:engine.sourceVersion});
      if (req.method === 'GET' && path === '/api/fixture') return json(res,200,await bridge.call('fixture',{}));
      if (req.method === 'GET' && path === '/api/catalog') return json(res,200,{models:await catalog.all()});
      if (req.method === 'GET' && path === '/api/prompt-variants') return json(res,200,{variants:PROMPT_VARIANTS});
      if (req.method === 'GET' && path === '/api/evaluation-cases') return json(res,200,developmentCases());
      if (req.method === 'POST' && path === '/api/prepare') {
        if (engine.busy) throw new Error('비교 완료 후 프롬프트를 확인하세요.');
        return json(res,200,await prepareComparison(bridge,await body(req)));
      }
      if (req.method === 'POST' && path === '/api/run') {
        const input = await body(req);
        input.evaluationCase = evaluationCaseMetadata(input);
        const controller = new AbortController(); active.add(controller);
        res.on('close',() => controller.abort());
        res.writeHead(200,{'Content-Type':'application/x-ndjson; charset=utf-8'});
        const emit = value => { if (!res.destroyed && !res.writableEnded) res.write(JSON.stringify(value)+'\n'); };
        try { await engine.run(input,emit,controller.signal); }
        catch (e) { emit({type:'error',error:cleanError(e,engine.apiKey)}); }
        finally { active.delete(controller); res.end(); }
        return;
      }
      if (req.method === 'POST' && path === '/api/ratings') {
        const data = await body(req), run = engine.lastRun;
        if (!run || data.id !== run.id || engine.busy) throw new Error('가장 최근에 완료한 비교만 평가할 수 있습니다.');
        const ratings = {};
        for (const slot of ['A','B','C']) {
          const value = data.ratings?.[slot];
          if (!value || !['미평가','승','무','패'].includes(value.preference) || typeof value.note !== 'string' || value.note.length > 2000) throw new Error('Invalid rating');
          const scores = {};
          for (const axis of ['korean','character','context','story']) {
            const n = value.scores?.[axis];
            if (!Number.isInteger(n) || n < 0 || n > 5) throw new Error('Invalid score');
            scores[axis] = n;
          }
          ratings[slot] = {preference:value.preference,note:value.note,scores};
        }
        run.ratings = ratings; run.ratedAfterReveal = data.revealed === true;
        if (!data.revealed && !run.revealAt) run.blindRatings = structuredClone(ratings);
        run.revealAt = run.revealAt ?? (data.revealed || data.reveal ? new Date().toISOString() : null);
        writeFileSync(join(engine.directory,`${run.id}.json`),JSON.stringify(run,null,2));
        return json(res,200,{saved:true});
      }
      if (req.method === 'GET' && path === '/api/export') {
        if (!engine.lastRun || engine.busy) throw new Error('완료된 비교가 없습니다.');
        res.setHeader('Content-Disposition',`attachment; filename="bakeoff-${engine.lastRun.id}.json"`);
        return json(res,200,engine.lastRun);
      }
      return json(res,404,{error:'Not found'});
    } catch (e) { return json(res,400,{error:cleanError(e,engine.apiKey)}); }
  });
  server.abortRuns = () => { for (const controller of active) controller.abort(); };
  return server;
}

async function main() {
  const envFile = join(here,'.env.local');
  if (existsSync(envFile)) process.loadEnvFile(envFile);
  const port = Number(process.env.BAKEOFF_PORT ?? 18767);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid BAKEOFF_PORT');
  const directory = join(here,'.local'); mkdirSync(directory,{recursive:true});
  const lock = join(directory,'server.lock');
  if (existsSync(lock)) {
    const pid = Number(readFileSync(lock,'utf8'));
    if (!Number.isInteger(pid) || pid <= 0) throw new Error('Invalid server.lock; inspect before restarting');
    let running = true;
    try { process.kill(pid,0); } catch (e) { if (e.code === 'ESRCH') running = false; }
    if (running) throw new Error('Another lab process holds this result directory');
    unlinkSync(lock);
  }
  writeFileSync(lock,String(process.pid),{flag:'wx'});
  let bridge;
  process.on('exit',() => { bridge?.close(); try { if (readFileSync(lock,'utf8') === String(process.pid)) unlinkSync(lock); } catch {} });
  const runtimeFile = join(root,'build/bakeoff/runtime.json');
  if (!existsSync(runtimeFile)) throw new Error('먼저 gradlew.bat prepareBakeoff bakeoffSmoke를 실행하세요.');
  const runtime = JSON.parse(readFileSync(runtimeFile,'utf8')), currentHash = sourceHash();
  if (runtime.sourceHash !== currentHash) throw new Error('Java 소스가 빌드와 다릅니다. gradlew.bat prepareBakeoff bakeoffSmoke를 다시 실행하세요.');
  let git = 'unavailable';
  try { git = execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8',windowsHide:true}).trim(); } catch {}
  bridge = new PromptBridge(runtimeFile);
  await bridge.call('prepare',{mode:'SANDBOX',input:'무슨 일인데?',history:[]});
  const ledger = new Ledger(directory,Number(process.env.BAKEOFF_BUDGET_USD ?? 5));
  const catalog = new Catalog();
  const labDigest = createHash('sha256');
  for (const file of ['server.mjs','engine.mjs','bridge.mjs','public/index.html','public/app.js','public/suggestions.js','public/style.css']) labDigest.update(file).update('\0').update(readFileSync(join(here,file)));
  labDigest.update('development-cases.json').update('\0').update(readFileSync(join(root,'docs/28_assets/development-cases.json')));
  const engine = new Engine({bridge,catalog,ledger,directory,apiKey:process.env.OPENROUTER_API_KEY ?? '',sourceVersion:{git,javaSourceHash:currentHash,labSourceHash:labDigest.digest('hex')}});
  const server = createLabServer({bridge,catalog,ledger,engine,port});
  await new Promise((resolve,reject) => { server.once('error',reject); server.listen(port,'127.0.0.1',resolve); });
  console.log(`Lucid bakeoff: http://127.0.0.1:${port} · key ${engine.apiKey ? 'configured' : 'missing'} · no inference until Run`);
  const stop = () => { server.abortRuns(); server.close(); setTimeout(() => process.exit(0),1500).unref(); };
  process.on('SIGINT',stop); process.on('SIGTERM',stop);
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(e => { console.error(cleanError(e,process.env.OPENROUTER_API_KEY)); process.exit(1); });
