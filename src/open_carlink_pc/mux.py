from __future__ import annotations

import struct
import threading
from dataclasses import dataclass
from typing import Callable

from .aoa import AoaError, AoaTransport
from .capture import CaptureWriter


MAX_CHANNEL_ID = 240
MAX_MESSAGE_LENGTH = 64 * 1024 * 1024
CONTROL_CHANNEL_ID = 1

PORT_NAMES = {
    4321: "UIBC",
    7236: "RTSP",
    15550: "RTP",
    57209: "AUTH",
    57219: "CONTROL",
    57229: "MEDIA",
    57239: "SENSOR",
    57249: "CERT",
    57259: "VIDEO",
}

FIXED_CHANNEL_IDS = {
    15550: 4,
    7236: 3,
    4321: 5,
    57209: 7,
    57219: 2,
    57229: 6,
    57239: 8,
    57249: 9,
}


@dataclass(frozen=True, slots=True)
class MultiplexFrame:
    channel_id: int
    payload: bytes


@dataclass(frozen=True, slots=True)
class CreateSocketMessage:
    port: int
    channel_id: int
    socket_type: int
    message_type: int

    @classmethod
    def parse(cls, payload: bytes) -> "CreateSocketMessage":
        if len(payload) != 10:
            raise ValueError("create socket 消息必须为 10 字节")
        port, channel_id, socket_type, message_type = struct.unpack(">iiBB", payload)
        return cls(port, channel_id, socket_type, message_type)

    def encode(self) -> bytes:
        return struct.pack(">iiBB", self.port, self.channel_id, self.socket_type, self.message_type)


class Multiplexer:
    def __init__(
        self,
        transport: AoaTransport,
        capture: CaptureWriter,
        log: Callable[[str], None],
    ) -> None:
        self.transport = transport
        self.capture = capture
        self.log = log
        self.channels: dict[int, CreateSocketMessage] = {}
        self.next_dynamic_channel = 16
        self.requested_rtp = False
        self.requested_auth = False
        self._write_lock = threading.Lock()

    def read_frame(self, stop_requested: Callable[[], bool]) -> MultiplexFrame:
        header = self.transport.read_exact(8, stop_requested)
        channel_id, length = struct.unpack(">II", header)
        if not 1 <= channel_id <= MAX_CHANNEL_ID:
            raise AoaError(f"无效 CarLink 通道 ID：{channel_id}")
        if length > MAX_MESSAGE_LENGTH:
            raise AoaError(f"CarLink 帧过大：{length} 字节")
        padded_length = max(length, 8)
        padded_payload = self.transport.read_exact(padded_length, stop_requested)
        payload = padded_payload[:length]
        self.capture.frame("phone_to_pc", channel_id, payload)
        return MultiplexFrame(channel_id, payload)

    def write_frame(self, channel_id: int, payload: bytes) -> None:
        if not 1 <= channel_id <= MAX_CHANNEL_ID:
            raise ValueError("channel_id 超出范围")
        if len(payload) > MAX_MESSAGE_LENGTH:
            raise ValueError("payload 过大")
        header = struct.pack(">II", channel_id, len(payload))
        padded_payload = payload if len(payload) >= 8 else payload.ljust(8, b"\x00")
        with self._write_lock:
            self.transport.write_all(header)
            self.transport.write_all(padded_payload)
            self.capture.frame("pc_to_phone", channel_id, payload)

    def send_create_socket(
        self,
        port: int,
        channel_id: int,
        socket_type: int,
        message_type: int,
    ) -> None:
        message = CreateSocketMessage(port, channel_id, socket_type, message_type)
        self.write_frame(CONTROL_CHANNEL_ID, message.encode())
        self.log(
            f"发送通道请求：{PORT_NAMES.get(port, port)} "
            f"channel={channel_id} socketType={socket_type} msgType={message_type}"
        )

    def handle_control(self, frame: MultiplexFrame) -> None:
        message = CreateSocketMessage.parse(frame.payload)
        name = PORT_NAMES.get(message.port, f"PORT-{message.port}")
        self.log(
            f"手机请求通道：{name} channel={message.channel_id} "
            f"socketType={message.socket_type} msgType={message.message_type}"
        )
        if message.socket_type == 1:
            channel_id = FIXED_CHANNEL_IDS.get(message.port)
            if channel_id is None:
                channel_id = self.next_dynamic_channel
                self.next_dynamic_channel += 1
            established = CreateSocketMessage(
                message.port,
                channel_id,
                2,
                message.message_type,
            )
            self.channels[channel_id] = established
            self.send_create_socket(
                message.port,
                channel_id,
                2,
                message.message_type,
            )
        elif message.socket_type == 2:
            self.channels[message.channel_id] = message
        else:
            self.log(f"忽略未知 socketType={message.socket_type}")
            return

        ready_ports = {item.port for item in self.channels.values()}
        if 7236 in ready_ports and 4321 in ready_ports:
            if not self.requested_auth:
                self.requested_auth = True
                self.send_create_socket(57209, -1, 1, 2)
            if not self.requested_rtp:
                self.requested_rtp = True
                self.send_create_socket(15550, -1, 1, 2)

    def describe_channel(self, channel_id: int) -> str:
        message = self.channels.get(channel_id)
        if message is None:
            return f"CHANNEL-{channel_id}"
        return PORT_NAMES.get(message.port, f"PORT-{message.port}")
