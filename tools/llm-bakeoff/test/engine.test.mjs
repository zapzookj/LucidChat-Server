import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { Catalog, Engine, Ledger, MODELS, API, firstScene, readSse, requestFor, BASELINE_PROMPT, PROMPT_VARIANTS, prepareComparison, hash } from '../engine.mjs';

const endpoint = {tag:'unit-provider',name:'Unit Provider',input:.5,output:3,context:16384,maxOutput:10000,parameters:['response_format','temperature','reasoning'],tier:'default'};
const prepared = {mode:'SANDBOX',messages:[{role:'system',content:'고정 설정'},{role:'user',content:'안녕'}],maxTokens:6144};
const candidates=MODELS.slice(0,3).map(m=>({model:m.id,provider:endpoint.tag,reasoning:'default'}));
const input={mode:'SANDBOX',input:'안녕',history:[],candidates,timeoutMs:5000};
const raw=JSON.stringify({scenes:[{narration:'책장을 넘긴다.',dialogue:'안녕하세요. "반가워요" {웃음}',emotion:'JOY'}],stat_changes:{}});
function temporary(t){const dir=mkdtempSync(join(tmpdir(),'lucid-lab-test-'));t.after(()=>{if(!resolve(dir).startsWith(resolve(tmpdir())+'\\lucid-lab-test-')&&!resolve(dir).startsWith(resolve(tmpdir())+'/lucid-lab-test-'))throw new Error('Unsafe cleanup path');rmSync(dir,{recursive:true});});return dir;}
function sse({output=raw,finish='stop',done=true,usage={prompt_tokens:100,completion_tokens:50,cost:.001},extra}={}){
  const chunks=[{id:'gen-unit',model:'actual-model',provider:'actual-provider',choices:[{delta:{content:output},finish_reason:finish}]}];
  if(usage)chunks.push({choices:[{delta:{},finish_reason:finish}],usage});if(extra)chunks.push(extra);
  const text=': keepalive\r\n\r\n'+chunks.map(c=>'data: '+JSON.stringify(c)+'\r\n\r\n').join('')+(done?'data: [DONE]\n\n':'');
  const bytes=new TextEncoder().encode(text);let offset=0;
  return new Response(new ReadableStream({pull(c){if(offset===bytes.length){c.close();return;}const end=Math.min(offset+7,bytes.length);c.enqueue(bytes.slice(offset,end));offset=end;}}),{headers:{'Content-Type':'text/event-stream'}});
}
function fixture(t,options={}){
  const directory=temporary(t),ledger=new Ledger(directory,options.limit??5);let requests=[];
  const bridge={call:async op=>op==='prepare'?prepared:{valid:true,scenes:JSON.parse(raw).scenes,issues:[]}};
  const catalog={model:async id=>({id,endpoints:[endpoint]})};
  const fetcher=async(url,init)=>{requests.push({url,init});return sse();};
  return {engine:new Engine({directory,ledger,bridge,catalog,apiKey:'sk-or-unit-secret',fetcher,...options}),ledger,directory,requests};
}
test('SSE preserves UTF-8, CRLF, comments and final usage across fragmented chunks',async()=>{
  const response=sse(),frames=[];await readSse(response.body,f=>frames.push(f));assert.equal(frames.at(-1),'[DONE]');assert.equal(JSON.parse(frames[0]).choices[0].delta.content,raw);assert.equal(JSON.parse(frames[1]).usage.cost,.001);
});
test('first scene waits for complete quote-aware JSON with escaped delimiters',()=>{
  assert.equal(firstScene(raw.slice(0,35)),null);assert.equal(firstScene(raw).dialogue,'안녕하세요. "반가워요" {웃음}');assert.equal(firstScene('{"scenes":[{"narration":"","dialogue":""}]}'),null);
});
test('request pins provider, disables fallback, records unsupported sampling and reasoning',()=>{
  const request=requestFor(prepared,{...candidates[0],reasoning:'minimal'},endpoint);assert.deepEqual(request.body.provider.only,['unit-provider']);assert.equal(request.body.provider.allow_fallbacks,false);assert.deepEqual(request.body.provider.max_price,{prompt:1,completion:5});assert.deepEqual(request.omitted,['frequency_penalty','presence_penalty']);assert.deepEqual(request.body.reasoning,{effort:'minimal',exclude:true});
  assert.throws(()=>requestFor(prepared,{...candidates[0],reasoning:'unknown'},endpoint));assert.throws(()=>requestFor(prepared,candidates[0],{...endpoint,maxOutput:2048}));assert.throws(()=>requestFor(prepared,{...candidates[0],provider:'unlisted'},endpoint));
});
test('catalog excludes excessive prices, request fees, inactive and unsupported JSON endpoints',async()=>{
  const base={tag:'normal',status:0,context_length:100000,max_completion_tokens:10000,pricing:{prompt:'0.0000005',completion:'0.000003'},supported_parameters:['response_format']};
  const values=[base,{...base,tag:'high',pricing:{prompt:'0.000002',completion:'0.000003'}},{...base,tag:'fee',pricing:{...base.pricing,request:'0.01'}},{...base,tag:'inactive',status:1},{...base,tag:'no-json',supported_parameters:[]}];
  const catalog=new Catalog(async(url,init)=>{assert.equal(url,`${API}/models/${MODELS[0].id}/endpoints`);assert.equal(init.redirect,'error');return Response.json({data:{endpoints:values}});});
  assert.deepEqual((await catalog.model(MODELS[0].id)).endpoints.map(e=>e.tag),['normal']);await assert.rejects(catalog.model('http://evil'),/not allowed/);
});
test('three requests share messages and reserve before any dispatch; usage and random labels persist',async t=>{
  const {engine,ledger,requests,directory}=fixture(t);const events=[];const run=await engine.run(input,e=>{events.push(e.type);if(e.type==='start')assert.equal(ledger.entries.length,3);});
  assert.deepEqual(run.results.map(r=>r.slot),['A','B','C']);assert.ok(run.results.every(r=>r.status==='ok'&&r.costUsd===.001&&r.actualProvider==='actual-provider'));
  assert.equal(ledger.snapshot().known,.003);assert.equal(ledger.snapshot().held,0);assert.equal(requests.length,3);
  for(const {url,init}of requests){assert.equal(url,`${API}/chat/completions`);assert.equal(init.redirect,'error');assert.deepEqual(JSON.parse(init.body).messages,prepared.messages);}
  const file=readFileSync(join(directory,run.id+'.json'),'utf8');assert.ok(!file.includes('sk-or-unit-secret'));assert.equal(events.at(-1),'complete');
});
test('budget failure dispatches zero calls and reserves none atomically',async t=>{
  const {engine,ledger,requests}=fixture(t,{limit:.001});await assert.rejects(engine.run(input),/예산 부족/);assert.equal(requests.length,0);assert.equal(ledger.entries.length,0);assert.equal(engine.busy,false);
});
test('unknown usage remains reserved across process restarts',async t=>{
  const {engine,ledger,directory}=fixture(t,{fetcher:async()=>sse({usage:null})});const run=await engine.run(input);assert.ok(run.results.every(r=>r.costUsd===null));assert.ok(ledger.snapshot().held>0);assert.deepEqual(new Ledger(directory).snapshot(),ledger.snapshot());
});
test('truncated outputs cannot pass, while final reported costs still settle',async t=>{
  const {engine,ledger}=fixture(t,{fetcher:async()=>sse({finish:'length'})});const run=await engine.run(input);assert.ok(run.results.every(r=>r.status==='truncated'));assert.equal(ledger.snapshot().known,.003);
});
test('missing DONE is incomplete and reported partial usage does not release reservations',async t=>{
  const {engine,ledger}=fixture(t,{fetcher:async()=>sse({done:false})});const run=await engine.run(input);assert.ok(run.results.every(r=>r.status==='incomplete'&&r.costUsd===null));assert.ok(ledger.snapshot().held>0);
});
test('invalid DTO responses remain invalid with billed final cost',async t=>{
  const {engine,ledger}=fixture(t,{bridge:{call:async op=>{if(op==='prepare')return prepared;throw new Error('Bad JSON');}}});const run=await engine.run(input);assert.ok(run.results.every(r=>r.status==='invalid'));assert.equal(ledger.snapshot().known,.003);
});
test('mid-stream error is recorded and redacts upstream echoed credentials',async t=>{
  const {engine,ledger,directory}=fixture(t,{fetcher:async()=>sse({extra:{error:{message:'Bearer sk-or-unit-secret failed'}}})});const run=await engine.run(input);assert.ok(run.results.every(r=>r.status==='error'&&r.costUsd===null));assert.ok(ledger.snapshot().held>0);assert.ok(!readFileSync(join(directory,run.id+'.json'),'utf8').includes('sk-or-unit-secret'));
});
test('simultaneous run rejected; cancelled calls keep all uncertain reservations',async t=>{
  let dispatched=0,ready;const started=new Promise(r=>ready=r);
  const {engine,ledger}=fixture(t,{fetcher:async(url,{signal})=>new Promise((resolve,reject)=>{dispatched++;if(dispatched===3)ready();signal.addEventListener('abort',()=>reject(new Error('Aborted')),{once:true});})});
  const controller=new AbortController(),pending=engine.run(input,()=>{},controller.signal);await started;await assert.rejects(engine.run(input),/이미 비교/);controller.abort();const run=await pending;assert.ok(run.results.every(r=>r.status==='cancelled'));assert.equal(ledger.entries.length,3);assert.ok(ledger.snapshot().held>0);assert.equal(engine.busy,false);
});
test('observer exceptions do not interrupt billing settlement or leave concurrent jobs',async t=>{
  const {engine,ledger}=fixture(t);const run=await engine.run(input,()=>{throw new Error('Disconnected');});assert.equal(run.results.length,3);assert.equal(ledger.snapshot().known,.003);assert.equal(engine.busy,false);
});
test('pre-dispatch cancellation or missing key performs no paid call',async t=>{
  const {engine,ledger,requests}=fixture(t);const c=new AbortController();c.abort();await assert.rejects(engine.run(input,()=>{},c.signal),/Cancelled/);engine.apiKey='';await assert.rejects(engine.run(input),/OPENROUTER_API_KEY/);assert.equal(requests.length,0);assert.equal(ledger.entries.length,0);
});
test('request timeout aborts the upstream operation and never assumes zero billing',async t=>{
  const {engine}=fixture(t,{fetcher:async(url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(new Error('Timed out')),{once:true}))});
  const condition={candidate:candidates[0],endpoint,...requestFor(prepared,candidates[0],endpoint)};
  const result=await engine.generate(condition,prepared,'A',20,new AbortController().signal,()=>{});
  assert.equal(result.status,'timeout');assert.equal(result.costUsd,null);assert.ok(result.totalMs>=15);
});

