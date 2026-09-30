# Echo

[繁體中文](../README.md) · [Download Android](https://github.com/ab003317/Echo/releases/latest) · [AI configuration](ai-providers.md)

A private music server and Android player with background playback, offline music,
daily discovery and recommendations informed by listening behavior.

- Background and lock-screen playback, queue, shuffle, repeat and listening history.
- Automatic Wi-Fi downloads from recent, frequent, favorite and recommended music,
  with a storage budget, manual retention and deletion.
- A prepared discovery pool: heard tracks stay, frequently played tracks become
  permanent, and part of the unheard pool rotates daily.
- Daily artist, series, style and random mixes. Save a mix to keep its edition.
  Style mixes require actual audio evidence.
- AI uses listening time, repeats, completion and intentional skips.
  **Song titles are never treated as evidence of genre.**
- Traditional Chinese and English in the app and deployment documentation.

Each deployment shares one library, history and preference profile. It is intended
for an individual or household with shared preferences, and has no separate user
accounts or app access keys. Anyone who can reach the address can operate the
instance. Use a private network or access control in your existing reverse proxy
when needed.

## Quick start

Requirements: a Linux server or NAS, Docker Engine and Docker Compose v2.
The app requires Android 8.0 or later.

```bash
git clone https://github.com/ab003317/Echo.git
cd Echo
cp .env.example .env
```

Edit at least these values:

```dotenv
DOMAIN=music.example.com
MUSIC_PATH=/path/to/your/music
AI_API_KEYS='["your-gemini-api-key"]'
```

The default is Gemini `gemini-3.8-flash` for both behavioral and audio analysis.
Replace the example key with your real key. Leaving keys as `[]` keeps the library,
playback, offline downloads and non-AI recommendations available. AI requests
consume your provider quota and may incur charges.

```bash
docker compose up -d --build
docker compose exec echo python -m server.check_config
```

Point DNS at the server and allow inbound TCP ports 80 and 443. Included Caddy
obtains HTTPS certificates automatically. For an existing proxy, tunnel, occupied
ports or LAN-only access, use the [deployment guide](deployment.md).

Open `https://music.example.com/music`, install the [APK](https://github.com/ab003317/Echo/releases/latest),
and enter that address on first launch. **The phone needs only the server address;
AI keys stay on the server.**

## AI configuration

Every setting in [.env.example](../.env.example) has Traditional Chinese and English
comments. [Provider examples](ai-providers.md) cover official channels, model IDs,
OpenAI-compatible base URLs, multiple keys, audio and network routing.

| Setting | Purpose |
| --- | --- |
| `AI_PROVIDER` | `gemini`, `openai`, `deepseek`, `qwen`, `custom`, or `none` |
| `AI_BASE_URL` | API base without `/chat/completions`; required for custom and Qwen |
| `AI_MODEL` | Model ID; blank selects the provider preset |
| `AI_API_KEYS` | One key or `'["key-one","key-two"]'` |
| `AI_PROXY_URL` | Optional HTTP(S) AI proxy |
| `AUDIO_AI_*` | Separate audio provider, model, base URL and keys |
| `YOUTUBE_PROXY_URL` | Separate YouTube proxy |

VPN/proxy requirements depend on server networking, supported regions and account
eligibility, not simply the model name. Check the [official sources](ai-providers.md).

AI analyzes the previous day's listening evidence daily. Audio analysis samples
at most 12 tracks per day and up to 60 seconds per track. These clips are sent to
your chosen provider. Set `AUDIO_AI_PROVIDER=none` to disable audio; behavioral
analysis continues without inventing musical characteristics.

## Data and maintenance

Music uses a host bind mount. The database, favorites, history, mixes and artwork
live in the `echo_data` Docker volume. Pool rotation never deletes your original
music files; automatic cleanup applies only to Echo-managed discovery tracks.
YouTube import/search relies on yt-dlp and source availability. It does not sync
your YouTube Music account. Import only audio you have permission to use.

See [deployment and maintenance](deployment.md) for updates, backups, restore, NAS
permissions, cookies and proxy setup.

No project license has been granted at this stage. Public source availability
does not grant permission to modify or redistribute it. Third-party components
retain their own licenses; see [THIRD_PARTY.md](../THIRD_PARTY.md).

## Development

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r server/requirements.lock
python -m pytest server -q
# Direct execution does not load .env; export environment variables first.
python -m uvicorn server.app:app --host 127.0.0.1 --port 18082
```

For Android, install JDK 17+ and Android SDK 36:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

See [Android build instructions](android.md) for release signing. CI checks Python,
Android and Docker. CI debug APKs are for testing; use Releases for the regular app.
