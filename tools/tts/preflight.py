"""Read-only ElevenLabs account/model/official voice preflight. Never emits credentials."""
import argparse,json,re,urllib.request,urllib.error
from pathlib import Path
from datetime import datetime,timezone
SLUGS=('airi','yeonhwa','taeri','luna','claire','rosetta','chaerin','sierra','edel','seolah')
ROOT=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--config',type=Path,default=ROOT/'.local/elevenlabs.json');p.add_argument('--account',action='store_true');a=p.parse_args()
config=json.loads(a.config.read_text(encoding='utf-8-sig'))
assert isinstance(config.get('apiKey'),str) and len(config['apiKey'])>10,'API key is not filled'
voices=config.get('voiceIds',{})
assert set(voices)==set(SLUGS),'Official voice mapping differs from the 10-character list'
assert all(isinstance(v,str) and re.fullmatch(r'[A-Za-z0-9_-]{1,100}',v) for v in voices.values()),'Invalid or missing voice ID'
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None
opener=urllib.request.build_opener(NoRedirect())
def get(path):
    req=urllib.request.Request('https://api.elevenlabs.io'+path,headers={'xi-api-key':config['apiKey'],'Accept':'application/json','User-Agent':'LucidChat-TTS-preflight/1.0'})
    try:
        with opener.open(req,timeout=30) as r:return json.loads(r.read())
    except urllib.error.HTTPError as e:
        try:detail=json.loads(e.read()).get('detail',{})
        except Exception:detail={}
        reason=detail.get('status') if isinstance(detail,dict) else None
        safe=reason if reason in ('invalid_api_key','missing_permissions','quota_exceeded','unauthorized','invalid_authorization_header') else 'unclassified'
        raise RuntimeError('ElevenLabs metadata HTTP '+str(e.code)+' / '+safe) from None
out={'observedAt':datetime.now(timezone.utc).isoformat(),'configValid':True,'officialVoiceCount':len(voices),
     'sharedVoiceGroups':[[s for s in SLUGS if voices[s]==v] for v in set(voices.values()) if sum(voices[s]==v for s in SLUGS)>1]}
if a.account:
    models=get('/v1/models')
    out['models']=[{'id':m['model_id'],'supportsTts':m.get('can_do_text_to_speech'),'supportsDialogue':m.get('can_do_text_to_dialogue'),'korean':any(l.get('language_id') in ('ko','kor') for l in m.get('languages',[]))} for m in models if m['model_id'] in ('eleven_v4','eleven_v4_turbo')]
    out['voices']={slug:{'accessible':get('/v1/voices/'+voices[slug]).get('voice_id')==voices[slug]} for slug in SLUGS}
    sub=get('/v1/user/subscription')
    out['subscription']={k:sub.get(k) for k in ('tier','character_count','character_limit')}
    (ROOT/'.local/account-preflight.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(out,ensure_ascii=False))
