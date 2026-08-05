import struct
import unittest

from open_carlink_pc.video import RtpMpegTsExtractor


class RtpMpegTsExtractorTests(unittest.TestCase):
    @staticmethod
    def _packet(sequence: int, ts_packets: int = 1) -> bytes:
        rtp = (
            bytes((0x80, 0xA1))
            + struct.pack(">HII", sequence, 12345, 0xDEADBEEF)
            + (b"\x47" + b"\xFF" * 187) * ts_packets
        )
        return struct.pack(">H", len(rtp)) + rtp

    def test_extracts_split_and_coalesced_rtp_packets(self) -> None:
        first = self._packet(1, 2)
        second = self._packet(2, 1)
        extractor = RtpMpegTsExtractor()
        self.assertEqual(extractor.feed(first[:17]), b"")
        result = extractor.feed(first[17:] + second)
        self.assertEqual(len(result), 3 * 188)
        self.assertEqual(result[0], 0x47)
        self.assertEqual(result[376], 0x47)
        self.assertEqual(extractor.packet_count, 2)


if __name__ == "__main__":
    unittest.main()
