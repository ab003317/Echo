"""Configurable AI transports. Never log credentials, payloads, or provider error bodies."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import hashlib
import json
import os
import threading
import time
from urllib.parse import urlsplit

import httpx


PRESETS = {
    'gemini': ('https://generativelanguage.googleapis.com/v1beta', 'gemini-3.8-flash'),
    'openai': ('https://api.openai.com/v1', 'gpt-4.1-mini'),
    'deepseek': ('https://api.deepseek.com', 'deepseek-flash'),
    'qwen': ('', 'qwen-plus'),
    'custom': ('', ''),
}


def parse_keys(value: str) -> tuple[str, ...]:
    """One literal key or a JSON array; never split keys on punctuation."""
    value = value.strip()
    if not value:
        return ()
    try:
        parsed = json.loads(value) if value.startswith('[') else [value]
    except ValueError:
        raise ValueError('API_KEYS：請使用 JSON 字串陣列 / Use a JSON array of strings') from None
    if not isinstance(parsed, list) or any(not isinstance(k, str) or not k.strip() or any(c.isspace() for c in k.strip()) for k in parsed):
        raise ValueError('API_KEYS：每項必須是非空金鑰 / Each item must be a nonempty key')
    return tuple(dict.fromkeys(k.strip() for k in parsed))


def endpoint(value: str, name: str) -> str:
    parts = urlsplit(value)
    if parts.scheme not in ('https', 'http') or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError(f'{name}：需要不含帳密或查詢參數的 HTTP(S) URL / Use an HTTP(S) URL without credentials or query parameters')
    return value.rstrip('/')


@dataclass(frozen=True)
class AIConfig:
    provider: str
    base_url: str
    model: str
    keys: tuple[str, ...] = field(repr=False)
    proxy: str = field(default='', repr=False)
    audio: bool = False

    @property
    def enabled(self):
        return self.provider != 'none' and bool(self.keys)

    @classmethod
    def from_env(cls, audio=False):
        main = os.getenv('AI_PROVIDER', 'gemini').strip().lower()
        prefix = 'AUDIO_AI_' if audio else 'AI_'
        provider = os.getenv(prefix + 'PROVIDER', 'auto' if audio else main).strip().lower()
        if audio and provider in ('auto', ''):
            provider = main if main in ('gemini', 'openai') else 'none'
        if provider == 'none':
            return cls('none', '', '', (), audio=audio)
        if provider not in PRESETS:
            raise ValueError(prefix + 'PROVIDER：未知供應商 / Unknown provider')
        default_url, default_model = PRESETS[provider]
        # Credentials may only inherit within the same configured endpoint.
        same = audio and provider == main
        base = os.getenv(prefix + 'BASE_URL', '').strip() or (os.getenv('AI_BASE_URL', '').strip() if same else '') or default_url
        if not base:
            raise ValueError(prefix + 'BASE_URL：自訂渠道必填 / Required for custom providers')
        base = endpoint(base, prefix + 'BASE_URL')
        main_base = os.getenv('AI_BASE_URL', '').strip().rstrip('/') or PRESETS.get(main, ('', ''))[0]
        same = same and base == main_base
        default_audio = 'gpt-audio-1.5' if provider == 'openai' else default_model
        model = os.getenv(prefix + 'MODEL', '').strip() or (os.getenv('AI_MODEL', '').strip() if same and provider == 'gemini' else '') or (default_audio if audio else default_model)
        if not model or any(c in model for c in '\r\n?#'):
            raise ValueError(prefix + 'MODEL：請填寫模型 ID / Enter a model ID')
        raw_keys = os.getenv(prefix + 'API_KEYS', '').strip()
        if not raw_keys and same:
            raw_keys = os.getenv('AI_API_KEYS', '')
        proxy = os.getenv(prefix + 'PROXY_URL', '').strip() or (os.getenv('AI_PROXY_URL', '').strip() if same else '')
        if proxy:
            endpoint(proxy, prefix + 'PROXY_URL')
        return cls(provider, base, model, parse_keys(raw_keys), proxy, audio)


class AIError(RuntimeError):
    pass


class RateLimited(AIError):
    def __init__(self, retry_at):
        super().__init__('AI 額度暫時受限，稍後重試 / AI rate limited; retry later')
        self.retry_at = retry_at


def retry_seconds(value):
    try:
        return max(60, min(86400, float(value)))
    except (ValueError, TypeError):
        try:
            return max(60, min(86400, (parsedate_to_datetime(value) - datetime.now(timezone.utc)).total_seconds()))
        except (ValueError, TypeError, OverflowError):
            return 3600


class AIClient:
    _lock = threading.Lock()
    _cursor = {}
    _cooldown = {}
    _retry = {}

    def __init__(self, config=None, *, audio=False):
        self.config = config or AIConfig.from_env(audio=audio)

    def generate(self, prompt, schema, attachments=()):
        cfg = self.config
        if not cfg.enabled:
            raise AIError('尚未設定 AI 金鑰 / AI keys are not configured')
        if attachments and not cfg.audio:
            raise AIError('此分析未啟用音訊輸入 / Audio input is not enabled')
        instruction = prompt + '\nReturn only valid JSON matching this schema:\n' + json.dumps(schema)
        if cfg.provider == 'gemini':
            parts = [{'text': instruction}]
            for label, data in attachments:
                parts.extend([{'text': 'Next audio is ' + label}, {'type': 'audio', 'mime_type': 'audio/mp3', 'data': data}])
            # Interactions supports native audio input and typed JSON responses.
            parts = [dict(type='text', **p) if 'text' in p else p for p in parts]
            url = cfg.base_url + '/interactions'
            payload = {'model': cfg.model, 'store': False, 'input': parts, 'response_format': schema,
                       'generation_config': {'temperature': .2, 'max_output_tokens': 6000}}
        else:
            parts = [{'type': 'text', 'text': instruction}]
            for label, data in attachments:
                parts.extend([{'type': 'text', 'text': 'Next audio is ' + label},
                              {'type': 'input_audio', 'input_audio': {'data': data, 'format': 'mp3'}}])
            url = cfg.base_url + '/chat/completions'
            payload = {'model': cfg.model, 'messages': [{'role': 'user', 'content': parts if attachments else instruction}]}
            # Audio models and arbitrary compatible servers need not support JSON mode.
            if not attachments and cfg.provider != 'custom':
                payload['response_format'] = {'type': 'json_object'}
            if attachments:
                payload['modalities'] = ['text']
        group = (cfg.provider, cfg.base_url, hashlib.sha256('\0'.join(cfg.keys).encode()).hexdigest())
        with self._lock:
            retry_at = self._retry.get(group, 0)
            if retry_at > time.time():
                raise RateLimited(retry_at)
            start = self._cursor.get(group, 0) % len(cfg.keys)
            self._cursor[group] = start + 1
            keys = cfg.keys[start:] + cfg.keys[:start]
        attempts = 0
        with httpx.Client(proxy=cfg.proxy or None, timeout=httpx.Timeout(150, connect=20), trust_env=False) as client:
            for key in keys:
                slot = (cfg.base_url, hashlib.sha256(key.encode()).hexdigest())
                with self._lock:
                    if self._cooldown.get(slot, 0) > time.time():
                        continue
                attempts += 1
                headers = {'x-goog-api-key': key} if cfg.provider == 'gemini' else {'Authorization': 'Bearer ' + key}
                try:
                    response = client.post(url, json=payload, headers=headers)
                except httpx.HTTPError:
                    raise AIError('AI 連線失敗，請檢查伺服器網路與代理設定 / AI connection failed; check server networking and proxy') from None
                if response.status_code == 429:
                    retry_at = time.time() + retry_seconds(response.headers.get('retry-after'))
                    with self._lock:
                        self._retry[group] = retry_at
                    raise RateLimited(retry_at)
                if response.status_code in (401, 403):
                    with self._lock:
                        self._cooldown[slot] = time.time() + 3600
                    if attempts < min(3, len(keys)):
                        continue
                if not response.is_success:
                    raise AIError(f'AI 請求失敗 / AI request failed (HTTP {response.status_code})')
                try:
                    body = response.json()
                    if cfg.provider == 'gemini':
                        if body.get('status') != 'completed':
                            raise ValueError('Incomplete interaction')
                        text = ''.join(p.get('text', '') for step in body.get('steps', []) if step.get('type') == 'model_output'
                                       for p in step.get('content', []) if p.get('type') == 'text')
                        if not text:
                            text = ''.join(p.get('text', '') for p in body.get('outputs', []) if p.get('type') == 'text')
                    else:
                        text = body['choices'][0]['message']['content']
                    text = text.strip()
                    if text.startswith('```') and text.endswith('```'):
                        text = text.split('\n', 1)[1].rsplit('```', 1)[0].strip()
                    result = json.loads(text)
                    if not isinstance(result, dict):
                        raise ValueError('Object required')
                    return result, cfg.model
                except (KeyError, IndexError, TypeError, ValueError, AttributeError):
                    raise AIError('AI 回覆格式不完整，保留已有偏好 / Invalid AI response; keeping existing preferences') from None
        raise AIError('AI 金鑰正在冷卻 / AI keys are cooling down')
