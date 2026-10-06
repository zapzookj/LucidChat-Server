import json, subprocess, sys
from pathlib import Path
root = Path(__file__).resolve().parents[2]
runtime = json.loads((root / 'build/bakeoff/runtime.json').read_text(encoding='utf-8'))
local = root / 'tools/tts/.local'
local.mkdir(exist_ok=True)
args = local / 'prepare.args'
args.write_text('-cp\n"' + runtime['classpath'].replace('\\', '/') + '"\ncom.spring.aichat.bakeoff.TtsPrepare\n'
    + 'tools/tts/.local/elevenlabs.json\ntools/tts/.local/greetings-v4\n' + (sys.argv[1] if len(sys.argv) > 1 else 'airi') + '\n', encoding='utf-8')
result = subprocess.run([runtime['java'], '@' + str(args)], cwd=root, capture_output=True, text=True, encoding='utf-8')
print(result.stdout, end='')
if result.returncode:
    print('Preparation did not complete. No automatic provider retry was made.')
sys.exit(result.returncode)
