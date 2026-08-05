from __future__ import annotations

import json

from .auth import encrypt_session_payload


ACTION_DOWN = 0
ACTION_UP = 1
ACTION_MOVE = 2
KEY_ACTION_PRESS = 2
KEY_CODE_BACK = 4
KEY_CODE_MAIN = 8103


def build_touch_event(
    action: int,
    x: int,
    y: int,
    *,
    width: int = 1280,
    height: int = 720,
    session_key: bytes | None = None,
) -> bytes:
    if action not in {ACTION_DOWN, ACTION_UP, ACTION_MOVE}:
        raise ValueError(f"不支持的触控 action：{action}")
    if width <= 0 or height <= 0:
        raise ValueError("触控画面尺寸必须大于 0")
    event = {
        "type": 1,
        "action": action,
        "width": width,
        "height": height,
        "count": 1,
        "trackID0": 0,
        "x0": max(0, min(width - 1, int(x))),
        "y0": max(0, min(height - 1, int(y))),
    }
    plaintext = json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    # ColorOS 16's source-side UIBC receiver parses the socket bytes directly
    # as JSON. Encryption is only valid when both RTSP peers negotiated it.
    return encrypt_session_payload(session_key, plaintext) if session_key else plaintext


def build_key_event(
    key_code: int,
    *,
    action: int = KEY_ACTION_PRESS,
    meta_state: int = 0,
    session_key: bytes | None = None,
) -> bytes:
    event = {
        "type": 2,
        "action": action,
        "keycode": key_code,
        "metaState": meta_state,
    }
    plaintext = json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return encrypt_session_payload(session_key, plaintext) if session_key else plaintext
