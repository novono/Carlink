from __future__ import annotations

import struct
import time
from dataclasses import dataclass

from .config import CarIdentity

HEADER_SIZE = 20
SOURCE_CAR = 0
FORMAT_PB3 = 1
MESSAGE_SEND = 0
MESSAGE_REQ = 1
MESSAGE_RES = 2
MESSAGE_NOTIFY = 3
CATEGORY_CONTROL = 1
METHOD_HEARTBEAT = 1
METHOD_SESSION_DISCONNECT = 7
METHOD_GET_UCAR_CONFIG_REQUEST = 24
METHOD_GET_UCAR_CONFIG_RESPONSE = 25
MAX_STREAM_MESSAGE_LENGTH = 8 * 1024 * 1024


class UCarStreamReassembler:
    """Reassemble UCar messages split across AOA socket payload frames."""

    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(self, data: bytes) -> list[bytes]:
        self._buffer.extend(data)
        messages: list[bytes] = []
        while len(self._buffer) >= HEADER_SIZE:
            length = struct.unpack_from(">I", self._buffer)[0]
            if length < HEADER_SIZE or length > MAX_STREAM_MESSAGE_LENGTH:
                self._buffer.clear()
                raise ValueError(f"UCar 流中的消息长度无效：{length}")
            if len(self._buffer) < length:
                break
            message = bytes(self._buffer[:length])
            del self._buffer[:length]
            UCarHeader.parse(message)
            messages.append(message)
        return messages


def crc16_modbus(data: bytes) -> int:
    crc = 0
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF


@dataclass(frozen=True, slots=True)
class UCarHeader:
    length: int
    sequence_id: int
    timestamp: int
    source: int
    data_format: int
    message_type: int
    category: int
    method: int
    reserved: int

    @classmethod
    def parse(cls, message: bytes) -> UCarHeader:
        if len(message) < HEADER_SIZE:
            raise ValueError("UCar 消息不足 20 字节")
        length, sequence_id, timestamp = struct.unpack_from(">III", message)
        if length != len(message):
            raise ValueError(f"UCar 长度字段 {length} 与实际 {len(message)} 不符")
        expected_crc = struct.unpack_from(">H", message, 18)[0]
        actual_crc = crc16_modbus(message[:18])
        if expected_crc != actual_crc:
            raise ValueError(
                f"UCar 头 CRC 错误：期望 {expected_crc:04X}，计算 {actual_crc:04X}"
            )
        flags = message[12]
        return cls(
            length=length,
            sequence_id=sequence_id,
            timestamp=timestamp,
            source=flags & 0x01,
            data_format=(flags & 0x06) >> 1,
            message_type=(flags & 0x18) >> 3,
            category=message[13],
            method=struct.unpack_from(">H", message, 14)[0],
            reserved=struct.unpack_from(">H", message, 16)[0],
        )


def is_session_disconnect(message: bytes) -> bool:
    header = UCarHeader.parse(message)
    return (
        header.category == CATEGORY_CONTROL
        and header.method == METHOD_SESSION_DISCONNECT
        and header.message_type == MESSAGE_NOTIFY
    )


def build_session_disconnect(sequence_id: int) -> bytes:
    if sequence_id < 0:
        raise ValueError("断开通知 sequence_id 不能为负数")
    body = _field_varint(1, 1)
    header = _build_header(
        body_length=len(body),
        sequence_id=sequence_id,
        data_format=FORMAT_PB3,
        message_type=MESSAGE_NOTIFY,
        category=CATEGORY_CONTROL,
        method=METHOD_SESSION_DISCONNECT,
    )
    return header + body


def _encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("protobuf varint 必须为非负整数")
    result = bytearray()
    while value > 0x7F:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def _field_varint(field_number: int, value: int) -> bytes:
    if value == 0:
        return b""
    return _encode_varint(field_number << 3) + _encode_varint(value)


def _field_bytes(field_number: int, value: bytes) -> bytes:
    if not value:
        return b""
    return _encode_varint((field_number << 3) | 2) + _encode_varint(len(value)) + value


def _build_header(
    *,
    body_length: int,
    sequence_id: int,
    data_format: int,
    message_type: int,
    category: int,
    method: int,
    reserved: int = 0,
) -> bytes:
    flags = SOURCE_CAR | (data_format << 1) | (message_type << 3)
    header = bytearray(HEADER_SIZE)
    struct.pack_into(
        ">IIIBBHH",
        header,
        0,
        HEADER_SIZE + body_length,
        sequence_id,
        int(time.time()),
        flags,
        category,
        method,
        reserved,
    )
    struct.pack_into(">H", header, 18, crc16_modbus(header[:18]))
    return bytes(header)


def build_get_config_response(
    request: bytes,
    identity: CarIdentity,
    *,
    width: int = 1280,
    height: int = 720,
    dpi: int = 320,
    fps: int = 30,
) -> bytes | None:
    header = UCarHeader.parse(request)
    if not (
        header.category == CATEGORY_CONTROL
        and header.method == METHOD_GET_UCAR_CONFIG_REQUEST
        and header.message_type == MESSAGE_REQ
        and header.data_format == FORMAT_PB3
    ):
        return None

    car_id = bytes.fromhex(identity.car_id)
    custom_field = bytes.fromhex(identity.vendor_data.ljust(4, "0")[:4])
    sdk_version = b"v1.2.12-202301290958-5a25c0f"
    body = b"".join(
        (
            _field_bytes(1, car_id),
            _field_varint(2, width),
            _field_varint(3, height),
            _field_varint(4, dpi),
            _field_varint(5, width),
            _field_varint(6, height),
            _field_varint(7, fps),
            _field_varint(8, 1),
            _field_varint(11, 149),
            _field_varint(12, 1),
            _field_varint(13, 1),
            _field_bytes(16, custom_field),
            _field_bytes(17, sdk_version),
        )
    )
    response_header = _build_header(
        body_length=len(body),
        sequence_id=header.sequence_id,
        data_format=FORMAT_PB3,
        message_type=MESSAGE_RES,
        category=CATEGORY_CONTROL,
        method=METHOD_GET_UCAR_CONFIG_RESPONSE,
        reserved=header.reserved,
    )
    return response_header + body


def build_heartbeat(sequence_id: int, *, timestamp_ms: int | None = None) -> bytes:
    if sequence_id < 0:
        raise ValueError("心跳 sequence_id 不能为负数")
    if timestamp_ms is None:
        timestamp_ms = int(time.time() * 1000)
    body = _field_varint(1, timestamp_ms)
    header = _build_header(
        body_length=len(body),
        sequence_id=sequence_id,
        data_format=FORMAT_PB3,
        message_type=MESSAGE_SEND,
        category=CATEGORY_CONTROL,
        method=METHOD_HEARTBEAT,
    )
    return header + body
