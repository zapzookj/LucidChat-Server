"""Compare production official greeting text with local receipts without printing secrets/text."""
import hashlib, json, subprocess
from pathlib import Path
from datetime import datetime, timezone

root = Path(__file__).resolve().parents[2]
local = root / 'tools/tts/.local'
config = json.loads((local / 'elevenlabs.json').read_text(encoding='utf-8-sig'))
slugs = ('airi','yeonhwa','taeri','luna','claire','rosetta','chaerin','sierra','edel','seolah')
sql = "SELECT COALESCE(json_agg(t),'[]') FROM (SELECT slug,first_greeting FROM characters WHERE slug IN (" + ','.join("'" + s + "'" for s in slugs) + ")) t;"
result = subprocess.run(['C:/Windows/System32/OpenSSH/ssh.exe', '-i', 'C:/Users/zapza/.ssh/lucid_deploy',
    '-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','root@141.164.37.146',
    'docker exec -i lucid-postgres psql -U postgres -d lucidchat -t -A -q -v ON_ERROR_STOP=1'],
    input=sql, capture_output=True, text=True, encoding='utf-8')
if result.returncode: raise SystemExit('Production greeting read failed; raw output withheld.')
rows = json.loads(result.stdout)
assert len(rows) == 10 and {row['slug'] for row in rows} == set(slugs), 'Production official set differs'
proof = {'observedAt':datetime.now(timezone.utc).isoformat(), 'expected':10,'found':len(rows),'greetings':[]}
for row in rows:
    receipt = json.loads((local / 'greetings-v4' / (row['slug'] + '.json')).read_text(encoding='utf-8'))
    expected = hashlib.sha256((row['first_greeting'] + '|' + config['voiceIds'][row['slug']]).encode('utf-8')).hexdigest()
    proof['greetings'].append({'slug':row['slug'],'matchesProductionTextAndVoice':receipt['state'] == 'DONE' and receipt['hash'] == expected})
proof['matched'] = sum(item['matchesProductionTextAndVoice'] for item in proof['greetings'])
(root / 'docs/35_assets/production-greeting-verification.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
print('Production greeting set:',proof['found'],'| matching prepared text/voice:',proof['matched'])
if proof['matched'] != 10: raise SystemExit('Prepared sample mismatch; no synthesis or DB writes were made.')
