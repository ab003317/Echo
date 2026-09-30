# .env configuration

[繁體中文](configuration.md) · [README](README.en.md) · [AI providers and key registration](ai-providers.md) · [Deployment and maintenance](deployment.md)

## Prerequisites

The default HTTPS deployment requires a domain or subdomain with DNS control, such as `music.example.com`. Without a domain, search online for a free domain registration guide. Domain registration is not covered here.

Also required: a Linux server or NAS, Docker Engine, Docker Compose v2 and a music directory. AI features require an API key and available quota from the chosen provider. Music playback and offline storage do not require AI keys.

## Create and edit the file

`.env` is the server deployment configuration file, stored beside `compose.yaml`. Docker Compose reads it to configure containers, music mounts and AI services. The phone needs only the server address.

For the first deployment, run these commands in the project root:

```bash
cp .env.example .env
nano .env
```

Any text editor can replace `nano`. If `.env` already exists, edit it directly to preserve its settings. The filename is `.env`, not `.env.txt`.

- Use one `NAME=value` per line. Lines starting with `#` are comments. Keep one assignment per setting.
- `NAME=` is an empty value. Blank behavior differs by field, as described below.
- `[]` is an empty API key array. In audio settings, it behaves differently from a blank value.
- Replace placeholders such as `your-key` and `music.example.com` with actual values.
- For multiple keys, use outer single quotes and inner JSON double quotes. Compose reads single-quoted values literally, including `$`. See [Docker .env syntax](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/).

## Domain, files and connections

| Setting | Meaning and purpose | Value and where to obtain it |
| --- | --- | --- |
| `DOMAIN` | Public hostname used by built-in Caddy to obtain HTTPS certificates. | **Required for built-in HTTPS.** Use a domain/subdomain configured in the domain provider's DNS console, such as `music.example.com`. Omit `https://`, ports and `/music`. This field does not register a domain or change DNS. |
| `COMPOSE_PROFILES` | Enables the bundled HTTPS service in Compose. | Default: `https`, which starts Caddy. For an existing reverse proxy or tunnel, set `COMPOSE_PROFILES=` and let that service handle HTTPS. This is a local choice, with no registration required. |
| `MUSIC_PATH` | Music directory on the Docker host, mounted at `/music` inside the container. Echo reads music and writes imported tracks here. | Copy the host path from the server/NAS file manager, for example `/srv/music`. Default `./music` is beside the project; change it for an existing library. The directory must be readable and writable. Quote spaces, for example `MUSIC_PATH='/srv/Music Library'`. |
| `TZ` | Time zone used for daily analysis, mixes and discovery rotation. | Default: `Asia/Hong_Kong`. Obtain an IANA name from server time settings; Linux systems with systemd can list them with `timedatectl list-timezones`. Examples: `Asia/Taipei`, `Europe/London`. Do not leave blank or use `UTC+8`. |
| `BIND_ADDRESS` | Host network interface accepting connections to the HTTP port; not a domain or AI URL. | Default `127.0.0.1` allows host-local proxy access. Built-in Caddy reaches Echo over Docker networking, so keep this default. For direct HTTP access from other machines, use `0.0.0.0` or a host interface IP from its network settings; firewall rules still apply. |
| `HTTP_PORT` | Host HTTP port mapped to Echo. | Default: `18082`. If occupied, choose an unused port such as `18083` and update an existing proxy's upstream address. This does not change container port `18082` or Caddy ports `80/443`. |

Before starting built-in HTTPS, DNS must point to the server, and TCP ports 80/443 must be reachable and unused by other services. See [deployment and maintenance](deployment.md) for an existing proxy or LAN-only access.

With default HTTPS, enter `https://music.example.com/music` in the app. `HTTP_PORT` is a backend port and is not added to this HTTPS address.

## Listening behavior AI

These settings analyze listening time, completion, repeats and skips. An API key authenticates requests to the AI provider; the model ID selects a model; the API URL identifies the service receiving requests.

