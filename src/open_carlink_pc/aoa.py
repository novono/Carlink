from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable, Iterable

import libusb_package
import usb.backend.libusb1
import usb.core
import usb.util

from .config import CarIdentity


AOA_GET_PROTOCOL = 51
AOA_SEND_IDENTITY = 52
AOA_START_ACCESSORY = 53
AOA_VENDOR_ID = 0x18D1
AOA_PRODUCT_IDS = {0x2D00, 0x2D01, 0x2D04, 0x2D05}

PHONE_VENDOR_NAMES = {
    0x22D9: "OPPO",
    0x2A70: "OnePlus",
    0x18D1: "Google/AOA",
    0x2717: "Xiaomi",
    0x2D95: "vivo",
}

IDENTITY_STRINGS = (
    "ICCOA",
    "CarLink",
    "ICCOA CarLink",
    "1.0.0",
    "http://www.iccoa.cn/",
)


class AoaError(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class UsbDeviceInfo:
    vendor_id: int
    product_id: int
    manufacturer: str
    product: str
    serial: str
    accessory_mode: bool

    @property
    def display_name(self) -> str:
        known = PHONE_VENDOR_NAMES.get(self.vendor_id)
        title = self.product or known or "Android USB device"
        return f"{title} ({self.vendor_id:04X}:{self.product_id:04X})"


def _backend():
    backend = usb.backend.libusb1.get_backend(find_library=libusb_package.find_library)
    if backend is None:
        raise AoaError("找不到 libusb 后端")
    return backend


def _safe_string(device: usb.core.Device, index: int) -> str:
    if not index:
        return ""
    try:
        return usb.util.get_string(device, index) or ""
    except (usb.core.USBError, ValueError):
        return ""


def describe_device(device: usb.core.Device) -> UsbDeviceInfo:
    return UsbDeviceInfo(
        vendor_id=int(device.idVendor),
        product_id=int(device.idProduct),
        manufacturer=_safe_string(device, int(device.iManufacturer)),
        product=_safe_string(device, int(device.iProduct)),
        serial=_safe_string(device, int(device.iSerialNumber)),
        accessory_mode=(
            int(device.idVendor) == AOA_VENDOR_ID
            and int(device.idProduct) in AOA_PRODUCT_IDS
        ),
    )


def enumerate_usb_devices() -> list[tuple[usb.core.Device, UsbDeviceInfo]]:
    devices = usb.core.find(find_all=True, backend=_backend())
    result: list[tuple[usb.core.Device, UsbDeviceInfo]] = []
    for device in devices or ():
        info = describe_device(device)
        if info.accessory_mode or info.vendor_id in PHONE_VENDOR_NAMES:
            result.append((device, info))
    return result


class AoaTransport:
    def __init__(self, device: usb.core.Device, log: Callable[[str], None]) -> None:
        self.device = device
        self.log = log
        self.interface_number: int | None = None
        self.endpoint_in = None
        self.endpoint_out = None

    @staticmethod
    def is_accessory(device: usb.core.Device) -> bool:
        return int(device.idVendor) == AOA_VENDOR_ID and int(device.idProduct) in AOA_PRODUCT_IDS

    @classmethod
    def switch_to_accessory(
        cls,
        device: usb.core.Device,
        identity: CarIdentity,
        log: Callable[[str], None],
    ) -> None:
        try:
            version_data = device.ctrl_transfer(0xC0, AOA_GET_PROTOCOL, 0, 0, 2, timeout=2000)
            if len(version_data) != 2:
                raise AoaError("手机未返回完整 AOA 版本")
            protocol = int(version_data[0]) | (int(version_data[1]) << 8)
            if protocol not in (1, 2):
                raise AoaError(f"手机返回不支持的 AOA 版本：{protocol}")
            log(f"手机支持 AOA {protocol}.0")

            strings: Iterable[str] = (*IDENTITY_STRINGS, identity.aoa_serial)
            for index, value in enumerate(strings):
                payload = value.encode("utf-8")
                sent = device.ctrl_transfer(
                    0x40,
                    AOA_SEND_IDENTITY,
                    0,
                    index,
                    payload,
                    timeout=2000,
                )
                if sent != len(payload):
                    raise AoaError(f"发送 AOA 身份字段 {index} 不完整")
            device.ctrl_transfer(0x40, AOA_START_ACCESSORY, 0, 0, None, timeout=2000)
            log("已请求手机切换到 ICCOA CarLink 配件模式")
        except usb.core.USBError as exc:
            raise AoaError(_explain_usb_error(exc)) from exc
        finally:
            usb.util.dispose_resources(device)

    @classmethod
    def wait_for_accessory(
        cls,
        timeout_seconds: float,
        log: Callable[[str], None],
    ) -> usb.core.Device:
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            device = usb.core.find(
                idVendor=AOA_VENDOR_ID,
                find_all=True,
                backend=_backend(),
            )
            for candidate in device or ():
                if int(candidate.idProduct) in AOA_PRODUCT_IDS:
                    log(f"手机已重新枚举为 AOA {candidate.idVendor:04X}:{candidate.idProduct:04X}")
                    return candidate
            time.sleep(0.3)
        raise AoaError("等待手机进入 AOA 模式超时；请解锁手机并查看是否出现 Car+提示")

    def open(self) -> None:
        try:
            try:
                self.device.set_configuration()
            except usb.core.USBError:
                pass
            configuration = self.device.get_active_configuration()
            accessory_interfaces = sorted(
                (
                    interface
                    for interface in configuration
                    if int(interface.bInterfaceClass) == 0xFF
                    and int(interface.bInterfaceSubClass) == 0xFF
                    and int(interface.bInterfaceProtocol) == 0x00
                ),
                key=lambda interface: int(interface.bInterfaceNumber),
            )
            for interface in accessory_interfaces:
                endpoint_in = None
                endpoint_out = None
                for endpoint in interface:
                    if usb.util.endpoint_type(endpoint.bmAttributes) != usb.util.ENDPOINT_TYPE_BULK:
                        continue
                    direction = usb.util.endpoint_direction(endpoint.bEndpointAddress)
                    if direction == usb.util.ENDPOINT_IN:
                        endpoint_in = endpoint
                    else:
                        endpoint_out = endpoint
                if endpoint_in is not None and endpoint_out is not None:
                    self.interface_number = int(interface.bInterfaceNumber)
                    usb.util.claim_interface(self.device, self.interface_number)
                    self.endpoint_in = endpoint_in
                    self.endpoint_out = endpoint_out
                    self.log(
                        "USB Bulk 通道已打开 "
                        f"IN=0x{endpoint_in.bEndpointAddress:02X} "
                        f"OUT=0x{endpoint_out.bEndpointAddress:02X}"
                    )
                    return
            raise AoaError("AOA 数据接口 MI_00 没有成对的 Bulk IN/OUT 端点")
        except NotImplementedError as exc:
            raise AoaError(
                "Windows 尚未给 AOA 数据接口 MI_00 绑定 WinUSB 驱动；"
                "请安装本项目说明中的 AOA WinUSB 驱动后重试"
            ) from exc
        except usb.core.USBError as exc:
            raise AoaError(_explain_usb_error(exc)) from exc

    def close(self) -> None:
        try:
            if self.interface_number is not None:
                usb.util.release_interface(self.device, self.interface_number)
        except (usb.core.USBError, NotImplementedError):
            pass
        finally:
            usb.util.dispose_resources(self.device)
            self.endpoint_in = None
            self.endpoint_out = None
            self.interface_number = None

    def read_exact(self, length: int, stop_requested: Callable[[], bool]) -> bytes:
        if self.endpoint_in is None:
            raise AoaError("USB 输入端点尚未打开")
        result = bytearray()
        while len(result) < length and not stop_requested():
            wanted = min(16384, length - len(result))
            try:
                chunk = self.endpoint_in.read(wanted, timeout=500)
                result.extend(bytes(chunk))
            except usb.core.USBError as exc:
                if _is_timeout_error(exc):
                    continue
                raise AoaError(_explain_usb_error(exc)) from exc
        if len(result) != length:
            raise AoaError(f"USB 读取中断：期望 {length} 字节，收到 {len(result)} 字节")
        return bytes(result)

    def write_all(self, data: bytes) -> None:
        if self.endpoint_out is None:
            raise AoaError("USB 输出端点尚未打开")
        offset = 0
        try:
            while offset < len(data):
                chunk = data[offset : offset + 16384]
                try:
                    written = int(self.endpoint_out.write(chunk, timeout=3000))
                except usb.core.USBError as exc:
                    if not _is_timeout_error(exc):
                        raise
                    self.log("USB 写入等待超时，正在重试一次…")
                    written = int(self.endpoint_out.write(chunk, timeout=3000))
                if written <= 0:
                    raise AoaError("USB 写入返回 0")
                offset += written
        except usb.core.USBError as exc:
            raise AoaError(_explain_usb_error(exc)) from exc


def _explain_usb_error(error: usb.core.USBError) -> str:
    text = str(error)
    lowered = text.lower()
    if "access" in lowered or "permission" in lowered or "not supported" in lowered:
        return (
            f"Windows 未授权访问手机 USB 接口：{text}。"
            "请关闭手机助手/ADB，并按 docs/USB_DRIVER.md 检查 WinUSB 驱动。"
        )
    if "busy" in lowered:
        return f"手机 USB 接口正被其他程序占用：{text}"
    return f"USB 通信失败：{text}"


def _is_timeout_error(error: usb.core.USBError) -> bool:
    """Handle libusb timeouts that PyUSB exposes as a plain USBError on Windows."""
    if isinstance(error, usb.core.USBTimeoutError):
        return True
    if getattr(error, "errno", None) in {60, 110, 10060}:
        return True
    if getattr(error, "backend_error_code", None) == -7:
        return True
    text = str(error).lower()
    return "timed out" in text or "timeout" in text
