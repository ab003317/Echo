# 部署與維護 / Deployment and maintenance

## 既有反向代理或 Tunnel / Existing reverse proxy or tunnel

保留 .env 的 DOMAIN，設定 / Keep DOMAIN and set:

```dotenv
COMPOSE_PROFILES=
BIND_ADDRESS=127.0.0.1
HTTP_PORT=18082
```

```bash
docker compose up -d --build echo
```

把你的 HTTPS 網域轉送到 `http://127.0.0.1:18082`，保留 `/music` 路徑。
不要 strip-prefix。代理須保留 Range／Content-Range，並允許長音訊串流。
若代理也在容器內，localhost 指向它自己；把代理接到 Echo 的 Docker 網路，
上游填 `http://echo:18082`，或使用可達的主機位址。

Forward your HTTPS domain to `http://127.0.0.1:18082`, preserving `/music`.
Do not strip the prefix. Preserve Range/Content-Range and allow long audio streams.
For a containerized proxy, localhost is the proxy container; join Echo's Docker
network and use `http://echo:18082`, or use a reachable host address.

先前已啟動內建 Caddy 時，切換代理前先停止它：
If included Caddy is already running, stop it before switching:

```bash
docker compose --profile https stop https
```

## 只用內網 / LAN only

```dotenv
COMPOSE_PROFILES=
BIND_ADDRESS=0.0.0.0
HTTP_PORT=18082
```

App 填 `http://你的伺服器IP:18082/music`，例如 `http://10.0.0.20:18082/music`。
不要為此模式把 18082 轉發到公網；有外網需求可改用 HTTPS。
Enter `http://your-server-ip:18082/music` in the app. Keep port 18082 on your LAN;
use HTTPS for access outside it.

Android 的 Wi-Fi 自動下載仍受系統背景排程、省電與 Wi-Fi 驗證影響；
連上 Wi-Fi 不保證立刻執行。可在 App 的下載頁檢查進度。
Android background scheduling, battery restrictions and Wi-Fi validation affect
automatic downloads. Connecting to Wi-Fi does not guarantee an immediate run.
Check progress in the app's Downloads tab.

## 音樂路徑與 NAS 權限 / Music paths and NAS permissions

MUSIC_PATH 是 Docker 主機的路徑，不是手機路徑，也不是容器內路徑。
預設映射為容器 `/music`，資料庫保存在 `/data`。支援的音訊副檔名請見 server/app.py 的 EXTENSIONS。
請先確認 Docker 有讀寫該音樂目錄的權限。NFS root squash／NAS ACL 可能需要
在主機上授予權限；不要為了解決權限而讓 Echo 遞迴修改整個原有音樂庫的擁有者。

MUSIC_PATH is a path on the Docker host, mounted at `/music`; app data lives in
`/data`. Supported extensions are listed in server/app.py. Docker must be able
to read the library and write imported tracks. NFS root squash and NAS ACLs may
require host-side permission changes. Echo does not recursively change ownership
of your original library.

預設容器以 root 執行以支援一般 bind mount；如需指定 UID/GID，可在 Compose 的
echo 服務加入 `user: "1000:1000"`，並預先讓音樂目錄與資料 volume 可由該身分寫入。
The container runs as root by default for standard bind mounts. To run with a
specific UID/GID, add `user: "1000:1000"` to the echo service and provision writable
music and data storage for that identity.

## 更新 / Update

```bash
git pull --ff-only
docker compose up -d --build
docker compose ps
docker compose logs --tail=80 echo
```

更換 .env 只需 `docker compose up -d`；單純 restart 不會載入新的環境變數。
After editing .env, use `docker compose up -d`; restart alone does not reload it.

## 備份與還原 / Backup and restore

備份包含：音樂目錄、.env，以及完整 echo_data volume。先停止服務取得一致的 SQLite
與音訊狀態；以下指令從專案根目錄執行，備份不包含音樂 bind mount。
Back up music, .env and the entire echo_data volume. Stop the service for a
consistent SQLite/audio snapshot. Run these commands from the project directory;
this archive does not include the music bind mount.

```bash
mkdir -p backups
docker compose stop echo
docker compose run --rm --no-deps -v "$PWD/backups:/backup" echo \
  tar -czf /backup/echo-data.tar.gz -C /data .
docker compose up -d echo
```

另行備份 MUSIC_PATH 與 .env。備份檔包含個人聆聽資料，不要提交 Git。
Back up MUSIC_PATH and .env separately. Archives contain personal listening data;
do not commit them.

還原至新的部署／空資料 volume / Restore into a new deployment or empty data volume:

```bash
docker compose build echo
docker compose run --rm --no-deps -v "$PWD/backups:/backup:ro" echo \
  tar -xzf /backup/echo-data.tar.gz -C /data
docker compose up -d
```

請勿執行 `docker compose down -v`，除非你確定要刪除 volume 的資料。
Avoid `docker compose down -v` unless you intend to delete volume data.
TZ 決定每日分析與新歌池輪換的時區；預設 Asia/Hong_Kong。
TZ controls daily analysis and pool-rotation boundaries; default: Asia/Hong_Kong.

## YouTube cookies（可選） / Optional YouTube cookies

部分來源會要求登入或封鎖資料中心 IP；下載並非任何網路都一定成功。
如你有可用的 Netscape cookies 檔案，把它存為 `cookies.txt`，新增
`compose.override.yaml`：

Some sources require login or block datacenter IPs. Imports are not guaranteed on
every network. If you have a usable Netscape cookies file, save it as
`cookies.txt` and add `compose.override.yaml`:

```yaml
services:
  echo:
    volumes:
      - ./cookies.txt:/run/echo/cookies.txt
```

再設定 / Then set:

```dotenv
YOUTUBE_COOKIES_FILE=/run/echo/cookies.txt
```

yt-dlp 可能更新 cookies，所以掛載需可寫。此檔案與 AI key 都不會包含在 App 或
Docker image；請保留在自己的伺服器。匯入自己有權使用的內容。
yt-dlp may update cookies, so the mount is writable. Cookies and AI keys are not
embedded in the app or image; keep them on your own server. Import content you
are authorized to use.

## 故障排查 / Troubleshooting

| 現象 / Symptom | 檢查 / Check |
| --- | --- |
| HTTPS 無法啟動 / HTTPS unavailable | DNS、80/443、防火牆、Caddy logs / DNS, ports, firewall and Caddy logs |
| App 無法連線 / App cannot connect | 開啟 /music/api/health；路徑不可被代理移除 / Open health endpoint; preserve path |
| 音樂庫空白 / Empty library | MUSIC_PATH 是否是主機音樂資料夾、權限、重新掃描 / Host mount, permissions, rescan |
| AI 未啟用 / AI disabled | check_config 的 key 數量、provider、模型權限 / Key count, provider and model access |
| AI 401/403 | key、地區與端點是否一致 / Key, region and endpoint match |
| AI 404 | BASE_URL 是否重複加 /chat/completions、模型是否存在 / Duplicated suffix or unavailable model |
| AI 429 | 等待 Retry-After／檢查供應商額度 / Wait for backoff and check provider quota |
| 沒有曲風歌單 / No style mixes | 是否啟用實際音訊分析且累積足夠同類歌曲 / Audio analysis enabled, enough supported tracks |
