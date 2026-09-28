// Offline UI verification only: all inference and catalog traffic is replaced in-process.
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { mkdirSync } from 'node:fs';
import { PromptBridge } from '../bridge.mjs';
import { Engine, Ledger, MODELS } from '../engine.mjs';
import { createLabServer } from '../server.mjs';
const root=fileURLToPath(new URL('../../../',import.meta.url));
const directory=join(root,'build/bakeoff/ui-test-results');mkdirSync(directory,{recursive:true});
const bridge=new PromptBridge(join(root,'build/bakeoff/runtime.json'));
const endpoint={tag:'test-only',name:'Offline',input:.5,output:3,context:16384,maxOutput:10000,parameters:['response_format','temperature'],tier:'default',observedAt:new Date().toISOString()};
const models=MODELS.map(m=>({...m,name:`[오프라인 UI 샘플] ${m.name}`,endpoints:[endpoint]}));
const catalog={all:async()=>models,model:async id=>models.find(m=>m.id===id)};
const ledger=new Ledger(directory,5);
const fetcher=async(url,options)=>{
  const request=JSON.parse(options.body),story=request.messages.some(m=>m.content.includes('system_updates'));
  const scene={speaker:'로제타',narration:'[UI 테스트용 수동 샘플] 찻잔을 내려놓는다.',dialogue:'로제타: 후훗~ 제법이네? 이 로제타 님 앞에서 눈도 안 피하고.',emotion:'JOY'};
  const stats={intimacy:0,affection:0,dependency:0,playfulness:0,trust:0};
  const raw=JSON.stringify(story?{scenes:[scene,scene,scene],system_updates:{topic_concluded:false,stat_changes:{101:stats}}}:{scenes:[scene],stat_changes:stats});
  const chunks=[{id:'unit-ui',model:'OFFLINE-SAMPLE',provider:'OFFLINE-SAMPLE',choices:[{delta:{content:raw},finish_reason:'stop'}]},{usage:{prompt_tokens:100,completion_tokens:50,cost:0},choices:[]}];
  return new Response(chunks.map(x=>'data: '+JSON.stringify(x)+'\n\n').join('')+'data: [DONE]\n\n',{headers:{'Content-Type':'text/event-stream'}});
};
const engine=new Engine({bridge,catalog,ledger,directory,apiKey:'OFFLINE-TEST-ONLY',fetcher,sourceVersion:'OFFLINE-UI-SAMPLE'});
const server=createLabServer({bridge,catalog,ledger,engine,port:18768});server.listen(18768,'127.0.0.1',()=>console.log('Offline UI samples: http://127.0.0.1:18768 · NO EXTERNAL INFERENCE'));
process.on('exit',()=>bridge.close());process.on('SIGINT',()=>process.exit(0));process.on('SIGTERM',()=>process.exit(0));
