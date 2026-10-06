"""Prepare a credential-free local audition directory. Does not generate or upload audio."""
import html, json, shutil
from pathlib import Path

root = Path(__file__).resolve().parents[2]
local = root / 'tools/tts/.local'
review = local / 'greeting-review'
review.mkdir(exist_ok=True)
names = {'airi':'아이리','yeonhwa':'연화','taeri':'서태리','luna':'백루나','claire':'클레어','rosetta':'로제타','chaerin':'강채린','sierra':'시에라','edel':'에델','seolah':'류설아'}
cards = []
samples = local / 'greetings-selected'
if not samples.exists(): samples = local / 'greetings-v4'
for slug, name in names.items():
    receipt = json.loads((samples / (slug + '.json')).read_text(encoding='utf-8'))
    assert receipt['state'] == 'DONE'
    shutil.copyfile(samples / (slug + '.mp3'), review / (slug + '.mp3'))
    label = '사용자 교체본' if receipt.get('source') == 'user-selected-replacement' else '기존 유지본'
    cards.append('<article><h2>' + html.escape(name) + '</h2><audio controls preload="metadata" src="' + slug + '.mp3?v=' + receipt.get('audioSha256','original')[:12] + '"></audio><p>' + label + '</p></article>')
page = '<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>공식 10종 첫인사 보이스</title><style>body{background:#11121e;color:#e9e6f2;font:16px system-ui;margin:32px auto;padding:0 20px;max-width:1000px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}article{background:#201d30;border:1px solid #423757;border-radius:16px;padding:20px}h2{font-size:18px}p{color:#b0a5bc;font-size:13px}audio{width:100%}</style><h1>공식 10종 첫인사 보이스</h1><p>사용자가 청취 후 선정한 7개 교체본과 기존 유지 3개입니다. 이 화면의 재생에는 API 호출이나 에너지 차감이 없습니다.</p><main>' + ''.join(cards) + '</main></html>'
(review / 'index.html').write_text(page,encoding='utf-8')
print('Prepared credential-free audition page with exactly 10 local MP3 clips.')