const promptInput = () => ({...input, comparison:'prompt', candidates:PROMPT_VARIANTS.slice(0,3).map(v=>({...candidates[0],promptVersion:v.id}))});
function promptBridge(change=p=>p) {
  return {call:async(op,request)=>op==='prepare'?change({...prepared,promptVersion:request.promptVersion,
    fixture:'unit-fixed-state',fixtureProfile:{name:'로제타'},historyLogs:1,stateFrozen:true,
    messages:[{role:'system',content:request.promptVersion},{role:'user',content:request.input}]}):{valid:true,scenes:JSON.parse(raw).scenes,issues:[]}};
}

test('prompt comparison stores each exact request, common snapshot and randomized slot provenance',async t=>{
  let catalogReads=0;
  const {engine,ledger,requests,directory}=fixture(t,{bridge:promptBridge(),catalog:{model:async id=>{catalogReads++;return{id,endpoints:[endpoint]};}}});
  const run=await engine.run(promptInput());
  assert.equal(catalogReads,1,'one endpoint observation controls all three prompt requests');
  assert.equal(run.comparison,'prompt');assert.equal(run.preparedVariants.length,3);assert.ok(run.snapshotHash);
  assert.equal(new Set(run.results.map(r=>r.promptHash)).size,3);
  assert.deepEqual(run.results.map(r=>r.dispatchIndex).sort(),[0,1,2]);
  for(const result of run.results){
    const p=run.preparedVariants.find(p=>p.promptVersion===result.promptVersion);
    assert.deepEqual(result.request.messages,p.messages);assert.equal(result.promptHash,hash(p.messages));
    assert.equal(result.snapshotHash,run.snapshotHash);assert.equal(result.request.model,MODELS[0].id);
  }
  assert.equal(requests.length,3);assert.equal(ledger.entries.length,3);assert.equal(ledger.snapshot().known,.003);
  const saved=JSON.parse(readFileSync(join(directory,run.id+'.json'),'utf8'));
  assert.deepEqual(saved.preparedVariants,run.preparedVariants);assert.deepEqual(saved.results,run.results);
});

