from __future__ import annotations

import os
import re
import subprocess
import sys
import time
from collections.abc import Callable
from pathlib import Path

import usb.core

from .aoa import AOA_PRODUCT_IDS, AOA_VENDOR_ID, PHONE_VENDOR_NAMES, _backend

_USB_INSTANCE_PATTERN = re.compile(
    r"USB\\VID_[0-9A-F]{4}&PID_[0-9A-F]{4}\\[^\r\n]+",
    re.IGNORECASE,
)


def _helper_path() -> Path:
    frozen_root = getattr(sys, "_MEIPASS", None)
    if frozen_root is not None:
        return Path(frozen_root) / "usb_cycle_port.exe"
    return Path(__file__).resolve().parents[2] / "tools" / "usb_cycle_port.exe"


def find_accessory_instance_id(
    vendor_id: int,
    product_id: int,
    serial: str = "",
) -> str | None:
    if os.name != "nt":
        return None
    try:
        result = subprocess.run(
            ["pnputil", "/enum-devices", "/connected", "/class", "USB"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    prefix = f"USB\\VID_{vendor_id:04X}&PID_{product_id:04X}\\"
    candidates = [
        value.strip()
        for value in _USB_INSTANCE_PATTERN.findall(result.stdout)
        if value.upper().startswith(prefix)
    ]
    if serial:
        suffix = f"\\{serial}".upper()
        serial_matches = [value for value in candidates if value.upper().endswith(suffix)]
        if len(serial_matches) == 1:
            return serial_matches[0]
    return candidates[0] if len(candidates) == 1 else None


def cycle_phone_port(instance_id: str) -> tuple[bool, str]:
    helper = _helper_path()
    if not helper.exists():
        return False, "新版缺少 USB 端口循环组件"
    try:
        result = subprocess.run(
            [str(helper), instance_id],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return False, f"USB 端口循环调用失败：{exc}"
    detail = (result.stdout or result.stderr).strip()
    if result.returncode == 0:
        return True, detail or "已循环当前手机 USB 端口"
    if result.returncode == 5:
        return False, "Windows 拒绝循环 USB 端口；请以管理员身份运行"
    return False, detail or f"USB 端口循环失败（退出码 {result.returncode}）"


def wait_for_phone_mode(
    timeout_seconds: float,
    stop_requested: Callable[[], bool] = lambda: False,
    *,
    stable_seconds: float = 3.0,
    observation: Callable[[bool, tuple[int, int] | None], None] | None = None,
) -> tuple[int, int] | None:
    deadline = time.monotonic() + timeout_seconds
    stable_since: float | None = None
    stable_device: tuple[int, int] | None = None
    while time.monotonic() < deadline and not stop_requested():
        devices = usb.core.find(find_all=True, backend=_backend())
        accessory_present = False
        phone_mode: tuple[int, int] | None = None
        for device in devices or ():
            vendor_id = int(device.idVendor)
            product_id = int(device.idProduct)
            if vendor_id == AOA_VENDOR_ID and product_id in AOA_PRODUCT_IDS:
                accessory_present = True
            elif vendor_id in PHONE_VENDOR_NAMES:
                phone_mode = vendor_id, product_id
        if observation is not None:
            observation(accessory_present, phone_mode)
        now = time.monotonic()
        if not accessory_present and phone_mode is not None:
            if phone_mode != stable_device:
                stable_device = phone_mode
                stable_since = now
            elif stable_since is not None and now - stable_since >= stable_seconds:
                return phone_mode
        else:
            stable_since = None
            stable_device = None
        time.sleep(0.3)
    return None
