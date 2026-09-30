"""Exercise a newly built echo:test image without AI calls or source downloads."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import uuid
import wave


def main():
    name = 'echo-smoke-' + uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory() as directory:
        music = Path(directory)
        with wave.open(str(music / 'test.wav'), 'wb') as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(8000)
            audio.writeframes(b'\0' * 8000 * 2 * 5)
        subprocess.run(['docker', 'run', '-d', '--name', name, '-e', 'AI_PROVIDER=none',
                        '-e', 'POOL_ENABLED=false', '-p', '127.0.0.1::18082',
                        '-v', str(music) + ':/music', 'echo:test'], check=True, capture_output=True)
        try:
            binding = subprocess.check_output(['docker', 'port', name, '18082/tcp'], text=True).strip()
            base = 'http://' + binding + '/music'
            for _ in range(60):
                try:
                    health = json.load(urllib.request.urlopen(base + '/api/health', timeout=2))
                    break
                except (OSError, ValueError):
                    time.sleep(1)
            else:
                raise RuntimeError('Container did not become ready')
            assert health['ok'] and health['instanceId'] and not health['aiConfigured']
            tracks = json.load(urllib.request.urlopen(base + '/api/library'))['tracks']
            assert len(tracks) == 1
            request = urllib.request.Request(base + '/api/tracks/' + tracks[0]['id'] + '/audio',
                                             headers={'Range': 'bytes=0-127'})
            with urllib.request.urlopen(request) as response:
                assert response.status == 206 and len(response.read()) == 128
            page = urllib.request.urlopen(base).read().decode()
            assert '繁體中文' in page and 'English' in page
            print('Docker 音樂串流檢查通過 / Docker music streaming check passed')
        except Exception:
            subprocess.run(['docker', 'logs', '--tail=40', name], check=False)
            raise
        finally:
            subprocess.run(['docker', 'rm', '-f', name], check=False, capture_output=True)


if __name__ == '__main__':
    main()
