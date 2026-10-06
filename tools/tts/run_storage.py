import json, subprocess, sys
from pathlib import Path
root = Path(__file__).resolve().parents[2]
runtime = json.loads((root / 'build/bakeoff/runtime.json').read_text(encoding='utf-8'))
args = root / 'tools/tts/.local/storage.args'
args.write_text('-cp\n"' + runtime['classpath'].replace('\\', '/') + '"\ncom.spring.aichat.bakeoff.TtsStoragePrepare\n', encoding='utf-8')
result = subprocess.run([runtime['java'], '@' + str(args)], cwd=root, capture_output=True, text=True, encoding='utf-8')
print(result.stdout, end='')
if result.returncode: print('Storage preparation incomplete. Credentials were not printed.')
sys.exit(result.returncode)
