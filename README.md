# Echo

[English](docs/README.en.md) · [Android 下載](https://github.com/ab003317/Echo/releases/latest) · [AI 渠道設定](docs/ai-providers.md)

<img src="assets/brand/echo-icon.png" width="144" alt="Echo">

你的私人音樂伺服器，配上一個 Android 播放器。背景播放、離線音樂、每日探索與會跟著聆聽習慣調整的推薦。

- 背景與鎖定畫面播放、播放佇列、隨機、單曲／清單循環、播放記錄。
- Wi-Fi 自動保存最近聆聽、最常聆聽、最愛及推薦歌曲；可設定容量、手動保留或刪除。
- 預先下載的新歌池：聽過的保留，常聽的進入常駐池，未聽的每日部分輪換。
- 每日產生歌手、系列、曲風及隨機歌單；收藏後固定保留。曲風分類需要實際音訊證據。
- AI 依實際聆聽秒數、重播、完成與主動跳過分析；**不以歌名猜測曲風**。
- App 與部署文件提供繁體中文／English。

每個部署共用一份音樂庫、播放記錄與推薦偏好，適合個人或共享口味的家庭。這個版本沒有獨立使用者帳戶，也沒有 App 存取金鑰；知道並能連到位址的人可以操作此實例。可部署在私人網路，或由既有反向代理控制存取。

## 三步部署

需要 Linux 私人伺服器／NAS、Docker Engine 和 Docker Compose v2。App 需要 Android 8.0 或以上。

```bash
git clone https://github.com/ab003317/Echo.git
cd Echo
cp .env.example .env
```

編輯 `.env`，最少修改：

```dotenv
DOMAIN=music.example.com
MUSIC_PATH=/path/to/your/music
AI_API_KEYS='["your-gemini-api-key"]'
```

預設使用 Gemini `gemini-3.8-flash`，同時支援行為與音訊分析。不要將範例字串當成真實 key；沒有 key 時填 `[]` 仍可使用音樂庫、播放、離線保存及不依賴 AI 的推薦。AI 渠道會依用量計費或消耗額度。

```bash
docker compose up -d --build
docker compose exec echo python -m server.check_config
```

把網域 DNS 指向伺服器，並讓 TCP 80／443 能連到它。內建 Caddy 自動申請 HTTPS 憑證。已有服務佔用 80／443、使用 Tunnel 或只用內網時，請看[其他部署方式](docs/deployment.md)。

打開 `https://music.example.com/music`，下載 [Echo APK](https://github.com/ab003317/Echo/releases/latest)。App 首次開啟時填入相同位址；**手機只填伺服器位址，AI key 留在伺服器**。

## AI 怎麼填

`.env.example` 內每項都有繁中與英文註解；[AI 設定指南](docs/ai-providers.md)提供官方渠道、模型、OpenAI 相容 URL、多 key、音訊和 VPN／代理範例。

| 設定 | 用途 |
| --- | --- |
| `AI_PROVIDER` | `gemini`、`openai`、`deepseek`、`qwen`、`custom` 或 `none` |
| `AI_BASE_URL` | API 根位址；不含 `/chat/completions`。自訂渠道與 Qwen 必填 |
| `AI_MODEL` | 模型 ID；留空使用渠道預設 |
| `AI_API_KEYS` | 一組 key，或 `'["key-one","key-two"]'` |
| `AI_PROXY_URL` | 可選的 AI HTTP(S) 代理 |
| `AUDIO_AI_*` | 音訊分析另選渠道、模型、URL 與 key |
| `YOUTUBE_PROXY_URL` | YouTube 的獨立代理 |

是否需要 VPN／代理取決於**伺服器網路、渠道服務地區與帳戶資格**，不是某個模型名稱的固定屬性。請核對[渠道文件與支援地區](docs/ai-providers.md#網路vpn與代理--network-vpn-and-proxy)。

AI 預設每日分析前一日聆聽資料，音訊每天最多抽樣 12 首，每首最多 60 秒。音訊會傳往你設定的渠道；可用 `AUDIO_AI_PROVIDER=none` 關閉。文字分析仍可運作，但不會補造曲風。

## 資料與維護

音樂目錄是主機掛載；資料庫、收藏、播放記錄、歌單及封面在 Docker `echo_data` volume。
原有音樂檔案不會由新歌池輪換刪除；自動清理由 Echo 管理的探索歌曲才適用。
YouTube 搜尋／匯入依賴 yt-dlp 與來源可用性，並非 YouTube Music 帳戶同步；只匯入你有權使用的音源。

更新、備份、還原、NAS 權限、既有代理及 cookies 的做法見[部署維護指南](docs/deployment.md)。
此階段沒有加入專案授權條款；公開原始碼不代表授予修改或再發佈授權。第三方元件保留各自授權，見 [THIRD_PARTY.md](THIRD_PARTY.md)。

## 開發

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r server/requirements.lock
python -m pytest server -q
# 直接執行不會自動讀取 .env；需先設定環境變數。
python -m uvicorn server.app:app --host 127.0.0.1 --port 18082
```

Android 使用 JDK 17 或以上、Android SDK 36：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

正式 APK 需要自己的簽署設定，見 [Android 建置說明](docs/android.md)。GitHub CI 執行 Python、Android 與 Docker 檢查；CI debug APK 適合測試，正式下載請使用 Releases。
