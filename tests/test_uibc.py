import json
import os
import unittest

from open_carlink_pc.auth import decrypt_session_payload
from open_carlink_pc.uibc import ACTION_DOWN, KEY_CODE_BACK, build_key_event, build_touch_event


class UibcTests(unittest.TestCase):
    def test_touch_event_is_plain_json_with_clamped_coordinates(self) -> None:
        event = json.loads(build_touch_event(ACTION_DOWN, 2000, -4))
        self.assertEqual(event["type"], 1)
        self.assertEqual(event["action"], ACTION_DOWN)
        self.assertEqual(event["width"], 1280)
        self.assertEqual(event["height"], 720)
        self.assertEqual(event["x0"], 1279)
        self.assertEqual(event["y0"], 0)

    def test_key_event_matches_uibc_json_layout(self) -> None:
        event = json.loads(build_key_event(KEY_CODE_BACK))
        self.assertEqual(
            event,
            {"type": 2, "action": 2, "keycode": 4, "metaState": 0},
        )

    def test_encryption_remains_available_when_negotiated(self) -> None:
        key = os.urandom(16)
        encrypted = build_touch_event(ACTION_DOWN, 12, 34, session_key=key)
        event = json.loads(decrypt_session_payload(key, encrypted))
        self.assertEqual((event["x0"], event["y0"]), (12, 34))


if __name__ == "__main__":
    unittest.main()
