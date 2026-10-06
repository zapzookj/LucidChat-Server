"""Prepare private storage metadata with empty credentials in an ignored file."""
import json, subprocess
from pathlib import Path
root = Path(__file__).resolve().parents[2]
if (root / 'tools/tts/.local/storage.json').exists(): raise SystemExit('Existing local storage configuration preserved.')
command = ['C:/Windows/System32/OpenSSH/ssh.exe', '-i', 'C:/Users/zapza/.ssh/lucid_deploy',
    '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', 'root@141.164.37.146',
    'docker inspect lucid-app --format "{{json .Config.Env}}"']
result = subprocess.run(command, capture_output=True, encoding='utf-8')
if result.returncode: raise SystemExit('Cannot read storage configuration; no credentials printed.')
env = dict(item.split('=', 1) for item in json.loads(result.stdout) if '=' in item)
data = {'accessKey': '', 'secretKey': '',
    'endpoint': env.get('S3_ENDPOINT', ''), 'region': env.get('AWS_REGION', 'auto'),
    'publicBucket': env.get('S3_BUCKET', 'lucid-chat-assets-v2'), 'privateBucket': 'lucid-chat-tts-private'}
assert data['endpoint'], 'Storage endpoint missing'
local = root / 'tools/tts/.local'; local.mkdir(exist_ok=True)
(local / 'storage.json').write_text(json.dumps(data), encoding='utf-8')
print('Prepared private storage metadata with empty accessKey and secretKey. Supply a private-bucket key.')
