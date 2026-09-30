# .env 設定

[English](configuration.en.md) · [README](../README.md) · [AI 渠道與申請入口](ai-providers.md) · [部署與維護](deployment.md)

## 部署前準備

預設 HTTPS 部署須自備可管理 DNS 的網域或子網域，例如 `music.example.com`。沒有網域時，請自行上網搜尋「免費網域申請指南」；本文件不提供網域申請教學。

另需 Linux 伺服器／NAS、Docker Engine、Docker Compose v2，以及存放音樂的資料夾。啟用 AI 時，需要所選渠道的 API key 與可用額度。只播放、保存音樂不需要 AI key。

## 建立與編輯

`.env` 是伺服器部署設定檔，與 `compose.yaml` 放在同一個目錄。Docker Compose 讀取它來設定容器、音樂掛載路徑與 AI 服務。手機只填伺服器位址。

首次部署時，在專案根目錄執行：

```bash
cp .env.example .env
nano .env
```

`nano` 可換成其他文字編輯器。已有 `.env` 時直接編輯，避免用範例覆蓋原有設定。檔名是 `.env`，不是 `.env.txt`。

- 每行一項 `名稱=值`，`#` 開頭的行是註解；同一項設定保留一行。
- `NAME=` 表示空字串，各欄位的留空行為不同，見下表。
- `[]` 表示空的 API key 陣列；在音訊設定中，它與留空的行為不同。
- `your-key`、`music.example.com` 等是佔位範例，需要替換成實際值。
- 多 key 使用外層單引號、內層 JSON 雙引號。Compose 將單引號內的內容按原文讀取，包含 `$`。見 [Docker .env 格式](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)。

## 網域、檔案與連線

| 設定 | 是什麼、有什麼用 | 填寫方式與取得位置 |
| --- | --- | --- |
| `DOMAIN` | 對外連線的網域，供內建 Caddy 申請 HTTPS 憑證。 | **使用內建 HTTPS 時必填。** 從網域服務商的 DNS 控制台取得已設定的網域／子網域。例：`music.example.com`，不加 `https://`、埠號或 `/music`。填入本欄不會註冊網域或修改 DNS。 |
| `COMPOSE_PROFILES` | 決定是否啟動 Compose 的內建 HTTPS 服務。 | 預設 `https`：啟動 Caddy。使用既有反向代理或 Tunnel 時填空值 `COMPOSE_PROFILES=`，由既有服務處理 HTTPS。此值自行選擇，無需申請。 |
| `MUSIC_PATH` | Docker 主機的音樂資料夾，掛載為容器內的 `/music`。Echo 從此讀取音樂，也會寫入匯入歌曲。 | 從伺服器／NAS 檔案管理器複製主機路徑，例如 `/srv/music`。預設 `./music` 指專案旁的資料夾；使用既有音樂庫須修改。資料夾須可讀寫，有空格時可填 `MUSIC_PATH='/srv/Music Library'`。 |
| `TZ` | 每日分析、歌單與探索輪換所用的時區。 | 預設 `Asia/Hong_Kong`。從伺服器時區設定取得 IANA 名稱；使用 systemd 的 Linux 可用 `timedatectl list-timezones` 查詢，例如 `Asia/Taipei`、`Europe/London`。不要留空或填 `UTC+8`。 |
| `BIND_ADDRESS` | 主機 HTTP 連接埠接受連線的網路介面；不是網域或 AI URL。 | 預設 `127.0.0.1`，供同主機的代理存取。內建 Caddy 透過 Docker 網路連到 Echo，可保留此值。需要其他機器直接連入 HTTP 時用 `0.0.0.0` 或主機指定介面的 IP，仍受防火牆限制。IP 從主機網路設定取得。 |
| `HTTP_PORT` | 主機映射到 Echo 的 HTTP 連接埠。 | 預設 `18082`；被佔用時自行選擇未使用的埠，例如 `18083`，並同步修改既有代理的上游位址。它不改變容器內的 `18082`，也不改變內建 Caddy 的 `80/443`。 |

內建 HTTPS 啟動前，網域 DNS 必須指向伺服器，TCP 80／443 必須可達且未被其他服務佔用。已有代理或只用內網的設定見[部署與維護](deployment.md)。

使用預設 HTTPS 時，App 填 `https://music.example.com/music`。`HTTP_PORT` 是後端埠，不加到這個 HTTPS 位址。

## 聆聽行為 AI

