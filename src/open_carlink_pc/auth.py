from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import struct
from dataclasses import dataclass
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from .config import CarIdentity
from .ucar_protocol import (
    FORMAT_PB3,
    MESSAGE_RES,
    UCarHeader,
    _build_header,
    crc16_modbus,
)


CATEGORY_AUTH = 3
METHOD_AUTH_REQUEST = 1
METHOD_AUTH_RESPONSE = 2
METHOD_AUTH_CONFIRM = 3


@dataclass(frozen=True, slots=True)
class AuthRequest:
    version: int
    auth_public_key: bytes
    auth_public_key_hmac: bytes
    agreement_public_key: bytes
    agreement_public_key_signature: bytes
    nonce: bytes
    device_id: bytes
    model: str
    user_confirmed: bool


def _read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while offset < len(data) and shift < 70:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7
    raise ValueError("无效 protobuf varint")


def _parse_protobuf(data: bytes) -> dict[int, list[int | bytes]]:
    result: dict[int, list[int | bytes]] = {}
    offset = 0
    while offset < len(data):
        tag, offset = _read_varint(data, offset)
        field_number = tag >> 3
        wire_type = tag & 0x07
        if field_number == 0:
            raise ValueError("protobuf 字段号不能为 0")
        if wire_type == 0:
            value, offset = _read_varint(data, offset)
        elif wire_type == 1:
            if offset + 8 > len(data):
                raise ValueError("protobuf fixed64 越界")
            value = data[offset : offset + 8]
            offset += 8
        elif wire_type == 2:
            length, offset = _read_varint(data, offset)
            if offset + length > len(data):
                raise ValueError("protobuf bytes 越界")
            value = data[offset : offset + length]
            offset += length
        elif wire_type == 5:
            if offset + 4 > len(data):
                raise ValueError("protobuf fixed32 越界")
            value = data[offset : offset + 4]
            offset += 4
        else:
            raise ValueError(f"不支持的 protobuf wire type：{wire_type}")
        result.setdefault(field_number, []).append(value)
    return result


def _last_int(fields: dict[int, list[int | bytes]], number: int) -> int:
    values = fields.get(number)
    if not values:
        return 0
    value = values[-1]
    if not isinstance(value, int):
        raise ValueError(f"protobuf 字段 {number} 类型错误")
    return value


def _last_bytes(fields: dict[int, list[int | bytes]], number: int) -> bytes:
    values = fields.get(number)
    if not values:
        return b""
    value = values[-1]
    if not isinstance(value, bytes):
        raise ValueError(f"protobuf 字段 {number} 类型错误")
    return value


def parse_auth_request(body: bytes) -> AuthRequest:
    fields = _parse_protobuf(body)
    try:
        model = _last_bytes(fields, 8).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("手机型号不是有效 UTF-8") from exc
    return AuthRequest(
        version=_last_int(fields, 1),
        auth_public_key=_last_bytes(fields, 2),
        auth_public_key_hmac=_last_bytes(fields, 3),
        agreement_public_key=_last_bytes(fields, 4),
        agreement_public_key_signature=_last_bytes(fields, 5),
        nonce=_last_bytes(fields, 6),
        device_id=_last_bytes(fields, 7),
        model=model,
        user_confirmed=bool(_last_int(fields, 9)),
    )


def _encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("protobuf varint 不能为负数")
    result = bytearray()
    while value > 0x7F:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def _field_varint(number: int, value: int) -> bytes:
    if value == 0:
        return b""
    return _encode_varint(number << 3) + _encode_varint(value)


def _field_bytes(number: int, value: bytes) -> bytes:
    if not value:
        return b""
    return _encode_varint((number << 3) | 2) + _encode_varint(len(value)) + value


def _raw_public_key(key: ec.EllipticCurvePublicKey) -> bytes:
    numbers = key.public_numbers()
    size = (key.curve.key_size + 7) // 8
    return numbers.x.to_bytes(size, "big") + numbers.y.to_bytes(size, "big")


def _load_raw_public_key(data: bytes) -> ec.EllipticCurvePublicKey:
    if len(data) != 64:
        raise ValueError(f"P-256 公钥长度应为 64，实际为 {len(data)}")
    numbers = ec.EllipticCurvePublicNumbers(
        int.from_bytes(data[:32], "big"),
        int.from_bytes(data[32:], "big"),
        ec.SECP256R1(),
    )
    return numbers.public_key()


def _pin_hmac(pin: bytes, nonce_context: bytes, data: bytes) -> bytes:
    key = hashlib.sha256(nonce_context + pin).digest()
    return hmac.new(key, data, hashlib.sha256).digest()


