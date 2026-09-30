# 內含音訊工具與 YouTube JS 執行環境 / Includes audio tools and the YouTube JS runtime.
FROM node:22-bookworm-slim AS node
FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
WORKDIR /app
COPY server/requirements.lock /app/requirements.lock
RUN pip install -r requirements.lock
COPY server /app/server
COPY web /app/web
ENV SEREIN_MUSIC_ROOT=/music SEREIN_DATA_DIR=/data
EXPOSE 18082
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:18082/music/api/health', timeout=3)"
CMD ["python", "-m", "uvicorn", "server.app:app", "--host", "0.0.0.0", "--port", "18082", "--workers", "1"]