test('A/A prompt comparison permits repeated versions without changing input or sharing paid reservations',async t=>{
  const request=promptInput();request.candidates=request.candidates.map(c=>({...c,promptVersion:BASELINE_PROMPT}));
  let prepares=0;const bridge=promptBridge(),original=bridge.call;bridge.call=async(...args)=>{if(args[0]==='prepare')prepares++;return original(...args);};
  const {engine,ledger,requests}=fixture(t,{bridge});const run=await engine.run(request);
  assert.equal(prepares,1);assert.equal(new Set(run.results.map(r=>r.promptHash)).size,1);
  assert.equal(requests.length,3);assert.equal(ledger.entries.length,3);
});

test('mixed prompt controls and unknown versions are rejected before catalog, reservation or dispatch',async t=>{
  let catalogReads=0;const {engine,ledger,requests}=fixture(t,{bridge:promptBridge(),catalog:{model:async()=>{catalogReads++;throw new Error('Must not query');}}});
  for(const patch of [{model:MODELS[1].id},{provider:'other'},{reasoning:'minimal'},{promptVersion:'arbitrary'}]){
    const request=promptInput();Object.assign(request.candidates[1],patch);await assert.rejects(engine.run(request));
  }
  await assert.rejects(engine.run({...input,comparison:'other'}));
  await assert.rejects(engine.run({...input,candidates:input.candidates.map(c=>({...c,promptVersion:'S1-character-v1'}))}));
  assert.equal(catalogReads,0);assert.equal(ledger.entries.length,0);assert.equal(requests.length,0);assert.equal(engine.busy,false);
});

