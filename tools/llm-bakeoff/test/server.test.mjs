import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createLabServer, evaluationCaseMetadata } from '../server.mjs';
import { fileURLToPath } from 'node:url';

// fetch overrides Sec-Fetch-Mode; raw HTTP preserves the browser navigation headers under test.
const rawRequest=(url,headers={})=>new Promise((resolve,reject)=>{
  const req=http.get(url,{headers},res=>{let body='';res.setEncoding('utf8');res.on('data',chunk=>body+=chunk);res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body}));});req.on('error',reject);
});

async function setup(t){
  let prepares=0,runs=0;const secret='sk-or-server-only-test';
  const bridge={call:async()=>{prepares++;return{messages:[{role:'user',content:'합성 입력'}]};}};
  const engine={apiKey:secret,busy:false,sourceVersion:'unit',run:async()=>{runs++;throw new Error(`upstream echoes ${secret}`);}};
  const server=createLabServer({bridge,engine,catalog:{all:async()=>[]},ledger:{snapshot:()=>({limit:5,known:0,held:0,available:5})},port:0,capability:'unit-capability'});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>{server.closeAllConnections();server.close(r);}));
  const url=`http://127.0.0.1:${server.address().port}`;
  const request=(path,init={})=>fetch(url+path,{...init,headers:{Origin:url,'X-Lab-Capability':'unit-capability',...init.headers}});
  return{url,request,counts:()=>({prepares,runs}),secret,engine};
}
test('valid local bootstrap/status exposes no server key and includes browser security headers',async t=>{
  const {request,secret}=await setup(t);const page=await request('/');const html=await page.text();assert.ok(html.includes('unit-capability'));assert.ok(!html.includes(secret));assert.ok(page.headers.get('content-security-policy').includes("frame-ancestors 'none'"));assert.equal(page.headers.get('access-control-allow-origin'),null);
  const status=await request('/api/status');assert.equal(status.status,200);const text=await status.text();assert.ok(!text.includes(secret));assert.equal(JSON.parse(text).keyReady,true);
});
test('fixture metadata shares session protections and never invokes inference',async t=>{
  const {request,url,counts,secret}=await setup(t);
  assert.equal((await fetch(url+'/api/fixture')).status,403);
  for(const headers of [{Origin:'https://evil.test'},{'X-Lab-Capability':''},{'Sec-Fetch-Site':'cross-site'}])
    assert.equal((await request('/api/fixture',{headers})).status,403);
  assert.deepEqual(counts(),{prepares:0,runs:0});
  const response=await request('/api/fixture');assert.equal(response.status,200);
  assert.ok(!(await response.text()).includes(secret));assert.deepEqual(counts(),{prepares:1,runs:0});
});

test('prompt catalog and development cases require local session and perform no inference',async t=>{
  const {request,url,counts,secret}=await setup(t);
  for(const path of ['/api/prompt-variants','/api/evaluation-cases']){
    assert.equal((await fetch(url+path)).status,403);
    assert.equal((await request(path,{headers:{Origin:'https://evil.test'}})).status,403);
    const response=await request(path);assert.equal(response.status,200);
    const text=await response.text();assert.ok(!text.includes(secret));
    const data=JSON.parse(text);assert.ok((data.variants??data.cases).length>0);
  }
  assert.deepEqual(counts(),{prepares:0,runs:0});
});