這組設定用於分析聆聽時間、完成、重播及跳過記錄。API key 是 AI 渠道簽發的呼叫憑證；模型 ID 指定使用哪個模型；API URL 指定向哪個服務發出請求。

| 設定 | 是什麼、有什麼用 | 填寫方式與取得位置 |
| --- | --- | --- |
| `AI_PROVIDER` | 決定 AI 請求協定及預設端點／模型。 | 按 key 所屬渠道選 `gemini`、`openai`、`deepseek`、`qwen`；其他 OpenAI Chat Completions 相容服務選 `custom`。預設 `gemini`，停用行為 AI 填 `none`，不要留空。 |
| `AI_BASE_URL` | AI 的 API 根位址，與 Echo 的 `DOMAIN` 無關。 | 從渠道的 API 文件或控制台取得。Gemini／OpenAI／DeepSeek 留空使用官方預設；Qwen／custom 必填。例：`https://api.openai.com/v1`。不填聊天網站網址，不加 `/chat/completions`；Gemini 原生端點也不加 `/interactions`。 |
| `AI_MODEL` | 行為分析使用的模型 API ID。 | 從渠道的模型清單取得。官方渠道留空使用 [Echo 預設](ai-providers.md#官方預設--official-presets)；`custom` 必填。模型須支援所選 API 協定、文字輸入及 JSON 回覆；顯示名稱不一定等於 API ID。 |
| `AI_API_KEYS` | 呼叫所選 AI 服務的憑證。 | 在[渠道申請入口](ai-providers.md#api-access)建立，填 `'["實際key"]'`。多組填 `'["key-one","key-two"]'`，同池只放同渠道、同端點的 key。`[]` 或留空代表沒有 key，行為 AI 不啟用。 |
| `AI_PROXY_URL` | 行為 AI 請求使用的 HTTP(S) 代理。 | 可直連時留空。需要代理時，從代理軟體的 HTTP 監聽設定或管理員取得位址，例：`http://host.docker.internal:7890`。必須是已運作且 Docker 可達的代理；填入網址不會安裝代理或 VPN。 |

API key 的建立位置、模型清單、官方端點及各渠道範例見 [AI 渠道指南](ai-providers.md)。API 額度與費用由渠道計算；key 必須有權呼叫所填模型。

多組 key 會去重後輪替。401／403 暫停該 key，一次請求最多嘗試三組；429 依渠道回覆暫停整個池。增加 key 數量不代表增加同一帳戶或專案的共用額度。

## 實際音訊 AI

這組設定把音訊片段傳送至指定渠道，取得曲風等聆聽線索。文字模型不一定支援音訊；Echo 不以歌名替代音訊分析。音訊預設每日最多抽樣 12 首，每首最多 60 秒。

| 設定 | 是什麼、有什麼用 | 填寫方式與取得位置 |
| --- | --- | --- |
| `AUDIO_AI_PROVIDER` | 音訊分析的渠道。 | 預設 `auto`，留空也按 `auto` 處理：主渠道為 Gemini／OpenAI 時使用該渠道，其餘停用音訊。可另選 `gemini`、`openai`、`custom`；`none` 停用音訊分析。 |
| `AUDIO_AI_BASE_URL` | 接收音訊的 API 根位址。 | 從音訊渠道 API 文件取得。留空時，與主渠道相同就沿用 `AI_BASE_URL`，否則使用音訊渠道的官方預設。`custom` 必須填入或從相同主渠道繼承；不加 API 方法路徑。 |
| `AUDIO_AI_MODEL` | 支援音訊輸入的模型 API ID。 | 從音訊渠道模型文件取得。留空時，同渠道、同 URL 的 Gemini 沿用 `AI_MODEL`，其他 Gemini 使用其預設；OpenAI 使用 `gpt-audio-1.5`。`custom` 必填，且相容端點須支援 MP3 `input_audio`。 |
| `AUDIO_AI_API_KEYS` | 音訊渠道的 API key，格式與主 key 池相同。 | 到該音訊渠道的[申請入口](ai-providers.md#api-access)取得。**留空只有在渠道與 URL 均相同時才繼承 `AI_API_KEYS`**；不同渠道／URL 須另外填寫。**`[]` 明確停用音訊 key 池，不會繼承。** |
| `AUDIO_AI_PROXY_URL` | 音訊請求使用的 HTTP(S) 代理。 | 從代理軟體設定或管理員取得。留空時，渠道與 URL 均相同就繼承 `AI_PROXY_URL`，否則直連。填寫格式及限制與主 AI 代理相同。 |

同渠道且同 URL 的音訊代理不能以空值覆蓋成直連；空值會繼承主代理。需要兩者直連時，將兩個代理欄位均留空。

切換渠道時，同時檢查 URL、模型及 key；原本手動填入的值不會因更換 `AI_PROVIDER` 自動清空。音訊有獨立設定時，也需檢查五個 `AUDIO_AI_*` 欄位。

## 代理與 VPN

VPN／代理是否需要使用，取決於**伺服器網路、API 渠道支援地區及帳戶**。不是在手機安裝 VPN 就會改變伺服器出口。官方地區清單及渠道差異見 [AI 網路設定](ai-providers.md#network)。

`AI_PROXY_URL` 與 `AUDIO_AI_PROXY_URL` 接受 `http://` 或 `https://`，不接受 SOCKS URL、帳密、查詢參數或片段。VPN 訂閱連結不能直接填入；需從已安裝的代理程式取得 HTTP 代理位址。AI 請求不自動讀取 `HTTP_PROXY`／`HTTPS_PROXY` 等通用環境變數。

容器內 `127.0.0.1` 是容器本身。代理在 Docker 主機時可用 `host.docker.internal`；範例埠 `7890` 必須換成實際 HTTP 代理埠，且代理需允許容器連入。伺服器 VPN 已接管容器流量時，可將 Echo 代理欄位留空。

## YouTube

| 設定 | 是什麼、有什麼用 | 填寫方式與取得位置 |
| --- | --- | --- |
| `YOUTUBE_PROXY_URL` | yt-dlp 搜尋及匯入 YouTube 時使用的代理，與 AI 代理獨立。 | 從代理程式設定或管理員取得，例如 `http://host.docker.internal:7890`。不會繼承 `AI_PROXY_URL`。可正常存取時留空，交由 yt-dlp 使用預設網路設定。 |
| `YOUTUBE_COOKIES_FILE` | 來源要求登入時使用的瀏覽器 cookies 檔案路徑；不是 API key。 | 選填，預設留空。從自己的瀏覽器依 [yt-dlp 官方匯出說明](https://github.com/yt-dlp/yt-dlp/wiki/FAQ#how-do-i-pass-cookies-to-yt-dlp)取得 Netscape 格式檔案，放在專案根目錄的 `cookies.txt`。按[部署指南](deployment.md#youtube-cookies)掛載後填**容器路徑**，例如 `/run/echo/cookies.txt`。 |

僅設定 cookies 路徑不會掛載或上傳檔案。cookies 含登入資料，不提交至 GitHub；過期時須重新匯出並替換。它不會將 YouTube Music 的收藏或帳戶同步進 Echo。

## 最少需要修改哪些項目

以下範例是修改 `.env.example` 複本中的對應項目，其餘保留預設。

**內建 HTTPS，使用 Gemini 的行為與音訊分析：**

```dotenv
DOMAIN=music.example.com
MUSIC_PATH=/srv/music
AI_PROVIDER=gemini
AI_API_KEYS='["your-google-key"]'
```

**不啟用 AI：**

```dotenv
DOMAIN=music.example.com
MUSIC_PATH=/srv/music
AI_PROVIDER=none
AI_API_KEYS='[]'
AUDIO_AI_PROVIDER=none
```

OpenAI、DeepSeek、Qwen、自訂 URL 與分開音訊渠道的範例見 [AI 渠道指南](ai-providers.md)。既有反向代理的 `COMPOSE_PROFILES` 設定見[部署指南](deployment.md)。

## 套用與檢查

在 `.env` 與 `compose.yaml` 所在目錄執行：

```bash
docker compose config --quiet
docker compose up -d --build
docker compose exec echo python -m server.check_config
```

第一行檢查 Compose 格式，不輸出展開後的 key。`check_config` 顯示兩組 AI 的渠道、模型、key 數量、代理啟用狀態及 `enabled`。範例輸出中的 `keys=1` 表示成功讀入一組 key，不代表已驗證其權限或額度。

這些檢查不會呼叫 AI，也不驗證網域 DNS、連線或付費帳戶。AI 執行狀態與錯誤的檢查方式見[故障排查](deployment.md#故障排查--troubleshooting)。

往後修改 `.env` 後執行 `docker compose up -d` 套用；單純 `docker compose restart` 不會載入新的環境變數。從內建 HTTPS 切換到其他代理時，另按部署指南停止原有 Caddy 服務。