def _derive_session_key(
    private_key: ec.EllipticCurvePrivateKey,
    peer_public_key: ec.EllipticCurvePublicKey,
    client_nonce: bytes,
    server_nonce: bytes,
) -> bytes:
    secret = private_key.exchange(ec.ECDH(), peer_public_key)
    return HKDF(
        algorithm=hashes.SHA256(),
        length=16,
        salt=hashlib.sha256(client_nonce + server_nonce).digest(),
        info=b"session_key",
    ).derive(secret)


def decrypt_session_payload(session_key: bytes, payload: bytes) -> bytes:
    result = bytearray()
    offset = 0
    while offset < len(payload):
        if offset + 4 > len(payload):
            raise ValueError("AES-GCM IV 长度字段不完整")
        iv_length = struct.unpack_from(">I", payload, offset)[0]
        offset += 4
        if iv_length <= 0 or offset + iv_length + 4 > len(payload):
            raise ValueError("AES-GCM IV 长度无效")
        iv = payload[offset : offset + iv_length]
        offset += iv_length
        encrypted_length = struct.unpack_from(">I", payload, offset)[0]
        offset += 4
        if encrypted_length <= 16 or offset + encrypted_length > len(payload):
            raise ValueError("AES-GCM 密文长度无效")
        encrypted = payload[offset : offset + encrypted_length]
        offset += encrypted_length
        result.extend(AESGCM(session_key).decrypt(iv, encrypted, None))
    return bytes(result)


def encrypt_session_payload(session_key: bytes, payload: bytes) -> bytes:
    iv = os.urandom(12)
    encrypted = AESGCM(session_key).encrypt(iv, payload, None)
    return struct.pack(">I", len(iv)) + iv + struct.pack(">I", len(encrypted)) + encrypted


def _replace_message_body(message: bytes, body: bytes) -> bytes:
    if len(message) < 20:
        raise ValueError("UCar 消息不足 20 字节")
    header = bytearray(message[:20])
    struct.pack_into(">I", header, 0, 20 + len(body))
    struct.pack_into(">H", header, 18, crc16_modbus(header[:18]))
    return bytes(header) + body


def encrypt_session_message(session_key: bytes, message: bytes) -> bytes:
    UCarHeader.parse(message)
    return _replace_message_body(message, encrypt_session_payload(session_key, message[20:]))


def decrypt_session_message(session_key: bytes, message: bytes) -> bytes:
    UCarHeader.parse(message)
    return _replace_message_body(message, decrypt_session_payload(session_key, message[20:]))


