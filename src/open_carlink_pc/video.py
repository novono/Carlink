from __future__ import annotations

import struct
import threading
from collections.abc import Callable

import av
from PIL import Image


RTP_PAYLOAD_TYPE_MPEG_TS = 33
MPEG_TS_PACKET_SIZE = 188
MAX_RTP_PACKET_SIZE = 65_535


class RtpMpegTsExtractor:
    """Extract MPEG-TS bytes from ICCOA's length-prefixed RTP byte stream."""

    def __init__(self) -> None:
        self._buffer = bytearray()
        self.packet_count = 0

    def feed(self, data: bytes) -> bytes:
        self._buffer.extend(data)
        output = bytearray()
        while len(self._buffer) >= 2:
            length = struct.unpack_from(">H", self._buffer)[0]
            if length < 12 or length > MAX_RTP_PACKET_SIZE:
                self._buffer.clear()
                raise ValueError(f"RTP 包长度无效：{length}")
            total = 2 + length
            if len(self._buffer) < total:
                break
            packet = bytes(self._buffer[2:total])
            del self._buffer[:total]
            payload = self._payload(packet)
            self.packet_count += 1
            if payload:
                output.extend(payload)
        return bytes(output)

    @staticmethod
    def _payload(packet: bytes) -> bytes:
        if packet[0] >> 6 != 2:
            raise ValueError("RTP 版本不是 2")
        payload_type = packet[1] & 0x7F
        csrc_count = packet[0] & 0x0F
        offset = 12 + csrc_count * 4
        if offset > len(packet):
            raise ValueError("RTP CSRC 头越界")
        if packet[0] & 0x10:
            if offset + 4 > len(packet):
                raise ValueError("RTP 扩展头不完整")
            extension_words = struct.unpack_from(">H", packet, offset + 2)[0]
            offset += 4 + extension_words * 4
        if offset > len(packet):
            raise ValueError("RTP 扩展数据越界")
        end = len(packet)
        if packet[0] & 0x20:
            padding = packet[-1]
            if padding == 0 or padding > end - offset:
                raise ValueError("RTP padding 无效")
            end -= padding
        if payload_type != RTP_PAYLOAD_TYPE_MPEG_TS:
            return b""
        payload = packet[offset:end]
        if payload and (len(payload) % MPEG_TS_PACKET_SIZE or payload[0] != 0x47):
            raise ValueError("RTP 负载不是对齐的 MPEG-TS")
        return payload


class _BlockingByteStream:
    def __init__(self) -> None:
        self._buffer = bytearray()
        self._condition = threading.Condition()
        self._closed = False

    def feed(self, data: bytes) -> None:
        if not data:
            return
        with self._condition:
            if self._closed:
                return
            self._buffer.extend(data)
            self._condition.notify_all()

    def read(self, size: int = -1) -> bytes:
        with self._condition:
            while not self._buffer and not self._closed:
                self._condition.wait(timeout=0.5)
            if not self._buffer and self._closed:
                return b""
            if size < 0:
                size = len(self._buffer)
            count = min(size, len(self._buffer))
            result = bytes(self._buffer[:count])
            del self._buffer[:count]
            return result

    def close(self) -> None:
        with self._condition:
            self._closed = True
            self._condition.notify_all()


class VideoDecoder:
    def __init__(
        self,
        frame_callback: Callable[[Image.Image], None],
        log: Callable[[str], None],
    ) -> None:
        self._frame_callback = frame_callback
        self._log = log
        self._stream = _BlockingByteStream()
        self._thread = threading.Thread(
            target=self._run,
            name="CarLinkVideoDecoder",
            daemon=True,
        )
        self._started = False

    def start(self) -> None:
        if self._started:
            return
        self._started = True
        self._thread.start()

    def feed(self, transport_stream: bytes) -> None:
        self._stream.feed(transport_stream)

    def stop(self) -> None:
        self._stream.close()
        if self._started and self._thread is not threading.current_thread():
            self._thread.join(timeout=2.0)

    def _run(self) -> None:
        try:
            with av.open(
                self._stream,
                mode="r",
                format="mpegts",
                options={
                    "probesize": "32768",
                    "analyzeduration": "500000",
                },
            ) as container:
                video_stream = next(
                    (stream for stream in container.streams if stream.type == "video"),
                    None,
                )
                if video_stream is None:
                    raise ValueError("MPEG-TS 中没有视频轨")
                # Frame-level threading increases throughput but also keeps
                # several decoded frames queued. Slice threading stays close
                # to the live edge for this 720p in-car stream.
                video_stream.thread_type = "SLICE"
                video_stream.thread_count = 2
                video_stream.codec_context.options = {"flags2": "+fast"}
                self._log(
                    f"视频解码器已启动：{video_stream.codec_context.name} "
                    f"{video_stream.codec_context.width}x{video_stream.codec_context.height}"
                )
                for frame in container.decode(video_stream):
                    self._frame_callback(frame.to_image())
        except (av.FFmpegError, OSError, ValueError) as exc:
            self._log(f"视频解码已结束：{exc}")