| Setting | Meaning and purpose | Value and where to obtain it |
| --- | --- | --- |
| `AI_PROVIDER` | Selects the request protocol and default endpoint/model. | Match the key issuer: `gemini`, `openai`, `deepseek` or `qwen`. Use `custom` for other OpenAI Chat Completions-compatible services. Default: `gemini`. Use `none` to disable behavioral AI; do not leave blank. |
| `AI_BASE_URL` | AI API base URL, independent of Echo's `DOMAIN`. | Obtain from provider API docs or the console. Blank uses official defaults for Gemini/OpenAI/DeepSeek; Qwen/custom require a URL. Example: `https://api.openai.com/v1`. Do not use a chat website URL or append `/chat/completions`; also omit `/interactions` for native Gemini. |
| `AI_MODEL` | Model API ID for behavioral analysis. | Obtain from the provider's model list. Blank selects an [Echo preset](ai-providers.md#官方預設--official-presets) for official providers; required for `custom`. The model must support the selected API protocol, text input and JSON output. Display names can differ from API IDs. |
| `AI_API_KEYS` | Credentials for the selected AI service. | Create keys through the [provider portals](ai-providers.md#api-access). Format: `'["actual-key"]'` or `'["key-one","key-two"]'`. Each pool must belong to one provider and endpoint. `[]` or blank means no keys, so behavioral AI is disabled. |
| `AI_PROXY_URL` | HTTP(S) proxy for behavioral AI requests. | Leave blank for direct access. Obtain an address from the proxy application's HTTP listener settings or its administrator, such as `http://host.docker.internal:7890`. The proxy must already be running and reachable from Docker; this field does not install a proxy or VPN. |

The [AI provider guide](ai-providers.md) includes key registration, model lists, official endpoints and examples. Providers determine API quota and billing; the key must have access to the configured model.

Unique keys rotate within the pool. HTTP 401/403 temporarily disables a key, with at most three keys attempted per request. HTTP 429 pauses the pool according to the provider's response. More keys do not necessarily increase quota shared by one account or project.

## Actual audio AI

These settings send audio excerpts to the configured provider for musical characteristics, including genre. Text models may not accept audio; Echo does not substitute song titles for audio analysis. Defaults sample at most 12 tracks per day and 60 seconds per track.

| Setting | Meaning and purpose | Value and where to obtain it |
| --- | --- | --- |
| `AUDIO_AI_PROVIDER` | Provider for audio analysis. | Default: `auto`; blank also means `auto`. It selects the main provider for Gemini/OpenAI and disables audio for other main providers. Explicit options: `gemini`, `openai`, `custom`; `none` disables audio analysis. |
| `AUDIO_AI_BASE_URL` | API base URL receiving audio. | Obtain from the audio provider's API docs. Blank reuses `AI_BASE_URL` when the provider matches; otherwise it selects the audio provider's official default. `custom` requires an explicit URL or inheritance from the same main provider. Omit API method paths. |
| `AUDIO_AI_MODEL` | API model ID with audio input support. | Obtain from the audio provider's model docs. When blank, Gemini reuses `AI_MODEL` only for the same provider and URL, otherwise its preset; OpenAI uses `gpt-audio-1.5`. Required for `custom`, whose endpoint must accept MP3 `input_audio`. |
| `AUDIO_AI_API_KEYS` | Audio provider credentials, using the same format as the main pool. | Obtain from the audio provider's [key portal](ai-providers.md#api-access). **Blank inherits `AI_API_KEYS` only when both provider and URL match.** Otherwise, supply separate keys. **`[]` disables the audio key pool without inheritance.** |
| `AUDIO_AI_PROXY_URL` | HTTP(S) proxy for audio requests. | Obtain from proxy settings or its administrator. Blank inherits `AI_PROXY_URL` only when provider and URL match; otherwise it connects directly. Format and restrictions match the main AI proxy. |

For a matching provider and URL, a blank audio proxy cannot override the main proxy with a direct connection: it inherits. Leave both proxy fields blank to make both connections direct.

When switching providers, also check URL, model and keys. Changing `AI_PROVIDER` does not clear manually entered values. Review all five `AUDIO_AI_*` fields when audio has separate settings.

## Proxies and VPNs

VPN/proxy requirements depend on **server networking, supported API regions and the account**. A VPN on the phone does not change the server's route. See [AI networking](ai-providers.md#network) for official region lists and provider differences.

`AI_PROXY_URL` and `AUDIO_AI_PROXY_URL` accept `http://` or `https://`, without credentials, query strings or fragments. SOCKS URLs and VPN subscription links are not accepted; obtain an HTTP proxy address from the installed proxy application. AI requests do not automatically read generic `HTTP_PROXY`/`HTTPS_PROXY` environment variables.

Inside a container, `127.0.0.1` refers to that container. Use `host.docker.internal` for a proxy on the Docker host; replace example port `7890` with the actual HTTP proxy port and allow connections from the container. Echo proxy fields can remain blank when a server VPN already routes container traffic.

## YouTube

| Setting | Meaning and purpose | Value and where to obtain it |
| --- | --- | --- |
| `YOUTUBE_PROXY_URL` | Proxy for yt-dlp search and YouTube imports, independent of AI proxies. | Obtain from proxy settings or its administrator, such as `http://host.docker.internal:7890`. It does not inherit `AI_PROXY_URL`. Leave blank when access works; yt-dlp then uses its default network settings. |
| `YOUTUBE_COOKIES_FILE` | Path to browser cookies used for sources requiring login; not an API key. | Optional; blank by default. Export a Netscape-format file from your browser using the [official yt-dlp instructions](https://github.com/yt-dlp/yt-dlp/wiki/FAQ#how-do-i-pass-cookies-to-yt-dlp). Save it as `cookies.txt` in the project root, mount it using the [deployment guide](deployment.md#youtube-cookies), then enter the **container path**, such as `/run/echo/cookies.txt`. |

Setting a cookies path does not mount or upload the file. Cookies contain session credentials; do not commit them to GitHub. Export and replace expired cookies. This setting does not sync YouTube Music favorites or accounts into Echo.

## Minimum changes

Apply these changes to a copy of `.env.example`, leaving other fields at their defaults.

**Built-in HTTPS with Gemini for behavior and audio:**

```dotenv
DOMAIN=music.example.com
MUSIC_PATH=/srv/music
AI_PROVIDER=gemini
AI_API_KEYS='["your-google-key"]'
```

**Without AI:**

```dotenv
DOMAIN=music.example.com
MUSIC_PATH=/srv/music
AI_PROVIDER=none
AI_API_KEYS='[]'
AUDIO_AI_PROVIDER=none
```

See the [AI provider guide](ai-providers.md) for OpenAI, DeepSeek, Qwen, custom URLs and separate audio providers. See the [deployment guide](deployment.md) for `COMPOSE_PROFILES` with an existing reverse proxy.

## Apply and check

Run from the directory containing `.env` and `compose.yaml`:

```bash
docker compose config --quiet
docker compose up -d --build
docker compose exec echo python -m server.check_config
```

The first command validates Compose without printing expanded keys. `check_config` reports the provider, model, key count, proxy status and `enabled` for both AI configurations. `keys=1` means one key was parsed; it does not verify that key's permissions or quota.

These checks do not call AI or verify DNS, connectivity or paid accounts. See [troubleshooting](deployment.md#故障排查--troubleshooting) for AI status and errors.

After later `.env` edits, apply them with `docker compose up -d`. `docker compose restart` alone does not load new environment variables. When switching away from built-in HTTPS, also stop the existing Caddy service as described in the deployment guide.