class AuthSession:
    def __init__(
        self,
        identity: CarIdentity,
        storage_dir: Path,
        *,
        pin_code: str | None = None,
    ) -> None:
        self.identity = identity
        self.storage_dir = storage_dir
        self.pin_code = pin_code or identity.pin_code
        self.session_key: bytes | None = None
        self.phone_id: str | None = None
        self.connection_info: str | None = None
        self.confirmed = False

    def _private_key_path(self) -> Path:
        return self.storage_dir / f"car-auth-{self.identity.car_id}.pem"

    def _peers_path(self) -> Path:
        return self.storage_dir / f"peers-{self.identity.car_id}.json"

    def _load_or_create_auth_key(self) -> ec.EllipticCurvePrivateKey:
        path = self._private_key_path()
        if path.exists():
            key = serialization.load_pem_private_key(path.read_bytes(), password=None)
            if not isinstance(key, ec.EllipticCurvePrivateKey):
                raise ValueError("保存的车机认证密钥类型错误")
            return key
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        key = ec.generate_private_key(ec.SECP256R1())
        data = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        path.write_bytes(data)
        return key

    def _load_peers(self) -> dict[str, str]:
        path = self._peers_path()
        if not path.exists():
            return {}
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}
        return value if isinstance(value, dict) else {}

    def _save_peer(self, device_id: bytes, public_key: bytes) -> None:
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        peers = self._load_peers()
        peers[base64.b64encode(device_id).decode("ascii")] = base64.b64encode(public_key).decode("ascii")
        self._peers_path().write_text(
            json.dumps(peers, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def _load_peer_key(self, device_id: bytes) -> bytes | None:
        encoded = self._load_peers().get(base64.b64encode(device_id).decode("ascii"))
        if not isinstance(encoded, str):
            return None
        try:
            return base64.b64decode(encoded, validate=True)
        except ValueError:
            return None

    @staticmethod
    def _result_response(sequence_id: int, result: int) -> bytes:
        body = _field_varint(1, 1) + _field_varint(7, result)
        return _build_header(
            body_length=len(body),
            sequence_id=sequence_id,
            data_format=FORMAT_PB3,
            message_type=MESSAGE_RES,
            category=CATEGORY_AUTH,
            method=METHOD_AUTH_RESPONSE,
        ) + body

    def _handle_request(self, header: UCarHeader, body: bytes) -> tuple[str, bytes]:
        request = parse_auth_request(body)
        if len(request.nonce) != 32 or not request.device_id or len(request.agreement_public_key) != 64:
            return "手机认证请求缺少必要字段", self._result_response(header.sequence_id, 3)

        pin = self.pin_code.encode("utf-8")
        phone_auth_raw = request.auth_public_key
        normal_connection = bool(phone_auth_raw and request.auth_public_key_hmac)
        if normal_connection:
            expected_mac = _pin_hmac(pin, request.nonce, phone_auth_raw)
            if not hmac.compare_digest(expected_mac, request.auth_public_key_hmac):
                return "手机认证 PIN 校验失败", self._result_response(header.sequence_id, 3)
        else:
            phone_auth_raw = self._load_peer_key(request.device_id) or b""
            if not phone_auth_raw:
                return "手机请求快速认证，需转为首次认证", self._result_response(header.sequence_id, 1)

        try:
            phone_auth_key = _load_raw_public_key(phone_auth_raw)
            phone_agreement_key = _load_raw_public_key(request.agreement_public_key)
            phone_auth_key.verify(
                request.agreement_public_key_signature,
                request.agreement_public_key + request.nonce,
                ec.ECDSA(hashes.SHA256()),
            )
        except (InvalidSignature, ValueError) as exc:
            raise ValueError("手机认证签名校验失败") from exc

        car_auth_key = self._load_or_create_auth_key()
        car_auth_raw = _raw_public_key(car_auth_key.public_key())
        car_agreement_key = ec.generate_private_key(ec.SECP256R1())
        car_agreement_raw = _raw_public_key(car_agreement_key.public_key())
        car_nonce = os.urandom(32)
        self.session_key = _derive_session_key(
            car_agreement_key,
            phone_agreement_key,
            request.nonce,
            car_nonce,
        )
        self.phone_id = request.device_id.decode("utf-8", errors="replace")

        if normal_connection:
            self._save_peer(request.device_id, phone_auth_raw)
            response_auth_raw = car_auth_raw
            response_auth_hmac = _pin_hmac(
                pin,
                car_nonce + request.nonce,
                car_auth_raw + phone_auth_raw,
            )
        else:
            response_auth_raw = b""
            response_auth_hmac = b""

        agreement_signature = car_auth_key.sign(
            car_agreement_raw + request.agreement_public_key + car_nonce + request.nonce,
            ec.ECDSA(hashes.SHA256()),
        )
        response_body = b"".join(
            (
                _field_varint(1, 1),
                _field_bytes(2, response_auth_raw),
                _field_bytes(3, response_auth_hmac),
                _field_bytes(4, car_agreement_raw),
                _field_bytes(5, agreement_signature),
                _field_bytes(6, car_nonce),
            )
        )
        response = _build_header(
            body_length=len(response_body),
            sequence_id=header.sequence_id,
            data_format=FORMAT_PB3,
            message_type=MESSAGE_RES,
            category=CATEGORY_AUTH,
            method=METHOD_AUTH_RESPONSE,
            reserved=header.reserved,
        ) + response_body
        mode = "首次" if normal_connection else "快速"
        return f"已完成 {request.model or 'OPPO 手机'}的{mode}密钥协商", response

    def _handle_confirm(self, body: bytes) -> str:
        if self.session_key is None:
            raise ValueError("尚未生成会话密钥却收到认证确认")
        cipher = _last_bytes(_parse_protobuf(body), 1)
        plaintext = decrypt_session_payload(self.session_key, cipher)
        connection_info = plaintext.decode("utf-8", errors="replace")
        self.connection_info = connection_info
        self.confirmed = True
        return f"手机认证成功：{connection_info.split(':', 1)[0]}"

    def handle(self, message: bytes) -> tuple[str, bytes | None]:
        header = UCarHeader.parse(message)
        if header.category != CATEGORY_AUTH or header.data_format != FORMAT_PB3:
            raise ValueError("AUTH 通道收到非认证消息")
        body = message[20:]
        if header.method == METHOD_AUTH_REQUEST:
            return self._handle_request(header, body)
        if header.method == METHOD_AUTH_CONFIRM:
            return self._handle_confirm(body), None
        return f"收到未知认证方法 {header.method}", None
