import unittest

from open_carlink_pc.config import CarIdentity
from open_carlink_pc.ucar_protocol import (
    UCarHeader,
    UCarStreamReassembler,
    build_get_config_response,
    build_heartbeat,
    build_session_disconnect,
    crc16_modbus,
    is_session_disconnect,
)


class UCarProtocolTests(unittest.TestCase):
    def test_stream_reassembler_handles_split_and_coalesced_messages(self) -> None:
        first = build_heartbeat(1, timestamp_ms=1_700_000_000_001)
        second = build_heartbeat(2, timestamp_ms=1_700_000_000_002)
        stream = UCarStreamReassembler()
        self.assertEqual(stream.feed(first[:20]), [])
        self.assertEqual(stream.feed(first[20:] + second), [first, second])

    def test_crc_matches_real_phone_packet(self) -> None:
        header_without_crc = bytes.fromhex("00000014000000036a719d250b0100180000")
        self.assertEqual(crc16_modbus(header_without_crc), 0x0C50)

    def test_builds_config_response_for_real_request(self) -> None:
        request = bytes.fromhex("00000014000000036a719d250b01001800000c50")
        identity = CarIdentity(car_id="010203040506")
        response = build_get_config_response(request, identity)
        self.assertIsNotNone(response)
        assert response is not None
        header = UCarHeader.parse(response)
        self.assertEqual(header.sequence_id, 3)
        self.assertEqual(header.message_type, 2)
        self.assertEqual(header.category, 1)
        self.assertEqual(header.method, 25)
        self.assertIn(b"\x0a\x06\x01\x02\x03\x04\x05\x06", response[20:])

    def test_builds_car_heartbeat_with_millisecond_timestamp(self) -> None:
        heartbeat = build_heartbeat(7, timestamp_ms=1_700_000_000_123)
        header = UCarHeader.parse(heartbeat)
        self.assertEqual(header.sequence_id, 7)
        self.assertEqual(header.source, 0)
        self.assertEqual(header.data_format, 1)
        self.assertEqual(header.message_type, 0)
        self.assertEqual(header.category, 1)
        self.assertEqual(header.method, 1)
        self.assertEqual(heartbeat[20], 0x08)

    def test_detects_real_phone_session_disconnect_notification(self) -> None:
        packet = bytes.fromhex(
            "0000003a000000696a72f82c1b01000700007859"
            "0000000c9a7055e23172b6ad9e0fb52f00000012"
            "7dcf378b42d6c70996883693783446dd61b7"
        )

        self.assertTrue(is_session_disconnect(packet))
        self.assertFalse(is_session_disconnect(build_heartbeat(1, timestamp_ms=1)))

    def test_builds_car_session_disconnect_notification(self) -> None:
        message = build_session_disconnect(9)
        header = UCarHeader.parse(message)

        self.assertEqual(header.sequence_id, 9)
        self.assertEqual(header.source, 0)
        self.assertEqual(header.message_type, 3)
        self.assertEqual(header.category, 1)
        self.assertEqual(header.method, 7)
        self.assertEqual(message[20:], b"\x08\x01")


if __name__ == "__main__":
    unittest.main()
