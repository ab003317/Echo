"""Validate settings without printing secrets or making billable requests."""
from .ai import AIConfig
from .settings import TIMEZONE


def main():
    for audio in (False, True):
        cfg = AIConfig.from_env(audio=audio)
        purpose = '音訊 / audio' if audio else '偏好 / preferences'
        print(f'{purpose}: provider={cfg.provider}, model={cfg.model or "—"}, keys={len(cfg.keys)}, proxy={bool(cfg.proxy)}, enabled={cfg.enabled}')
    print(f'時區 / Time zone: {TIMEZONE}')
    print('格式檢查通過；未呼叫 AI / Configuration valid; no AI requests made')


if __name__ == '__main__':
    main()
