import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { PromptBridge } from '../bridge.mjs';
import { sourceHash } from '../server.mjs';
import { readFileSync } from 'node:fs';

test('actual Java bridge starts on Windows and shares service mapper, sanitization, ordering and history window',async t=>{
  const runtime=fileURLToPath(new URL('../../../build/bakeoff/runtime.json',import.meta.url));
  assert.equal(JSON.parse(readFileSync(runtime,'utf8')).sourceHash,sourceHash(),'Run gradlew.bat prepareBakeoff bakeoffSmoke after Java edits');
  const bridge=new PromptBridge(runtime);t.after(()=>bridge.close());
  const fixture=await bridge.call('fixture',{});
  assert.equal(fixture.id,'official-rosetta-stranger-v1');assert.equal(fixture.name,'로제타');assert.equal(fixture.age,20);
  assert.equal(fixture.worldId,'FANTASY_ACADEMY');assert.equal(fixture.difficulty,'HARD');
  assert.deepEqual(fixture.locations.map(l=>l.key).sort(),['ALCHEMY_LAB','ARENA','ASTRONOMY_TOWER','DORMITORY','ENCHANTED_FOREST','GARDEN_OF_ACADEMY','GREAT_HALL','LIBRARY_TOWER']);
  assert.match(fixture.speechQuirks,/이 로제타 님께서/);assert.match(fixture.storyBehaviorGuide,/Never SHY visibly/);
  const scene={speaker:'로제타',narration:'로제타: 찻잔을 내려놓았다.',dialogue:'로제타: 후훗~ 제법이네?',emotion:'JOY'};
  const stats={intimacy:0,affection:0,dependency:0,playfulness:0,trust:0};
  for(const mode of ['SANDBOX','STORY']){
    const raw=JSON.stringify(mode==='SANDBOX'?{scenes:[scene],stat_changes:stats,extra_field:'Spring ignores this'}:{scenes:[scene,scene,scene],system_updates:{topic_concluded:false,stat_changes:{101:stats}},extra_field:'Spring ignores this'});
    const parsed=await bridge.call('validate',{mode,raw});assert.equal(parsed.valid,true);assert.equal(parsed.scenes[0].dialogue,'후훗~ 제법이네?');
    if(mode==='SANDBOX')assert.equal((await bridge.call('validate',{mode,raw:JSON.stringify({scenes:[scene,scene],stat_changes:stats})})).valid,true,'Active V1 prompt allows multiple scenes');
    const request={mode,input:'지금 입력',history:[]};const first=await bridge.call('prepare',request);assert.deepEqual(await bridge.call('prepare',request),first);
    assert.equal(first.fixture,fixture.id);assert.deepEqual(first.fixtureProfile,fixture);
    const systems=first.messages.filter(m=>m.role==='system').map(m=>m.content).join('\n');
    for(const key of ['personality','tone','speechQuirks','backstory','coreValues','flaws','world'])assert.ok(systems.includes(fixture[key].trim()),`${mode}: missing seed ${key}`);
    const all=first.messages.map(m=>m.content).join('\n');
    assert.ok(all.includes(fixture.introNarration));assert.ok(all.includes(fixture.firstGreeting));
    assert.doesNotMatch(all,/서윤|BOOKSHOP|오래된 도서관 정문|28세 도서관 사서/);
    assert.match(systems,/STRANGER/);
    if(mode==='STORY')for(const loc of fixture.locations)assert.ok(systems.includes(loc.key),`Missing location: ${loc.key}`);
    assert.equal(first.messages.filter(m=>m.role==='user'&&m.content==='지금 입력').length,1);
    const live=await bridge.call('prepare',{...request,promptVersion:'P1-service-repairs-v1'});
    assert.equal(live.parentPromptVersion,first.promptVersion);
    assert.equal(live.baselineMessagesHash,first.baselineMessagesHash);
    assert.notDeepEqual(live.messages,first.messages,'P1 must use repaired production output, not frozen P0');
    assert.deepEqual(live.messages.filter(m=>m.role!=='system'),first.messages.filter(m=>m.role!=='system'));
    assert.deepEqual(live.messages.map(m=>[m.role,m.cache_control]),first.messages.map(m=>[m.role,m.cache_control]));
    assert.match(live.messages[0].content,/Appearance/);
    assert.match(live.messages[0].content,/Default Clothing/);
    assert.ok(live.messages.some(m=>m.role==='system'&&m.content.includes('유효 JSON')),'Live output-format block missing');
    if(mode==='STORY')assert.doesNotMatch(live.messages[0].content,/### Extended Backstory/);
    request.history=Array.from({length:12},(_,i)=>({input:`과거 ${i}`,raw}));const long=await bridge.call('prepare',request);
    assert.equal(long.historyLogs,20);assert.equal(long.messages.filter(m=>m.role!=='system').length,mode==='SANDBOX'?21:20);
    assert.equal(long.messages.filter(m=>m.role==='user'&&m.content==='지금 입력').length,1);
    const invalid=raw.replace('"trust":0','"trust":99');assert.equal((await bridge.call('validate',{mode,raw:invalid})).valid,false);
    await assert.rejects(bridge.call('prepare',{mode,input:'다음',history:[{input:'이전',raw:invalid}]}),/invalid response/);
  }
});