test('changed fixture, history, output budget, or wrong bridge version never dispatches',async t=>{
  for(const change of [p=>({...p,fixtureProfile:{name:'다른 인물'}}),p=>({...p,maxTokens:8000}),
    p=>({...p,messages:[...p.messages,{role:'assistant',content:'다른 기록'}]}),p=>({...p,promptVersion:BASELINE_PROMPT})]){
    const {engine,ledger,requests}=fixture(t,{bridge:promptBridge(p=>p.promptVersion===BASELINE_PROMPT?p:change(p))});
    await assert.rejects(engine.run(promptInput()),/일치하지/);assert.equal(ledger.entries.length,0);assert.equal(requests.length,0);
  }
});

test('oversize candidate fails before any inference even when baseline is small',async t=>{
  const {engine,ledger,requests}=fixture(t,{bridge:promptBridge(p=>p.promptVersion===BASELINE_PROMPT?p:{...p,messages:[{role:'system',content:'x'.repeat(100001)},...p.messages.slice(1)]})});
  await assert.rejects(engine.run(promptInput()),/100KB/);assert.equal(ledger.entries.length,0);assert.equal(requests.length,0);
});

test('free preview preserves selected variants and snapshot independent of candidate order',async()=>{
  const bridge=promptBridge(),request=promptInput();const first=await prepareComparison(bridge,request);
  const second=await prepareComparison(bridge,{...request,candidates:[...request.candidates].reverse()});
  assert.equal(first.snapshotHash,second.snapshotHash);assert.deepEqual(first.variants.map(p=>p.promptHash),second.variants.map(p=>p.promptHash).reverse());
  const repeated=await prepareComparison(bridge,request);assert.deepEqual(first,repeated);
  assert.deepEqual(await prepareComparison({call:async()=>prepared},input),prepared,'old model preview shape preserved');
});
