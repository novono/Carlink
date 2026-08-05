import struct
import tempfile
import unittest
from pathlib import Path

from open_carlink_pc.capture import CaptureWriter
from open_carlink_pc.mux import CreateSocketMessage, Multiplexer


class FakeTransport:
    def __init__(self, incoming: bytes = b"") -> None:
        self.incoming = bytearray(incoming)
        self.writes: list[bytes] = []

    def read_exact(self, length, stop_requested):
        del stop_requested
        result = bytes(self.incoming[:length])
        del self.incoming[:length]
        if len(result) != length:
            raise AssertionError("fake transport underflow")
        return result

    def write_all(self, data):
        self.writes.append(bytes(data))


class CreateSocketMessageTests(unittest.TestCase):
    def test_round_trip_uses_big_endian_signed_channel_id(self) -> None:
        message = CreateSocketMessage(15550, -1, 1, 2)
        encoded = message.encode()
        self.assertEqual(len(encoded), 10)
        self.assertEqual(encoded[:4], struct.pack(">i", 15550))
        self.assertEqual(CreateSocketMessage.parse(encoded), message)

    def test_short_payload_is_padded_to_eight_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            transport = FakeTransport()
            capture = CaptureWriter(Path(temporary_directory))
            mux = Multiplexer(transport, capture, lambda _: None)
            mux.write_frame(3, b"abc")
            self.assertEqual(transport.writes[0], struct.pack(">II", 3, 3))
            self.assertEqual(transport.writes[1], b"abc" + b"\x00" * 5)

    def test_frame_reader_removes_transport_padding(self) -> None:
        incoming = struct.pack(">II", 5, 2) + b"ok" + b"\x00" * 6
        with tempfile.TemporaryDirectory() as temporary_directory:
            transport = FakeTransport(incoming)
            capture = CaptureWriter(Path(temporary_directory))
            mux = Multiplexer(transport, capture, lambda _: None)
            frame = mux.read_frame(lambda: False)
            self.assertEqual(frame.channel_id, 5)
            self.assertEqual(frame.payload, b"ok")

    def test_requests_auth_and_rtp_after_default_client_channels(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            transport = FakeTransport()
            capture = CaptureWriter(Path(temporary_directory))
            mux = Multiplexer(transport, capture, lambda _: None)
            mux.handle_control(
                type("Frame", (), {"payload": CreateSocketMessage(7236, -1, 1, 2).encode()})()
            )
            mux.handle_control(
                type("Frame", (), {"payload": CreateSocketMessage(4321, -1, 1, 2).encode()})()
            )
            control_payloads = [transport.writes[index] for index in range(1, len(transport.writes), 2)]
            decoded = [CreateSocketMessage.parse(payload[:10]) for payload in control_payloads]
            self.assertIn(CreateSocketMessage(57209, -1, 1, 2), decoded)
            self.assertIn(CreateSocketMessage(15550, -1, 1, 2), decoded)


if __name__ == "__main__":
    unittest.main()
