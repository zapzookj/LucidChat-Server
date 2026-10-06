"""Prepare a credential-free local audition directory. Does not generate or upload audio."""
import html, json, shutil
from pathlib import Path

root = Path(__file__).resolve().parents[2]
local = root / 'tools/tts/.local'
review = local / 'greeting-review'
review.mkdir(exist_ok=True)
names = {'airi':'아이리','yeonhwa':'연화','taeri':'서태리','luna':'백루나','claire':'클레어','rosetta':'로제타','chaerin':'강채린','sierra':'시에라','edel':'에델','seolah':'류설아'}
cards = []
for slug, name in names.items():
    receipt = json.loads((local / 'greetings-v4' / (slug + '.json')).read_text(encoding='utf-8'))
    assert receipt['state'] == 'DONE'
    shutil.copyfile(local / 'greetings-v4' / (slug + '.mp3'), review / (slug + '.mp3'))
    cards.append('<article><h2>' + html.escape(name) + '</h2><audio controls preload="none" src="' + slug + '.mp3"></audio><p>eleven_v4 · 첫 후보 · 합성 요청 ' + str(round(receipt['seconds'],2)) + '초</p></article>')
page = '<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>공식 10종 첫인사 보이스</title><style>body{background:#11121e;color:#e9e6f2;font:16px system-ui;margin:32px auto;padding:0 20px;max-width:1000px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}article{background:#201d30;border:1px solid #423757;border-radius:16px;padding:20px}h2{font-size:18px}p{color:#b0a5bc;font-size:13px}audio{width:100%}</style><h1>공식 10종 첫인사 보이스</h1><p>실제 v4 사전 제작 샘플입니다. 각 캐릭터의 첫 후보이며, 사람의 최종 품질 선정은 아직 하지 않았습니다. 이 화면의 재생에는 API 호출이나 에너지 차감이 없습니다.</p><main>' + ''.join(cards) + '</main></html>'
(review / 'index.html').write_text(page,encoding='utf-8')
print('Prepared credential-free audition page with exactly 10 local MP3 clips.')
