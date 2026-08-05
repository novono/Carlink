from __future__ import annotations

import base64
import json
import os
import secrets
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(slots=True)
class CarIdentity:
    car_id: str
    model_id: str = "00000000"
    short_name: str = "PC CarLink"
    protocol_version: str = "1.2"
    vendor_data: str = "0000"

    @classmethod
    def create(cls) -> "CarIdentity":
        return cls(car_id=secrets.token_hex(6))

    def validate(self) -> None:
        if len(self.car_id) != 12:
            raise ValueError("car_id 必须是 12 位十六进制字符串")
        bytes.fromhex(self.car_id)
        if len(self.model_id) != 8:
            raise ValueError("model_id 必须是 8 个字符")
        if len(self.vendor_data) > 4:
            raise ValueError("vendor_data 最多 4 个字符")
        parts = self.protocol_version.split(".")
        if len(parts) < 2 or any(not part.isdigit() for part in parts[:2]):
            raise ValueError("protocol_version 格式应类似 1.2")

    @property
    def pin_code(self) -> str:
        return base64.b64encode(bytes.fromhex(self.car_id)[:6]).decode("ascii")

    @staticmethod
    def _truncate_utf8(value: str, max_bytes: int) -> str:
        encoded = value.encode("utf-8")
        if len(encoded) <= max_bytes:
            return value
        result = value
        while result and len((result + "…").encode("utf-8")) > max_bytes:
            result = result[:-1]
        return result + "…"

    @property
    def aoa_serial(self) -> str:
        self.validate()
        short_name = self._truncate_utf8(self.short_name, 16)
        major, minor = self.protocol_version.split(".")[:2]
        version = f"{int(major) & 0xFF}.{int(minor) & 0xFF}"
        return ";".join(
            (
                self.car_id[:12],
                self.model_id[:8],
                short_name,
                self.pin_code[:12],
                version,
                self.vendor_data[:4],
            )
        )


def app_data_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA") or os.environ.get("APPDATA")
    if base:
        root = Path(base)
    else:
        root = Path.cwd()
    path = root / "OpenCarLinkPC"
    path.mkdir(parents=True, exist_ok=True)
    return path


def load_identity() -> CarIdentity:
    path = app_data_dir() / "identity.json"
    if path.exists():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            identity = CarIdentity(**data)
            identity.validate()
            return identity
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            pass
    identity = CarIdentity.create()
    path.write_text(json.dumps(asdict(identity), ensure_ascii=False, indent=2), encoding="utf-8")
    return identity

