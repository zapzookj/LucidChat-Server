"""Compare production official greeting text with local receipts without printing secrets/text."""
import argparse, hashlib, json, subprocess, re
from pathlib import Path
from datetime import datetime, timezone

root = Path(__file__).resolve().parents[2]
local = root / 'tools/tts/.local'
config = json.loads((local / 'elevenlabs.json').read_text(encoding='utf-8-sig'))
slugs = ('airi','yeonhwa','taeri','luna','claire','rosetta','chaerin','sierra','edel','seolah')
parser = argparse.ArgumentParser()
parser.add_argument('--samples', type=Path)
options = parser.parse_args()
samples = options.samples or local / 'greetings-selected'
if not options.samples and not samples.exists(): samples = local / 'greetings-v4'
if {p.stem for p in samples.glob('*.json')} != set(slugs):
    raise SystemExit('Expected exactly ten official sample receipts; no network operations were made.')
receipts, audio_hashes = {}, {}
for slug in slugs:
    receipt = json.loads((samples / (slug + '.json')).read_text(encoding='utf-8'))
    audio_hash = hashlib.sha256((samples / (slug + '.mp3')).read_bytes()).hexdigest()
    expected_audio_hash = receipt.get('audioSha256')
    selected = samples.resolve() != (local / 'greetings-v4').resolve() or 'source' in receipt
    if selected and (not isinstance(expected_audio_hash, str) or not re.fullmatch(r'[0-9a-f]{64}', expected_audio_hash)):
        raise SystemExit('Selected sample is missing a valid SHA-256 receipt; no network operations were made.')
    if expected_audio_hash and audio_hash != expected_audio_hash:
        raise SystemExit('Selected audio hash mismatch; no network operations were made.')
    receipts[slug], audio_hashes[slug] = receipt, audio_hash
sql = "SELECT COALESCE(json_agg(t),'[]') FROM (SELECT slug,first_greeting FROM characters WHERE source='OFFICIAL' AND slug IN (" + ','.join("'" + s + "'" for s in slugs) + ")) t;"
result = subprocess.run(['C:/Windows/System32/OpenSSH/ssh.exe', '-i', 'C:/Users/zapza/.ssh/lucid_deploy',
    '-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','root@141.164.37.146',
    'docker exec -i lucid-postgres psql -U postgres -d lucidchat -t -A -q -v ON_ERROR_STOP=1'],
    input=sql, capture_output=True, text=True, encoding='utf-8')
if result.returncode: raise SystemExit('Production greeting read failed; raw output withheld.')
rows = json.loads(result.stdout)
assert len(rows) == 10 and {row['slug'] for row in rows} == set(slugs), 'Production official set differs'
proof = {'observedAt':datetime.now(timezone.utc).isoformat(), 'expected':10,'found':len(rows),'greetings':[]}
proof['sampleSet'] = samples.name
for row in rows:
    receipt = receipts[row['slug']]
    expected = hashlib.sha256((row['first_greeting'] + '|' + config['voiceIds'][row['slug']]).encode('utf-8')).hexdigest()
    audio_hash = audio_hashes[row['slug']]
    expected_audio_hash = receipt.get('audioSha256')
    selected = samples.resolve() != (local / 'greetings-v4').resolve() or 'source' in receipt
    proof['greetings'].append({'slug':row['slug'],'matchesProductionTextAndVoice':receipt['state'] == 'DONE' and receipt['hash'] == expected,
        'matchesSelectedAudio':audio_hash == expected_audio_hash if selected or expected_audio_hash else True, 'audioSha256':audio_hash})
proof['matched'] = sum(item['matchesProductionTextAndVoice'] for item in proof['greetings'])
(root / 'docs/35_assets/production-greeting-verification.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
print('Production greeting set:',proof['found'],'| matching prepared text/voice:',proof['matched'])
if proof['matched'] != 10 or not all(c['matchesSelectedAudio'] for c in proof['greetings']): raise SystemExit('Prepared sample mismatch; no synthesis or DB writes were made.')
