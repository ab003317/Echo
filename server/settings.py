"""Shared deployment settings; daily boundaries follow the operator's time zone."""
from datetime import datetime
import os
from zoneinfo import ZoneInfo

TIMEZONE = os.getenv('TZ', 'Asia/Hong_Kong')
LOCAL_TZ = ZoneInfo(TIMEZONE)


def local_now():
    return datetime.now(LOCAL_TZ)