test('evaluation provenance distinguishes edited inputs and continued histories from the original case',()=>{
  const cases=JSON.parse(readFileSync(fileURLToPath(new URL('../../../docs/28_assets/development-cases.json',import.meta.url)),'utf8'));
  const item=cases.cases[0],input={evaluationCaseId:item.id,mode:item.mode,input:item.input,history:[]};
  const original=evaluationCaseMetadata(input);
  assert.equal(original.id,item.id);assert.equal(original.familyId,item.familyId);assert.equal(original.version,cases.version);
  assert.equal(original.inputMatches,true);assert.equal(original.modeMatches,true);assert.equal(original.initialHistory,true);
  const edited=evaluationCaseMetadata({...input,input:'수정한 대사',mode:item.mode==='STORY'?'SANDBOX':'STORY',history:[{input:'이전'}]});
  assert.equal(edited.inputMatches,false);assert.equal(edited.modeMatches,false);assert.equal(edited.initialHistory,false);
  assert.equal(edited.sourceHash,original.sourceHash);assert.equal(evaluationCaseMetadata({}),null);
  assert.throws(()=>evaluationCaseMetadata({...input,evaluationCaseId:'../unknown'}),/Unknown evaluation case/);
});
test('bad Host, cross-site request, missing capability and foreign Origin do not dispatch',async t=>{
  const {request,url,counts}=await setup(t);
  const hostStatus=await new Promise((resolve,reject)=>{const req=http.get(url+'/',{headers:{Host:'evil.test'}},res=>{res.resume();resolve(res.statusCode);});req.on('error',reject);});assert.equal(hostStatus,403);
  for(const headers of [{Origin:'https://evil.test'},{'X-Lab-Capability':''},{'Sec-Fetch-Site':'cross-site'}]){
    const res=await request('/api/run',{method:'POST',headers:{'Content-Type':'application/json',...headers},body:'{}'});assert.equal(res.status,403);
  }
  assert.equal((await fetch(url+'/api/status')).status,403);assert.deepEqual(counts(),{prepares:0,runs:0});
});
test('clicked cross-site links can open only the top-level entry document',async t=>{
  const {url,counts,secret}=await setup(t);
  const navigation={'Sec-Fetch-Site':'cross-site','Sec-Fetch-Mode':'navigate','Sec-Fetch-Dest':'document','Sec-Fetch-User':'?1'};
  const page=await rawRequest(url+'/',navigation);assert.equal(page.status,200);assert.ok(page.headers['content-type'].startsWith('text/html'));assert.ok(!page.body.includes(secret));
  for(const headers of [
    {...navigation,'Sec-Fetch-Mode':'cors'},
    {...navigation,'Sec-Fetch-Dest':'iframe'},
    {...navigation,'Sec-Fetch-User':'?0'},
    {...navigation,Host:'evil.test'}
  ])assert.equal((await rawRequest(url+'/',headers)).status,403);
  for(const path of ['/api/status','/api/export','/app.js'])assert.equal((await rawRequest(url+path,{...navigation,'X-Lab-Capability':'unit-capability'})).status,403);
  assert.deepEqual(counts(),{prepares:0,runs:0});
});
test('localhost entry redirects to the canonical origin without widening API hosts',async t=>{
  const {url,counts}=await setup(t);const port=new URL(url).port;
  const headers={Host:`localhost:${port}`,'Sec-Fetch-Site':'cross-site','Sec-Fetch-Mode':'navigate','Sec-Fetch-Dest':'document','Sec-Fetch-User':'?1'};
  const response=await rawRequest(url+'/',headers);assert.equal(response.status,302);assert.equal(response.headers.location,url+'/');assert.ok(!response.body.includes('unit-capability'));
  assert.equal((await rawRequest(url+'/',{Host:`localhost:${port}`})).status,302);
  assert.equal((await rawRequest(url+'/api/status',{Host:`localhost:${port}`,Origin:`http://localhost:${port}`,'X-Lab-Capability':'unit-capability'})).status,403);
  assert.equal((await rawRequest(url+'/',{Host:`localhost.evil.test:${port}`})).status,403);assert.deepEqual(counts(),{prepares:0,runs:0});
});
test('preview uses only bridge, inference errors redact keys, arbitrary routes cannot read files',async t=>{
  const {request,counts,secret}=await setup(t);
  const preview=await request('/api/prepare',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});assert.equal(preview.status,200);assert.deepEqual(counts(),{prepares:1,runs:0});
  const run=await request('/api/run',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});const text=await run.text();assert.ok(text.includes('[REDACTED]'));assert.ok(!text.includes(secret));assert.equal(counts().runs,1);
  assert.equal((await request('/.env.local')).status,404);assert.equal((await request('/api/prepare',{method:'POST',headers:{'Content-Type':'text/plain'},body:'{}'})).status,400);
});
test('blind ratings survive later revealed edits and appear in result export',async t=>{
  const {request,engine}=await setup(t);const directory=mkdtempSync(join(tmpdir(),'lucid-ratings-test-'));
  t.after(()=>{assert.ok(resolve(directory).startsWith(resolve(tmpdir())));rmSync(directory,{recursive:true});});
  engine.directory=directory;engine.lastRun={id:'unit-run'};
  const ratings=Object.fromEntries(['A','B','C'].map(slot=>[slot,{preference:'무',note:'샘플',scores:{korean:4,character:4,context:4,story:4}}]));
  const save=data=>request('/api/ratings',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:'unit-run',ratings,...data})});
  assert.equal((await save({revealed:false,reveal:true})).status,200);ratings.A.scores.korean=1;
  assert.equal((await save({revealed:true})).status,200);
  const exported=await(await request('/api/export')).json();assert.equal(exported.blindRatings.A.scores.korean,4);assert.equal(exported.ratings.A.scores.korean,1);assert.ok(exported.revealAt);assert.equal(exported.ratedAfterReveal,true);
  assert.deepEqual(JSON.parse(readFileSync(join(directory,'unit-run.json'),'utf8')),exported);
  assert.equal((await save({id:'another-run'})).status,400);
});
