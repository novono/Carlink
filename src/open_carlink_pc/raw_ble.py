from __future__ import annotations

import struct
import time
from dataclasses import dataclass
from uuid import UUID

import libusb_package
import usb.core
import usb.util


RAW_HCI_USB_DEVICES = (
    (0x0CF3, 0xE009, "Qualcomm QCA9377"),
    (0x13D3, 0x3558, "Realtek 8821CE Bluetooth"),
)

ICCOA_ADVERTISEMENT_UUID16 = 0xFCFB
ICCOA_PRIMARY_DATA_UUID16 = 0x0001
ICCOA_CAR_NAME_DATA_UUID16 = 0x0002
ICCOA_SHARE_SERVICE_UUID = UUID("2abcc850-9935-4f8a-ba84-123456789100")
ICCOA_CLIENT_INFO_UUID = UUID("2abcc850-9935-4f8a-ba84-123456789101")
ICCOA_SERVER_INFO_UUID = UUID("2abcc850-9935-4f8a-ba84-123456789102")

ATT_CID = 0x0004
LE_SIGNALING_CID = 0x0005


class RawBleError(RuntimeError):
    pass


def uuid128_le(value: UUID) -> bytes:
    """Return the Bluetooth over-the-air byte order for a 128-bit UUID."""
    return value.bytes[::-1]


def _uuid16(value: int) -> bytes:
    return struct.pack("<H", value)


