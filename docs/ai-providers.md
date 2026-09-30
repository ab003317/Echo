# AI 渠道 / AI providers

文件核對日期 / Documentation checked: 2026-10-01.

[.env 逐項設定](configuration.md) · [.env field reference](configuration.en.md)

<a id="api-access"></a>

## API key 與模型的取得方式 / Obtain API keys and model IDs

API key 是渠道簽發的程式呼叫憑證，填入 `AI_API_KEYS`，不是登入密碼。
API URL 是接收請求的服務位址，填入 `AI_BASE_URL`；模型的 API ID 填入 `AI_MODEL`。
Echo 的網域填在 `DOMAIN`，與這三項不同。

An API key is a provider-issued credential for `AI_API_KEYS`, not a login password.
The API service address goes in `AI_BASE_URL`, and the model's API ID in `AI_MODEL`.
Echo's own hostname belongs in `DOMAIN`, separate from these AI settings.

| 渠道 | 申請入口與步驟 | 模型 ID 與 API URL 取得位置 |
| --- | --- | --- |
| Gemini | 登入 [Google AI Studio 的 API Keys](https://aistudio.google.com/apikey)，建立或選擇專案後建立 API key，複製到 `AI_API_KEYS`。見 [Google key 文件](https://ai.google.dev/gemini-api/docs/api-key)。 | [Gemini 模型清單](https://ai.google.dev/gemini-api/docs/models)提供 API 模型名稱；使用官方服務時 `AI_BASE_URL` 留空。 |
| OpenAI | 登入 [OpenAI Platform 的 API keys](https://platform.openai.com/api-keys)，在使用的專案內建立 API key，複製到 `AI_API_KEYS`。見 [OpenAI 官方入門文件](https://developers.openai.com/api/docs/quickstart)。 | [OpenAI 模型清單](https://developers.openai.com/api/docs/models)提供模型 ID 與支援介面；官方 `AI_BASE_URL` 可留空，或填 `https://api.openai.com/v1`。 |
| DeepSeek | 登入 [DeepSeek Platform 的 API keys](https://platform.deepseek.com/api_keys)，建立 key 後複製到 `AI_API_KEYS`。 | [官方 API 文件](https://api-docs.deepseek.com/guides/harness)列出模型 ID 及端點；官方 `AI_BASE_URL` 可留空，或填 `https://api.deepseek.com`。 |
| Qwen | 依 [Model Studio 的 key 申請步驟](https://www.alibabacloud.com/help/en/model-studio/get-api-key)登入控制台，選地區與工作空間，於 API Key 頁面建立 key。 | 使用同地區、同工作空間的 OpenAI 相容 Base URL，保留 `/compatible-mode/v1` 路徑，見下方 Qwen 範例。模型 ID 見 [Model Studio 模型清單](https://www.alibabacloud.com/help/en/model-studio/models)。`AI_BASE_URL` 必填，地區須與 key 一致。 |
| custom | 從所用服務的 API key 管理頁取得，或向自架端點管理員索取。 | 向同一服務取得 OpenAI Chat Completions 相容根位址、模型 ID 與 key，三者必須相配；URL 與模型不能留空。 |

| Provider | Key registration | Model ID and API URL source |
| --- | --- | --- |
| Gemini | Sign in to [Google AI Studio API Keys](https://aistudio.google.com/apikey), create/select a project, create a key and copy it to `AI_API_KEYS`. See [Google's key guide](https://ai.google.dev/gemini-api/docs/api-key). | Use API model names from the [Gemini model list](https://ai.google.dev/gemini-api/docs/models); leave `AI_BASE_URL` blank for the official service. |
| OpenAI | Sign in to [OpenAI Platform API keys](https://platform.openai.com/api-keys), create a key in the intended project and copy it to `AI_API_KEYS`. See the [official OpenAI quickstart](https://developers.openai.com/api/docs/quickstart). | The [OpenAI model list](https://developers.openai.com/api/docs/models) provides IDs and supported interfaces. Leave the official URL blank or use `https://api.openai.com/v1`. |
| DeepSeek | Sign in to [DeepSeek Platform API keys](https://platform.deepseek.com/api_keys), create a key and copy it to `AI_API_KEYS`. | The [official API guide](https://api-docs.deepseek.com/guides/harness) lists models and endpoints. Leave the official URL blank or use `https://api.deepseek.com`. |
| Qwen | Follow [Model Studio key registration](https://www.alibabacloud.com/help/en/model-studio/get-api-key): select a region/workspace and create a key on its API Key page. | Use that region/workspace's OpenAI-compatible base URL, including `/compatible-mode/v1`, as in the Qwen example below. Obtain IDs from the [Model Studio model list](https://www.alibabacloud.com/help/en/model-studio/models). `AI_BASE_URL` is required, and its region must match the key. |
| custom | Use the service's API key management page or ask the endpoint administrator. | Obtain a matching Chat Completions-compatible base URL, model ID and key from that service. URL and model are required. |

建立 key 後，確認該帳戶／專案有可用額度及所選模型權限。可用模型與費用以渠道控制台為準。
自訂、Qwen 與 OpenAI 模型須支援 Echo 使用的 Chat Completions 介面；音訊另需音訊輸入支援。

After creating a key, check the account/project quota and model access in the provider console.
Custom, Qwen and OpenAI models must support Echo's Chat Completions interface;
audio additionally requires audio input support.

## 官方預設 / Official presets

| AI_PROVIDER | 預設模型 / Default model | URL | 音訊 / Audio |
| --- | --- | --- | --- |
| gemini | gemini-3.8-flash | https://generativelanguage.googleapis.com/v1beta | 原生 Interactions / Native Interactions |
| openai | gpt-4.1-mini | https://api.openai.com/v1 | 自動另用 gpt-audio-1.5 / Separate audio model |
| deepseek | deepseek-flash | https://api.deepseek.com | 預設停用 / Disabled by default |
| qwen | qwen-plus | 需填所屬地區／工作空間 URL / Region and workspace URL required | 預設停用 / Disabled by default |
| custom | 必填 / Required | 必填 / Required | 需明確設定音訊模型 / Explicit audio model required |

OpenAI 相容模式使用 `POST {AI_BASE_URL}/chat/completions`。支援文字 JSON 回覆，
並在程式內驗證事實依據；音訊模式要求支援 `input_audio` MP3。
只提供 Responses API 或語音轉文字 API 的端點不屬於此相容介面。

Compatible mode uses `POST {AI_BASE_URL}/chat/completions`. Responses must contain
JSON, which Echo validates against the evidence. Audio requires MP3 `input_audio`
support. A Responses-only or transcription-only endpoint is not compatible.

### Gemini

```dotenv
AI_PROVIDER=gemini
AI_BASE_URL=
AI_MODEL=gemini-3.8-flash
AI_API_KEYS='["your-google-key"]'
AUDIO_AI_PROVIDER=auto
```

留空 URL 使用官方預設；音訊沿用同渠道、模型和 key。
Blank URL selects the official preset; audio reuses this provider, model and keys.
官方文件 / Official docs: [audio understanding](https://ai.google.dev/gemini-api/docs/audio).

### OpenAI

```dotenv
AI_PROVIDER=openai
AI_BASE_URL=
AI_MODEL=gpt-4.1-mini
AI_API_KEYS='["your-openai-key"]'
AUDIO_AI_PROVIDER=auto
AUDIO_AI_MODEL=gpt-audio-1.5
```

文字分析與音訊分析使用不同模型；音訊模型須支援音訊輸入。
Text and audio analysis use separate models; the audio model must support audio input.
官方文件 / Official docs: [GPT-4.1 mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini),
[audio Chat Completions](https://developers.openai.com/api/docs/guides/audio-chat-completions).

### DeepSeek

```dotenv
AI_PROVIDER=deepseek
AI_BASE_URL=
AI_MODEL=deepseek-flash
AI_API_KEYS='["your-deepseek-key"]'
AUDIO_AI_PROVIDER=none
```

可作聆聽行為分析。要做曲風歌單，可另外設定 Gemini／OpenAI 音訊渠道。
Use for behavioral analysis. Configure a separate Gemini/OpenAI audio channel for
audio-based style mixes.
官方文件 / Official docs: [DeepSeek API](https://api-docs.deepseek.com/).

### Qwen / Alibaba Cloud Model Studio

```dotenv
AI_PROVIDER=qwen
AI_BASE_URL=https://YOUR_WORKSPACE_ID.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1
AI_MODEL=qwen-plus
AI_API_KEYS='["your-key-from-the-same-region"]'
AUDIO_AI_PROVIDER=none
```

這是新加坡工作空間範例；請把 YOUR_WORKSPACE_ID 換成真實 ID。
其他地區請從控制台複製該工作空間的 OpenAI 相容根位址。key 的建立地區必須與端點一致。
不要把 Qwen-Audio 模型直接套入此範例；它的協定支援與文字模型不同。

This example uses a Singapore workspace. Replace YOUR_WORKSPACE_ID with your ID.
For other regions, copy the workspace's compatible base URL from the console.
Keys must match the endpoint region. Do not substitute Qwen-Audio directly;
its supported protocol differs from this text example.
官方文件 / Official docs: [Model Studio compatibility](https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope).

### 自訂相容端點 / Custom compatible endpoint

```dotenv
AI_PROVIDER=custom
AI_BASE_URL=https://ai.example.com/v1
AI_MODEL=your-model-id
AI_API_KEYS='["your-key"]'
AUDIO_AI_PROVIDER=none
```

不加 `/chat/completions`；端點由程式補上。若自架服務不驗證 key，可填一個非空佔位值，
例如 `local`。自訂模式不強制使用供應商特定的 JSON mode 參數，但回覆仍須是合法 JSON。

Omit `/chat/completions`; Echo appends it. For a local endpoint without key
authentication, use a nonempty placeholder such as `local`. Custom mode avoids
provider-specific JSON-mode parameters, but still requires valid JSON output.

## 多組 API key / Multiple API keys

```dotenv
AI_API_KEYS='["first-key","second-key","third-key"]'
```

- 外層單引號是 Compose 的字面值包裹；內層 JSON 使用雙引號。請勿填
  `key1,key2`、多行 key 或 `[key1,key2]`。
  Outer single quotes preserve the value in Compose; JSON strings use double
  quotes. Do not use comma-separated, multiline or unquoted array items.
- 同池只能放同渠道、同端點適用的 key；不混放 Google 和 OpenAI 的 key。
  Each pool belongs to one provider and endpoint. Never mix Google and OpenAI keys.
- 重複 key 自動去除，請求輪流使用；401／403 暫停該 key 一小時，
  每次最多嘗試三組。429 會暫停該池，遵守 Retry-After，不輪流消耗所有 key。
  Duplicates are removed and requests rotate between keys. Authentication failures
  cool a key down for an hour, with at most three attempted keys per request.
  HTTP 429 pauses the pool according to Retry-After instead of exhausting keys.
- 網路或模型錯誤不觸發 key 輪替，模型維持指定設定。
  Network or model errors do not trigger key rotation or model substitution.

檢查格式而不呼叫 AI / Validate without an AI request:

```bash
docker compose exec echo python -m server.check_config
```

只輸出渠道、模型、key 數量及代理是否啟用，不會輸出 key。
Only provider, model, key count and proxy status are printed; never the keys.

## 獨立音訊渠道 / Separate audio channel

行為分析使用 DeepSeek、音訊分析使用 Gemini / DeepSeek for behavioral analysis, Gemini for audio analysis:

```dotenv
AI_PROVIDER=deepseek
AI_API_KEYS='["your-deepseek-key"]'
AUDIO_AI_PROVIDER=gemini
AUDIO_AI_MODEL=gemini-3.8-flash
AUDIO_AI_API_KEYS='["your-google-key-one","your-google-key-two"]'
AUDIO_AI_PROXY_URL=
```

只有渠道與 URL **都相同**時，空白的 AUDIO_AI_API_KEYS 才繼承主 key 池；
不同端點不會收到主渠道的金鑰。填 `[]` 可明確停用該音訊池。
Blank audio keys inherit only when both provider and endpoint match. A different
endpoint never receives the main provider's credentials. Use `[]` to disable the
audio key pool explicitly.

音訊以匿名 ID 傳送，移除檔案標籤；每首抽取最多三段、共 60 秒。歌名、歌手、專輯
不會附在音訊請求中。模型判斷仍是片段層級的推測，並非人工核實的整首曲風。

Audio requests use anonymous IDs with file tags removed, sampling up to three
excerpts totaling 60 seconds per track. Titles, artists and albums are omitted.
Model findings are hypotheses about the excerpts, not verified whole-track genres.

<a id="network"></a>

## 網路、VPN與代理 / Network, VPN and proxy

**先確認渠道支援伺服器／帳戶所在區域，再確認能否直連。**
沒有「選某模型一定需要 VPN」的固定規則；同一模型放在不同渠道，連線條件也不同。
代理只改變網路路由，不會自動取得服務資格或解決錯誤的 key、模型 ID。

**Check provider availability for your server and account region, then test direct
connectivity.** There is no universal rule that a model requires a VPN. Providers
hosting the same model may have different access conditions. A proxy changes
routing, not eligibility, key validity or model permissions.

OpenAI／Google 的 API 有官方地區清單。中國大陸伺服器通常不能直接連到這些服務；
香港及其他地區也應以 API 清單為準，不能從消費者聊天 App 可用推斷 API 可用。
DeepSeek／Qwen 通常可在中國大陸直連；仍需核對所選端點、地區與帳戶。

OpenAI and Google publish API region lists. Mainland China servers generally
cannot reach these services directly. For Hong Kong and elsewhere, consult the
API list rather than assuming consumer chat-app availability implies API access.
DeepSeek/Qwen are commonly reachable from mainland China; endpoint, region and
account configuration still matter.

官方地區文件 / Official region references:
[OpenAI](https://developers.openai.com/api/docs/supported-countries),
[Gemini](https://ai.google.dev/gemini-api/docs/available-regions).

```dotenv
AI_PROXY_URL=http://host.docker.internal:7890
YOUTUBE_PROXY_URL=http://host.docker.internal:7890
```

兩者獨立，可只填一項。容器中的 127.0.0.1 是容器本身；Compose 已加入
`host.docker.internal` 對應。主機代理必須監聽容器可達的介面。音訊換渠道時，
另填 AUDIO_AI_PROXY_URL。VPN 若已接管伺服器出口，可先不填代理。

These routes are independent; either may be blank. Container localhost is not the
host. Compose maps `host.docker.internal`; the host proxy must listen on an
interface reachable from Docker. A separate audio provider needs its own
AUDIO_AI_PROXY_URL. If a VPN already routes server traffic, try leaving proxies blank.

改完設定後重建容器環境 / Recreate after configuration changes:

```bash
docker compose up -d
```
