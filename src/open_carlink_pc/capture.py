from __future__ import annotations

import hashlib
import json
import threading
import time
from pathlib import Path


class CaptureWriter:
    def __init__(self, directory: Path, full_payload: bool = False) -> None:
        directory.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        self.path = directory / f"session-{stamp}.jsonl"
        self.full_payload = full_payload
        self._lock = threading.Lock()
        self._frame_counts: dict[tuple[str, int], int] = {}

    def event(self, event_type: str, **fields: object) -> None:
        record = {"time": time.time(), "type": event_type, **fields}
        line = json.dumps(record, ensure_ascii=False, separators=(",", ":"))
        with self._lock:
            with self.path.open("a", encoding="utf-8") as stream:
                stream.write(line + "\n")

    def frame(self, direction: str, channel_id: int, payload: bytes) -> None:
        key = (direction, channel_id)
        count = self._frame_counts.get(key, 0) + 1
        self._frame_counts[key] = count
        # RTP arrives hundreds of times per second. Opening and hashing a JSONL
        # record for every packet stalls the same thread that feeds the decoder.
        if not self.full_payload and direction == "phone_to_pc" and channel_id == 4:
            if count != 1 and count % 300:
                return
        fields: dict[str, object] = {
            "direction": direction,
            "channel_id": channel_id,
            "length": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
            "preview_hex": payload[:64].hex(),
            "frame_count": count,
        }
        if self.full_payload:
            fields["payload_hex"] = payload.hex()
        self.event("frame", **fields)