class RawHciController:
    """Minimal transport for a supported USB Bluetooth HCI bound to WinUSB."""

    def __init__(self) -> None:
        self.device = None
        self.device_name = ""
        self.connection_handle: int | None = None
        self.acl_data_length = 27
        self._event_queue: list[bytes] = []
        self._acl_fragments: dict[int, bytearray] = {}
        self._acl_expected: dict[int, int] = {}

    def open(self) -> None:
        backend = libusb_package.get_libusb1_backend()
        failures: list[str] = []
        for vendor_id, product_id, name in RAW_HCI_USB_DEVICES:
            device = usb.core.find(
                idVendor=vendor_id,
                idProduct=product_id,
                backend=backend,
            )
            if device is None:
                continue
            try:
                try:
                    device.set_configuration()
                except usb.core.USBError:
                    # A configuration may already be active after the driver switch.
                    pass
                usb.util.claim_interface(device, 0)
            except (usb.core.USBError, NotImplementedError) as exc:
                failures.append(f"{name}: {exc}")
                try:
                    usb.util.dispose_resources(device)
                except (usb.core.USBError, NotImplementedError):
                    pass
                continue
            self.device = device
            self.device_name = name
            return
        if failures:
            raise RawBleError(
                "找到支持的蓝牙控制器，但无法占用 WinUSB HCI 接口："
                + "; ".join(failures)
            )
        supported = ", ".join(
            f"{name} ({vendor_id:04X}:{product_id:04X})"
            for vendor_id, product_id, name in RAW_HCI_USB_DEVICES
        )
        raise RawBleError(f"未找到已切换为 WinUSB 的支持控制器：{supported}")

    def close(self) -> None:
        device = self.device
        self.device = None
        self.device_name = ""
        if device is not None:
            try:
                usb.util.release_interface(device, 0)
            except (usb.core.USBError, NotImplementedError):
                pass
            try:
                usb.util.dispose_resources(device)
            except (usb.core.USBError, NotImplementedError):
                pass

    def _require_device(self):
        if self.device is None:
            raise RawBleError("QCA9377 HCI 接口尚未打开")
        return self.device

    def read_event(self, timeout_ms: int = 100) -> bytes | None:
        if self._event_queue:
            return self._event_queue.pop(0)
        try:
            return bytes(self._require_device().read(0x81, 260, timeout=timeout_ms))
        except usb.core.USBTimeoutError:
            return None

    def read_acl(self, timeout_ms: int = 20) -> bytes | None:
        try:
            return bytes(self._require_device().read(0x82, 4096, timeout=timeout_ms))
        except usb.core.USBTimeoutError:
            return None

    def command(self, opcode: int, parameters: bytes = b"", timeout: float = 3.0) -> bytes:
        device = self._require_device()
        packet = struct.pack("<HB", opcode, len(parameters)) + parameters
        device.ctrl_transfer(0x20, 0, 0, 0, packet, timeout=3000)
        deadline = time.monotonic() + timeout
        deferred: list[bytes] = []
        while time.monotonic() < deadline:
            event = self.read_event(timeout_ms=250)
            if event is None:
                continue
            if (
                len(event) >= 6
                and event[0] == 0x0E
                and int.from_bytes(event[3:5], "little") == opcode
            ):
                status = event[5]
                self._event_queue[0:0] = deferred
                if status:
                    raise RawBleError(f"HCI 命令 0x{opcode:04X} 失败，状态 0x{status:02X}")
                return event
            deferred.append(event)
        self._event_queue[0:0] = deferred
        raise RawBleError(f"等待 HCI 命令 0x{opcode:04X} 响应超时")

    def initialize(self) -> None:
        self.command(0x0C03)  # Reset
        self.command(0x0C01, b"\xff" * 8)  # Set Event Mask
        self.command(0x2001, b"\xff" * 8)  # LE Set Event Mask
        buffer_size = self.command(0x2002)  # LE Read Buffer Size
        if len(buffer_size) >= 9:
            advertised_length = int.from_bytes(buffer_size[6:8], "little")
            if advertised_length:
                self.acl_data_length = advertised_length

    def start_iccoa_advertising(self, primary_data: bytes, car_name_data: bytes) -> None:
        if len(primary_data) != 15:
            raise ValueError("ICCOA INFO1 必须为 15 字节")
        if len(car_name_data) != 16:
            raise ValueError("ICCOA INFO2 必须为 16 字节")

        self.command(0x200A, b"\x00")
        parameters = b"".join(
            (
                struct.pack("<H", 160),
                struct.pack("<H", 160),
                b"\x00",  # ADV_IND: connectable and scannable
                b"\x00",  # public own address
                b"\x00",  # public direct address type
                b"\x00" * 6,
                b"\x07",  # all three advertising channels
                b"\x00",  # accept all scan/connect requests
            )
        )
        self.command(0x2006, parameters)

        advertisement = b"".join(
            (
                b"\x02\x01\x06",  # general discoverable, BR/EDR unsupported
                b"\x03\x03" + _uuid16(ICCOA_ADVERTISEMENT_UUID16),
                b"\x12\x16" + _uuid16(ICCOA_PRIMARY_DATA_UUID16) + primary_data,
            )
        )
        scan_response = (
            b"\x13\x16" + _uuid16(ICCOA_CAR_NAME_DATA_UUID16) + car_name_data
        )
        self.command(0x2008, bytes((len(advertisement),)) + advertisement.ljust(31, b"\x00"))
        self.command(0x2009, bytes((len(scan_response),)) + scan_response.ljust(31, b"\x00"))
        self.command(0x200A, b"\x01")

    def stop_advertising(self) -> None:
        self.command(0x200A, b"\x00")

    def process_event(self, event: bytes) -> tuple[str, object] | None:
        if len(event) < 2:
            return None
        event_code = event[0]
        if event_code == 0x3E and len(event) >= 6:
            subevent = event[2]
            if subevent in (0x01, 0x0A) and event[3] == 0:
                handle = int.from_bytes(event[4:6], "little") & 0x0FFF
                self.connection_handle = handle
                return "connected", handle
        if event_code == 0x05 and len(event) >= 6:
            handle = int.from_bytes(event[3:5], "little") & 0x0FFF
            reason = event[5]
            if handle == self.connection_handle:
                self.connection_handle = None
            self._acl_fragments.pop(handle, None)
            self._acl_expected.pop(handle, None)
            return "disconnected", (handle, reason)
        return None

    def process_acl(self, packet: bytes) -> list[tuple[int, int, bytes]]:
        if len(packet) < 4:
            return []
        header, packet_length = struct.unpack_from("<HH", packet)
        handle = header & 0x0FFF
        boundary = (header >> 12) & 0x03
        fragment = packet[4 : 4 + packet_length]
        completed: list[tuple[int, int, bytes]] = []

        if boundary in (0, 2):
            if len(fragment) < 4:
                return []
            l2cap_length, cid = struct.unpack_from("<HH", fragment)
            value = bytearray(fragment[4:])
            self._acl_fragments[handle] = value
            self._acl_expected[handle] = l2cap_length
            if len(value) >= l2cap_length:
                completed.append((handle, cid, bytes(value[:l2cap_length])))
                self._acl_fragments.pop(handle, None)
                self._acl_expected.pop(handle, None)
            return completed

        if boundary == 1 and handle in self._acl_fragments:
            value = self._acl_fragments[handle]
            value.extend(fragment)
            expected = self._acl_expected[handle]
            if len(value) >= expected:
                # Continuation fragments retain the CID from their initial packet.
                # ATT is the only fragmented channel used during ICCOA bootstrap.
                completed.append((handle, ATT_CID, bytes(value[:expected])))
                self._acl_fragments.pop(handle, None)
                self._acl_expected.pop(handle, None)
        return completed

    def send_l2cap(self, handle: int, cid: int, payload: bytes) -> None:
        device = self._require_device()
        l2cap = struct.pack("<HH", len(payload), cid) + payload
        packet_size = max(27, self.acl_data_length)
        offset = 0
        first = True
        while offset < len(l2cap):
            fragment = l2cap[offset : offset + packet_size]
            boundary = 2 if first else 1
            acl_header = (handle & 0x0FFF) | (boundary << 12)
            packet = struct.pack("<HH", acl_header, len(fragment)) + fragment
            device.write(0x02, packet, timeout=3000)
            offset += len(fragment)
            first = False


@dataclass(slots=True)
class GattAttribute:
    handle: int
    type_uuid: bytes
    value: bytes
    readable: bool = False
    writable: bool = False
    group_end: int = 0


@dataclass(slots=True)
class AttResult:
    response: bytes | None = None
    client_info: bytes | None = None
    indication_confirmed: bool = False


class IccoaGattServer:
    """Small ATT database containing the ICCOA Wi-Fi bootstrap service."""

    server_mtu = 247
    client_info_handle = 9
    server_info_handle = 11
    cccd_handle = 12

    def __init__(self, device_name: str = "PC CarLink") -> None:
        self.negotiated_mtu = 23
        self.cccd = 0
        self._prepared_writes: dict[int, dict[int, bytes]] = {}
        self.attributes = self._build_attributes(device_name)
        self.by_handle = {attribute.handle: attribute for attribute in self.attributes}

    @staticmethod
    def _build_attributes(device_name: str) -> list[GattAttribute]:
        primary = _uuid16(0x2800)
        characteristic = _uuid16(0x2803)
        share_uuid = uuid128_le(ICCOA_SHARE_SERVICE_UUID)
        client_uuid = uuid128_le(ICCOA_CLIENT_INFO_UUID)
        server_uuid = uuid128_le(ICCOA_SERVER_INFO_UUID)
        return [
            GattAttribute(1, primary, _uuid16(0x1800), True, group_end=5),
            GattAttribute(2, characteristic, b"\x02\x03\x00" + _uuid16(0x2A00), True),
            GattAttribute(3, _uuid16(0x2A00), device_name.encode("utf-8"), True),
            GattAttribute(4, characteristic, b"\x02\x05\x00" + _uuid16(0x2A01), True),
            GattAttribute(5, _uuid16(0x2A01), b"\x00\x00", True),
            GattAttribute(6, primary, _uuid16(0x1801), True, group_end=6),
            GattAttribute(7, primary, share_uuid, True, group_end=12),
            GattAttribute(8, characteristic, b"\x08\x09\x00" + client_uuid, True),
            GattAttribute(9, client_uuid, b"", writable=True),
            GattAttribute(10, characteristic, b"\x10\x0b\x00" + server_uuid, True),
            GattAttribute(11, server_uuid, b""),
            GattAttribute(12, _uuid16(0x2902), b"\x00\x00", True, True),
        ]

    @staticmethod
    def _error(request_opcode: int, handle: int, error_code: int) -> bytes:
        return bytes((0x01, request_opcode)) + struct.pack("<H", handle) + bytes((error_code,))

    def _matching(self, start: int, end: int, type_uuid: bytes) -> list[GattAttribute]:
        return [
            attribute
            for attribute in self.attributes
            if start <= attribute.handle <= end and attribute.type_uuid == type_uuid
        ]

    def handle(self, request: bytes) -> AttResult:
        if not request:
            return AttResult()
        opcode = request[0]

        if opcode == 0x02 and len(request) >= 3:
            client_mtu = int.from_bytes(request[1:3], "little")
            self.negotiated_mtu = max(23, min(client_mtu, self.server_mtu))
            return AttResult(b"\x03" + struct.pack("<H", self.server_mtu))

        if opcode == 0x04 and len(request) >= 5:
            start, end = struct.unpack_from("<HH", request, 1)
            candidates = [a for a in self.attributes if start <= a.handle <= end]
            if not candidates:
                return AttResult(self._error(opcode, start, 0x0A))
            uuid_length = len(candidates[0].type_uuid)
            output = bytearray((0x05, 0x01 if uuid_length == 2 else 0x02))
            for attribute in candidates:
                if len(attribute.type_uuid) != uuid_length:
                    break
                entry = struct.pack("<H", attribute.handle) + attribute.type_uuid
                if len(output) + len(entry) > self.negotiated_mtu:
                    break
                output.extend(entry)
            return AttResult(bytes(output))

        if opcode == 0x06 and len(request) >= 7:
            start, end, attribute_type = struct.unpack_from("<HHH", request, 1)
            value = request[7:]
            matches = [
                a
                for a in self.attributes
                if start <= a.handle <= end
                and a.type_uuid == _uuid16(attribute_type)
                and a.value == value
            ]
            if not matches:
                return AttResult(self._error(opcode, start, 0x0A))
            output = bytearray((0x07,))
            for attribute in matches:
                output.extend(struct.pack("<HH", attribute.handle, attribute.group_end))
            return AttResult(bytes(output[: self.negotiated_mtu]))

        if opcode == 0x08 and len(request) in (7, 21):
            start, end = struct.unpack_from("<HH", request, 1)
            type_uuid = request[5:]
            matches = self._matching(start, end, type_uuid)
            if not matches:
                return AttResult(self._error(opcode, start, 0x0A))
            entry_length = 2 + len(matches[0].value)
            output = bytearray((0x09, entry_length))
            for attribute in matches:
                if 2 + len(attribute.value) != entry_length:
                    break
                entry = struct.pack("<H", attribute.handle) + attribute.value
                if len(output) + len(entry) > self.negotiated_mtu:
                    break
                output.extend(entry)
            return AttResult(bytes(output))

        if opcode in (0x0A, 0x0C) and len(request) >= 3:
            handle = int.from_bytes(request[1:3], "little")
            offset = int.from_bytes(request[3:5], "little") if opcode == 0x0C else 0
            attribute = self.by_handle.get(handle)
            if attribute is None:
                return AttResult(self._error(opcode, handle, 0x01))
            if not attribute.readable:
                return AttResult(self._error(opcode, handle, 0x02))
            if offset > len(attribute.value):
                return AttResult(self._error(opcode, handle, 0x07))
            response_opcode = 0x0D if opcode == 0x0C else 0x0B
            return AttResult(bytes((response_opcode,)) + attribute.value[offset : offset + self.negotiated_mtu - 1])

        if opcode == 0x10 and len(request) in (7, 21):
            start, end = struct.unpack_from("<HH", request, 1)
            type_uuid = request[5:]
            matches = self._matching(start, end, type_uuid)
            if not matches:
                return AttResult(self._error(opcode, start, 0x0A))
            entry_length = 4 + len(matches[0].value)
            output = bytearray((0x11, entry_length))
            for attribute in matches:
                if 4 + len(attribute.value) != entry_length:
                    break
                entry = struct.pack("<HH", attribute.handle, attribute.group_end) + attribute.value
                if len(output) + len(entry) > self.negotiated_mtu:
                    break
                output.extend(entry)
            return AttResult(bytes(output))

        if opcode in (0x12, 0x52) and len(request) >= 3:
            handle = int.from_bytes(request[1:3], "little")
            value = request[3:]
            attribute = self.by_handle.get(handle)
            if attribute is None:
                response = None if opcode == 0x52 else self._error(opcode, handle, 0x01)
                return AttResult(response)
            if not attribute.writable:
                response = None if opcode == 0x52 else self._error(opcode, handle, 0x03)
                return AttResult(response)
            attribute.value = value
            if handle == self.cccd_handle and len(value) >= 2:
                self.cccd = int.from_bytes(value[:2], "little")
            response = None if opcode == 0x52 else b"\x13"
            return AttResult(response, value if handle == self.client_info_handle else None)

        if opcode == 0x16 and len(request) >= 5:
            handle, offset = struct.unpack_from("<HH", request, 1)
            attribute = self.by_handle.get(handle)
            if attribute is None:
                return AttResult(self._error(opcode, handle, 0x01))
            if not attribute.writable:
                return AttResult(self._error(opcode, handle, 0x03))
            self._prepared_writes.setdefault(handle, {})[offset] = request[5:]
            return AttResult(b"\x17" + request[1:])

        if opcode == 0x18 and len(request) >= 2:
            client_info = None
            if request[1] == 0x01:
                for handle, pieces in self._prepared_writes.items():
                    value = bytearray()
                    for offset, piece in sorted(pieces.items()):
                        if offset > len(value):
                            value.extend(b"\x00" * (offset - len(value)))
                        value[offset : offset + len(piece)] = piece
                    self.by_handle[handle].value = bytes(value)
                    if handle == self.client_info_handle:
                        client_info = bytes(value)
            self._prepared_writes.clear()
            return AttResult(b"\x19", client_info)

        if opcode == 0x1E:
            return AttResult(indication_confirmed=True)

        # Commands (bit 6 set) have no response; unsupported requests do.
        if opcode & 0x40:
            return AttResult()
        return AttResult(self._error(opcode, 0x0000, 0x06))

    def server_info_indication(self, value: bytes) -> bytes:
        limit = max(0, self.negotiated_mtu - 3)
        return b"\x1d" + struct.pack("<H", self.server_info_handle) + value[:limit]


def le_connection_parameter_response(identifier: int) -> bytes:
    return bytes((0x13, identifier, 0x02, 0x00, 0x00, 0x00))
