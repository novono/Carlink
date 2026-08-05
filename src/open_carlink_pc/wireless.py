from __future__ import annotations

import asyncio
import ctypes
import json
import secrets
import socket
import threading
import winreg
from collections.abc import Callable
from ctypes import wintypes
from dataclasses import dataclass
from uuid import UUID

import psutil
import usb.core
from winrt.windows.devices.bluetooth import BluetoothAdapter, BluetoothError
from winrt.windows.devices.bluetooth.genericattributeprofile import (
    GattCharacteristicProperties,
    GattLocalCharacteristic,
    GattLocalCharacteristicParameters,
    GattProtectionLevel,
    GattServiceProvider,
    GattServiceProviderAdvertisementStatus,
    GattServiceProviderAdvertisingParameters,
)
from winrt.windows.devices.radios import Radio, RadioAccessStatus, RadioState
from winrt.windows.devices.wifidirect import (
    WiFiDirectAdvertisementPublisher,
    WiFiDirectAdvertisementPublisherStatus,
    WiFiDirectConnectionListener,
    WiFiDirectDevice,
)
from winrt.windows.networking.connectivity import NetworkInformation
from winrt.windows.networking.networkoperators import (
    NetworkOperatorTetheringManager,
    TetheringOperationStatus,
    TetheringOperationalState,
    TetheringWiFiBand,
)
from winrt.windows.storage.streams import DataWriter

from .config import CarIdentity
from .raw_ble import (
    ATT_CID,
    LE_SIGNALING_CID,
    IccoaGattServer,
    RawBleError,
    RawHciController,
    le_connection_parameter_response,
)


ICCOA_ADVERTISEMENT_UUID = UUID("0000fcfb-0000-1000-8000-00805f9b34fb")
ICCOA_PRIMARY_DATA_UUID = UUID("00000001-0000-1000-8000-00805f9b34fb")
ICCOA_CAR_NAME_DATA_UUID = UUID("00000002-0000-1000-8000-00805f9b34fb")
ICCOA_SHARE_SERVICE_UUID = UUID("2abcc850-9935-4f8a-ba84-123456789100")
ICCOA_CLIENT_INFO_UUID = UUID("2abcc850-9935-4f8a-ba84-123456789101")
ICCOA_SERVER_INFO_UUID = UUID("2abcc850-9935-4f8a-ba84-123456789102")

WIRELESS_TYPE_WIFI_DIRECT = 1001
WIRELESS_TYPE_SOFT_AP = 1002
WIRELESS_TYPE_BOTH = 1003

_NETWORK_CLASS_KEY = (
    r"SYSTEM\CurrentControlSet\Control\Network\{4D36E972-E325-11CE-BFC1-08002BE10318}"
)


class WirelessError(RuntimeError):
    pass


class _WindowsGuid(ctypes.Structure):
    _fields_ = [
        ("data1", wintypes.DWORD),
        ("data2", wintypes.WORD),
        ("data3", wintypes.WORD),
        ("data4", ctypes.c_ubyte * 8),
    ]


def _wifi_channel_to_frequency(channel: int) -> int:
    if channel == 14:
        return 2484
    if 1 <= channel <= 13:
        return 2407 + channel * 5
    if 32 <= channel <= 177:
        return 5000 + channel * 5
    if channel >= 1:
        return 5950 + channel * 5
    return 0


def _interface_guid(interface_name: str) -> UUID | None:
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, _NETWORK_CLASS_KEY) as root:
            child_count = winreg.QueryInfoKey(root)[0]
            for index in range(child_count):
                child_name = winreg.EnumKey(root, index)
                try:
                    with winreg.OpenKey(root, child_name + r"\Connection") as connection:
                        name = str(winreg.QueryValueEx(connection, "Name")[0])
                except OSError:
                    continue
                if name == interface_name:
                    return UUID(child_name.strip("{}"))
    except OSError:
        return None
    return None


def _wlan_interface_frequency(interface_name: str) -> int:
    interface_guid = _interface_guid(interface_name)
    if interface_guid is None:
        return 0
    guid = _WindowsGuid.from_buffer_copy(interface_guid.bytes_le)
    wlan = ctypes.WinDLL("wlanapi.dll")
    handle = wintypes.HANDLE()
    negotiated = wintypes.DWORD()
    result = wlan.WlanOpenHandle(2, None, ctypes.byref(negotiated), ctypes.byref(handle))
    if result:
        return 0
    try:
        data_size = wintypes.DWORD()
        data = ctypes.c_void_p()
        value_type = wintypes.DWORD()
        result = wlan.WlanQueryInterface(
            handle,
            ctypes.byref(guid),
            8,  # wlan_intf_opcode_channel_number
            None,
            ctypes.byref(data_size),
            ctypes.byref(data),
            ctypes.byref(value_type),
        )
        if result or data.value is None or data_size.value < 4:
            return 0
        try:
            channel = ctypes.cast(data, ctypes.POINTER(wintypes.DWORD)).contents.value
            return _wifi_channel_to_frequency(channel)
        finally:
            wlan.WlanFreeMemory(data)
    finally:
        wlan.WlanCloseHandle(handle, None)


@dataclass(frozen=True, slots=True)
class HotspotInfo:
    ssid: str
    passphrase: str
    address: str
    mac_address: str
    frequency: int = 2412
    connection_type: int = WIRELESS_TYPE_SOFT_AP


@dataclass(frozen=True, slots=True)
class PhoneBleInfo:
    device_id: str
    name: str
    model: str
    band: int
    mac_address: str
    pin_code: str
    requested_type: int
    channel: int


def _buffer(data: bytes):
    writer = DataWriter()
    writer.write_bytes(data)
    return writer.detach_buffer()


def build_primary_service_data(identity: CarIdentity, *, serial: int = 1) -> bytes:
    identity.validate()
    major, minor = identity.protocol_version.split(".")[:2]
    vendor_data = bytes.fromhex(identity.vendor_data.ljust(4, "0")[:4])
    result = b"".join(
        (
            bytes((int(major) & 0xFF, int(minor) & 0xFF, serial & 0xFF)),
            bytes.fromhex(identity.car_id),
            bytes.fromhex(identity.model_id),
            vendor_data,
        )
    )
    if len(result) != 15:
        raise ValueError(f"ICCOA 主广播数据必须为 15 字节，实际为 {len(result)}")
    return result


def build_car_name_service_data(car_name: str) -> bytes:
    """Encode ICCOA's fixed-width scan-response name field."""
    encoded = car_name.encode("utf-8")
    if len(encoded) > 16:
        shortened = car_name
        while shortened and len((shortened + "…").encode("utf-8")) > 16:
            shortened = shortened[:-1]
        encoded = (shortened + "…").encode("utf-8")
    return encoded.ljust(16, b"\0")


def parse_phone_ble_info(data: bytes) -> PhoneBleInfo:
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("手机 BLE 信息不是有效 JSON") from exc
    if not isinstance(value, dict):
        raise ValueError("手机 BLE 信息必须是 JSON 对象")
    return PhoneBleInfo(
        device_id=str(value.get("id", "")),
        name=str(value.get("name", "")),
        model=str(value.get("model", "")),
        band=int(value.get("band", 0)),
        mac_address=str(value.get("mac", "")),
        pin_code=str(value.get("pinCodeOrAuthentication", "")),
        requested_type=int(value.get("type", WIRELESS_TYPE_WIFI_DIRECT)),
        channel=int(value.get("channel", 0)),
    )


def build_server_info(hotspot: HotspotInfo, car_name: str) -> bytes:
    return json.dumps(
        {
            "name": car_name,
            "ssid": hotspot.ssid,
            "psk": hotspot.passphrase,
            "mac": hotspot.mac_address,
            "freq": hotspot.frequency,
            "port": 0,
            "type": hotspot.connection_type,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")


def phone_address(connection_info: str | None, peer_address: str) -> str:
    candidate = (connection_info or "").strip()
    if candidate.startswith("[") and "]" in candidate:
        candidate = candidate[1 : candidate.index("]")]
    elif candidate.count(":") == 1:
        host, possible_port = candidate.rsplit(":", 1)
        if possible_port.isdigit():
            candidate = host
    try:
        socket.inet_pton(socket.AF_INET, candidate)
        return candidate
    except OSError:
        return peer_address


class WindowsMobileHotspot:
    def __init__(self, log: Callable[[str], None]) -> None:
        self.log = log
        self._manager: NetworkOperatorTetheringManager | None = None
        self._started_here = False

    @staticmethod
    def _interface_details() -> tuple[str, str, str]:
        fallback_address = "192.168.137.1"
        fallback_mac = "02:00:00:00:00:00"
        candidates: list[tuple[str, str, str]] = []
        for interface_name, addresses in psutil.net_if_addrs().items():
            ipv4 = next(
                (
                    item.address
                    for item in addresses
                    if item.family == socket.AF_INET and item.address != "127.0.0.1"
                ),
                "",
            )
            mac = next(
                (
                    item.address.replace("-", ":").lower()
                    for item in addresses
                    if item.family == psutil.AF_LINK and item.address
                ),
                "",
            )
            if ipv4:
                candidates.append((interface_name, ipv4, mac))
        preferred = next((item for item in candidates if item[1] == fallback_address), None)
        if preferred is None:
            preferred = next(
                (
                    item
                    for item in candidates
                    if item[1].startswith(("192.168.", "172.16.", "172.17."))
                ),
                None,
            )
        if preferred is None:
            return "", fallback_address, fallback_mac
        return preferred[0], preferred[1], preferred[2] or fallback_mac

    @staticmethod
    def _interface_info() -> tuple[str, str]:
        _name, address, mac_address = WindowsMobileHotspot._interface_details()
        return address, mac_address

    async def start(self) -> HotspotInfo:
        profile = NetworkInformation.get_internet_connection_profile()
        if profile is None:
            raise WirelessError("电脑当前没有可共享的网络连接")
        manager = NetworkOperatorTetheringManager.create_from_connection_profile(profile)
        self._manager = manager
        configuration = manager.get_current_access_point_configuration()
        try:
            if configuration.is_band_supported(TetheringWiFiBand.FIVE_GIGAHERTZ):
                configuration.band = TetheringWiFiBand.FIVE_GIGAHERTZ
                await manager.configure_access_point_async(configuration)
        except OSError as exc:
            self.log(f"热点频段保持系统设置：{exc}")

        if manager.tethering_operational_state != TetheringOperationalState.ON:
            result = await manager.start_tethering_async()
            if result.status != TetheringOperationStatus.SUCCESS:
                detail = result.additional_error_message or str(result.status)
                raise WirelessError(f"Windows 移动热点启动失败：{detail}")
            self._started_here = True

        for _ in range(40):
            if manager.tethering_operational_state == TetheringOperationalState.ON:
                interface_name, address, _mac_address = self._interface_details()
                if address:
                    current = manager.get_current_access_point_configuration()
                    frequency = _wlan_interface_frequency(interface_name) or 2412
                    channel = 14 if frequency == 2484 else (frequency - 2407) // 5
                    if frequency >= 5000:
                        channel = (frequency - 5000) // 5
                    self.log(f"Windows 移动热点使用信道 {channel}（{frequency} MHz）")
                    return HotspotInfo(
                        ssid=current.ssid,
                        passphrase=current.passphrase,
                        address=address,
                        # The reference vehicle SDK sends the privacy placeholder
                        # unless both peers explicitly negotiate real-MAC support.
                        mac_address="02:00:00:00:00:00",
                        frequency=frequency,
                        connection_type=WIRELESS_TYPE_SOFT_AP,
                    )
            await asyncio.sleep(0.25)
        raise WirelessError("Windows 移动热点启动超时")

    async def stop(self) -> None:
        manager = self._manager
        self._manager = None
        if manager is not None and self._started_here:
            self._started_here = False
            try:
                await manager.stop_tethering_async()
            except OSError:
                pass


class WindowsWiFiDirectGroupOwner:
    """Publish an autonomous Wi-Fi Direct GO with legacy-client credentials.

    ColorOS ICCOA uses WifiP2pManager to join the car.  A Windows Mobile Hotspot
    looks similar in Settings, but does not advertise the P2P Group Owner
    information elements that the phone requires.
    """

    def __init__(self, log: Callable[[str], None]) -> None:
        self.log = log
        self._publisher: WiFiDirectAdvertisementPublisher | None = None
        self._status_token: object | None = None
        self._status_event = threading.Event()
        self._last_status = WiFiDirectAdvertisementPublisherStatus.CREATED
        self._last_error: object | None = None
        self._listener: WiFiDirectConnectionListener | None = None
        self._listener_token: object | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._devices: list[WiFiDirectDevice] = []
        self._accept_tasks: set[asyncio.Task[None]] = set()

    async def _enable_wifi_radio(self) -> None:
        access = await Radio.request_access_async()
        if access != RadioAccessStatus.ALLOWED:
            raise WirelessError(f"Windows 未授权控制 Wi-Fi 无线电：{access}")
        radios = await Radio.get_radios_async()
        wifi_radios = [radio for radio in radios if radio.kind.value == 1]
        if not wifi_radios:
            raise WirelessError("没有找到可用的 Windows Wi-Fi 无线电")
        for radio in wifi_radios:
            if radio.state != RadioState.ON:
                result = await radio.set_state_async(RadioState.ON)
                if result != RadioAccessStatus.ALLOWED:
                    raise WirelessError(f"无法开启 Windows Wi-Fi 无线电：{result}")

    def _on_status_changed(self, _sender: object, event: object) -> None:
        self._last_status = getattr(event, "status", self._last_status)
        self._last_error = getattr(event, "error", None)
        self._status_event.set()

    async def _accept_connection(self, request: object) -> None:
        try:
            device_information = getattr(request, "device_information")
            device_id = str(device_information.id)
            device = await WiFiDirectDevice.from_id_async(device_id)
            self._devices.append(device)
            self.log("Windows 已接受 OPPO 的 Wi-Fi Direct 连接请求")
        except (OSError, ValueError) as exc:
            self.log(f"接受 Wi-Fi Direct 连接请求失败：{exc}")
        finally:
            try:
                getattr(request, "close")()
            except OSError:
                pass

    def _on_connection_requested(self, _sender: object, event: object) -> None:
        try:
            request = getattr(event, "get_connection_request")()
        except OSError as exc:
            self.log(f"读取 Wi-Fi Direct 连接请求失败：{exc}")
            return
        loop = self._loop
        if loop is None:
            try:
                getattr(request, "close")()
            except OSError:
                pass
            return

        def schedule() -> None:
            task = loop.create_task(self._accept_connection(request))
            self._accept_tasks.add(task)
            task.add_done_callback(self._accept_tasks.discard)

        try:
            loop.call_soon_threadsafe(schedule)
        except RuntimeError:
            try:
                getattr(request, "close")()
            except OSError:
                pass

    async def start(self) -> HotspotInfo:
        await self._enable_wifi_radio()

        self._loop = asyncio.get_running_loop()
        listener = WiFiDirectConnectionListener()
        self._listener = listener
        self._listener_token = listener.add_connection_requested(self._on_connection_requested)

        publisher = WiFiDirectAdvertisementPublisher()
        advertisement = publisher.advertisement
        advertisement.is_autonomous_group_owner_enabled = True
        legacy = advertisement.legacy_settings
        legacy.is_enabled = True
        legacy.ssid = "XHCODING 1868"
        legacy.passphrase.password = "cl" + secrets.token_hex(6)

        self._publisher = publisher
        self._status_event.clear()
        self._last_status = WiFiDirectAdvertisementPublisherStatus.CREATED
        self._last_error = None
        self._status_token = publisher.add_status_changed(self._on_status_changed)
        publisher.start()

        for _ in range(80):
            status = publisher.status
            if status == WiFiDirectAdvertisementPublisherStatus.STARTED:
                break
            if status == WiFiDirectAdvertisementPublisherStatus.ABORTED:
                error = self._last_error
                await self.stop()
                raise WirelessError(
                    f"Windows Wi-Fi Direct GO 启动失败：{error}；请先关闭系统移动热点"
                )
            await asyncio.sleep(0.1)
        else:
            await self.stop()
            raise WirelessError("Windows Wi-Fi Direct GO 启动超时")

        for _ in range(60):
            interface_name, address, mac_address = WindowsMobileHotspot._interface_details()
            if address == "192.168.137.1" and mac_address != "02:00:00:00:00:00":
                frequency = _wlan_interface_frequency(interface_name) or 2412
                channel = 14 if frequency == 2484 else (frequency - 2407) // 5
                if frequency >= 5000:
                    channel = (frequency - 5000) // 5
                self.log(f"Wi-Fi Direct GO 使用信道 {channel}（{frequency} MHz）")
                return HotspotInfo(
                    ssid=legacy.ssid,
                    passphrase=legacy.passphrase.password,
                    address=address,
                    mac_address=mac_address,
                    frequency=frequency,
                    connection_type=WIRELESS_TYPE_WIFI_DIRECT,
                )
            await asyncio.sleep(0.1)
        await self.stop()
        raise WirelessError("Wi-Fi Direct GO 已启动，但没有获得车机端 IPv4 地址")

    async def stop(self) -> None:
        publisher = self._publisher
        self._publisher = None
        if publisher is not None:
            try:
                if publisher.status in {
                    WiFiDirectAdvertisementPublisherStatus.STARTED,
                    WiFiDirectAdvertisementPublisherStatus.CREATED,
                }:
                    publisher.stop()
            except OSError:
                pass
            token = self._status_token
            self._status_token = None
            if token is not None:
                try:
                    publisher.remove_status_changed(token)
                except OSError:
                    pass

        listener = self._listener
        self._listener = None
        listener_token = self._listener_token
        self._listener_token = None
        if listener is not None and listener_token is not None:
            try:
                listener.remove_connection_requested(listener_token)
            except OSError:
                pass

        for task in list(self._accept_tasks):
            task.cancel()
        self._accept_tasks.clear()
        for device in self._devices:
            try:
                device.close()
            except OSError:
                pass
        self._devices.clear()
        self._loop = None


class IccoaBlePeripheral:
    def __init__(
        self,
        identity: CarIdentity,
        hotspot: HotspotInfo,
        log: Callable[[str], None],
        phone_info: Callable[[PhoneBleInfo], None],
    ) -> None:
        self.identity = identity
        self.hotspot = hotspot
        self.log = log
        self.phone_info = phone_info
        self._share_provider: GattServiceProvider | None = None
        self._advertisement_provider: GattServiceProvider | None = None
        self._primary_data_provider: GattServiceProvider | None = None
        self._car_name_data_provider: GattServiceProvider | None = None
        self._client_characteristic: GattLocalCharacteristic | None = None
        self._server_characteristic: GattLocalCharacteristic | None = None
        self._event_tokens: list[tuple[object, str, object]] = []
        self._loop: asyncio.AbstractEventLoop | None = None
        self._pending_server_info = False

    async def _ensure_bluetooth(self) -> None:
        access = await Radio.request_access_async()
        if access != RadioAccessStatus.ALLOWED:
            raise WirelessError("Windows 未授权程序使用蓝牙")
        adapter = await BluetoothAdapter.get_default_async()
        if adapter is None or not adapter.is_low_energy_supported:
            raise WirelessError("电脑没有可用的低功耗蓝牙适配器")
        if not adapter.is_peripheral_role_supported:
            raise WirelessError("蓝牙适配器不支持 BLE 外设模式")
        radio = await adapter.get_radio_async()
        if radio.state != RadioState.ON:
            result = await radio.set_state_async(RadioState.ON)
            if result != RadioAccessStatus.ALLOWED:
                raise WirelessError("无法开启电脑蓝牙，请在 Windows 设置中手动开启")

    @staticmethod
    async def _create_provider(service_uuid: UUID) -> GattServiceProvider:
        result = await GattServiceProvider.create_async(service_uuid)
        if result.error != BluetoothError.SUCCESS or result.service_provider is None:
            raise WirelessError(f"创建 BLE GATT 服务失败：{result.error}")
        return result.service_provider

    async def start(self) -> None:
        await self._ensure_bluetooth()
        self._loop = asyncio.get_running_loop()

        share = await self._create_provider(ICCOA_SHARE_SERVICE_UUID)
        client_parameters = GattLocalCharacteristicParameters()
        client_parameters.characteristic_properties = GattCharacteristicProperties.WRITE
        client_parameters.write_protection_level = GattProtectionLevel.PLAIN
        client_parameters.user_description = "ICCOA client info"
        client_result = await share.service.create_characteristic_async(
            ICCOA_CLIENT_INFO_UUID,
            client_parameters,
        )
        if client_result.error != BluetoothError.SUCCESS or client_result.characteristic is None:
            raise WirelessError(f"创建 BLE 客户端信息特征失败：{client_result.error}")

        server_parameters = GattLocalCharacteristicParameters()
        server_parameters.characteristic_properties = GattCharacteristicProperties.NOTIFY
        server_parameters.read_protection_level = GattProtectionLevel.PLAIN
        server_parameters.user_description = "ICCOA server connection info"
        server_result = await share.service.create_characteristic_async(
            ICCOA_SERVER_INFO_UUID,
            server_parameters,
        )
        if server_result.error != BluetoothError.SUCCESS or server_result.characteristic is None:
            raise WirelessError(f"创建 BLE 车机信息特征失败：{server_result.error}")

        self._share_provider = share
        self._client_characteristic = client_result.characteristic
        self._server_characteristic = server_result.characteristic
        write_token = self._client_characteristic.add_write_requested(self._on_write_requested)
        subscribed_token = self._server_characteristic.add_subscribed_clients_changed(
            self._on_subscribed_clients_changed
        )
        self._event_tokens.extend(
            (
                (self._client_characteristic, "write", write_token),
                (self._server_characteristic, "subscribed", subscribed_token),
            )
        )

        advertisement = await self._create_provider(ICCOA_ADVERTISEMENT_UUID)
        primary_data = await self._create_provider(ICCOA_PRIMARY_DATA_UUID)
        car_name_data = await self._create_provider(ICCOA_CAR_NAME_DATA_UUID)
        self._advertisement_provider = advertisement
        self._primary_data_provider = primary_data
        self._car_name_data_provider = car_name_data

        advertisement_parameters = GattServiceProviderAdvertisingParameters()
        advertisement_parameters.is_connectable = True
        advertisement_parameters.is_discoverable = True
        advertisement.start_advertising_with_parameters(advertisement_parameters)
        await asyncio.sleep(0.25)

        primary_parameters = GattServiceProviderAdvertisingParameters()
        primary_parameters.is_connectable = True
        primary_parameters.is_discoverable = True
        primary_parameters.service_data = _buffer(
            build_primary_service_data(self.identity, serial=secrets.randbelow(256))
        )
        primary_data.start_advertising_with_parameters(primary_parameters)
        await asyncio.sleep(0.25)

        # ICCOA puts the fixed 16-byte car name under UUID 0002 in the scan
        # response. Windows assigns the overflow service-data field to its
        # scan-response payload when all providers share the adapter.
        car_name_parameters = GattServiceProviderAdvertisingParameters()
        car_name_parameters.is_connectable = True
        car_name_parameters.is_discoverable = True
        car_name_parameters.service_data = _buffer(
            build_car_name_service_data(self.identity.short_name)
        )
        car_name_data.start_advertising_with_parameters(car_name_parameters)
        await asyncio.sleep(0.75)

        valid_statuses = {
            GattServiceProviderAdvertisementStatus.STARTED,
            GattServiceProviderAdvertisementStatus.STARTED_WITHOUT_ALL_ADVERTISEMENT_DATA,
        }
        if advertisement.advertisement_status not in valid_statuses:
            raise WirelessError(
                f"ICCOA BLE 广播启动失败：{advertisement.advertisement_status}"
            )
        if primary_data.advertisement_status not in valid_statuses:
            raise WirelessError(
                f"ICCOA BLE 设备数据广播启动失败：{primary_data.advertisement_status}"
            )
        if car_name_data.advertisement_status not in valid_statuses:
            raise WirelessError(
                f"ICCOA BLE 车机名称广播启动失败：{car_name_data.advertisement_status}"
            )
        self.log(
            "ICCOA BLE 车机广播已启动（FCFB + INFO1 + INFO2）："
            f"{advertisement.advertisement_status.name}/"
            f"{primary_data.advertisement_status.name}/"
            f"{car_name_data.advertisement_status.name}"
        )

    def _on_write_requested(self, _sender, args) -> None:
        deferral = args.get_deferral()
        loop = self._loop
        if loop is None:
            deferral.complete()
            return

        async def process() -> None:
            try:
                request = await args.get_request_async()
                if request is None:
                    return
                raw = bytes(request.value)
                if request.offset:
                    self.log(f"收到不支持的 BLE 分段写入，offset={request.offset}")
                    request.respond_with_protocol_error(0x07)
                    return
                info = parse_phone_ble_info(raw)
                request.respond()
                self.phone_info(info)
                self._pending_server_info = True
                await self.notify_server_info()
            except (OSError, ValueError) as exc:
                self.log(f"解析手机 BLE 信息失败：{exc}")
            finally:
                deferral.complete()

        loop.call_soon_threadsafe(lambda: asyncio.create_task(process()))

    def _on_subscribed_clients_changed(self, _sender, _args) -> None:
        loop = self._loop
        if loop is not None and self._pending_server_info:
            loop.call_soon_threadsafe(lambda: asyncio.create_task(self.notify_server_info()))

    async def notify_server_info(self) -> None:
        characteristic = self._server_characteristic
        if characteristic is None or not characteristic.subscribed_clients:
            return
        payload = build_server_info(self.hotspot, self.identity.short_name)
        max_size = min(client.max_notification_size for client in characteristic.subscribed_clients)
        if len(payload) > max_size:
            raise WirelessError(
                f"手机协商的 BLE MTU 太小：通知上限 {max_size}，需要 {len(payload)}"
            )
        results = await characteristic.notify_value_async(_buffer(payload))
        if not results:
            return
        self._pending_server_info = False
        self.log(f"已通过 BLE 下发电脑热点 {self.hotspot.ssid}")

    async def stop(self) -> None:
        for target, event_name, token in self._event_tokens:
            try:
                if event_name == "write":
                    target.remove_write_requested(token)
                else:
                    target.remove_subscribed_clients_changed(token)
            except OSError:
                pass
        self._event_tokens.clear()
        for provider in (
            self._car_name_data_provider,
            self._primary_data_provider,
            self._advertisement_provider,
        ):
            if provider is not None:
                try:
                    provider.stop_advertising()
                except OSError:
                    pass
        self._primary_data_provider = None
        self._car_name_data_provider = None
        self._advertisement_provider = None
        self._share_provider = None
        self._client_characteristic = None
        self._server_characteristic = None
        self._loop = None


class IccoaRawBlePeripheral:
    """ICCOA peripheral backed by direct QCA9377 USB HCI instead of WinRT."""

    def __init__(
        self,
        identity: CarIdentity,
        hotspot: HotspotInfo,
        log: Callable[[str], None],
        phone_info: Callable[[PhoneBleInfo], None],
    ) -> None:
        self.identity = identity
        self.hotspot = hotspot
        self.log = log
        self.phone_info = phone_info
        self._stop = threading.Event()
        self._ready = threading.Event()
        self._thread: threading.Thread | None = None
        self._startup_error: BaseException | None = None

    async def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop.clear()
        self._ready.clear()
        self._startup_error = None
        self._thread = threading.Thread(
            target=self._run,
            name="CarLinkRawBleHci",
            daemon=True,
        )
        self._thread.start()
        started = await asyncio.to_thread(self._ready.wait, 8.0)
        if not started:
            raise WirelessError("等待 QCA9377 原始 HCI 广播启动超时")
        if self._startup_error is not None:
            raise WirelessError(str(self._startup_error)) from self._startup_error

    def _handle_signaling(self, controller: RawHciController, handle: int, payload: bytes) -> None:
        offset = 0
        while offset + 4 <= len(payload):
            code = payload[offset]
            identifier = payload[offset + 1]
            length = int.from_bytes(payload[offset + 2 : offset + 4], "little")
            body_end = offset + 4 + length
            if body_end > len(payload):
                return
            if code == 0x12:
                controller.send_l2cap(
                    handle,
                    LE_SIGNALING_CID,
                    le_connection_parameter_response(identifier),
                )
            offset = body_end

    def _handle_att(
        self,
        controller: RawHciController,
        gatt: IccoaGattServer,
        handle: int,
        payload: bytes,
    ) -> None:
        result = gatt.handle(payload)
        if result.response is not None:
            controller.send_l2cap(handle, ATT_CID, result.response)
        if result.client_info is None:
            return
        try:
            info = parse_phone_ble_info(result.client_info)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError) as exc:
            self.log(f"解析手机 BLE 信息失败：{exc}")
            return
        self.phone_info(info)
        server_info = build_server_info(self.hotspot, self.identity.short_name)
        if len(server_info) > gatt.negotiated_mtu - 3:
            raise RawBleError(
                f"手机协商的 BLE MTU 太小：可用 {gatt.negotiated_mtu - 3}，需要 {len(server_info)}"
            )
        controller.send_l2cap(handle, ATT_CID, gatt.server_info_indication(server_info))
        self.log(f"已通过原始 BLE GATT 下发电脑热点 {self.hotspot.ssid}")

    def _run(self) -> None:
        controller = RawHciController()
        primary_data = build_primary_service_data(
            self.identity,
            serial=secrets.randbelow(256),
        )
        car_name_data = build_car_name_service_data(self.identity.short_name)
        gatt = IccoaGattServer(self.identity.short_name)
        try:
            controller.open()
            controller.initialize()
            controller.start_iccoa_advertising(primary_data, car_name_data)
            self.log("ICCOA 原始 BLE 广播已启动（FCFB + INFO1 + INFO2）")
            self._ready.set()

            while not self._stop.is_set():
                event = controller.read_event(timeout_ms=20)
                if event is not None:
                    parsed = controller.process_event(event)
                    if parsed is not None and parsed[0] == "connected":
                        self.log(f"OPPO 已连接 BLE GATT，handle={parsed[1]}")
                    elif parsed is not None and parsed[0] == "disconnected":
                        disconnected_handle, reason = parsed[1]
                        self.log(
                            f"BLE GATT 已断开，handle={disconnected_handle} reason=0x{reason:02X}"
                        )
                        gatt = IccoaGattServer(self.identity.short_name)
                        if not self._stop.is_set():
                            controller.start_iccoa_advertising(primary_data, car_name_data)

                acl = controller.read_acl(timeout_ms=20)
                if acl is None:
                    continue
                for handle, cid, payload in controller.process_acl(acl):
                    if cid == ATT_CID:
                        self._handle_att(controller, gatt, handle, payload)
                    elif cid == LE_SIGNALING_CID:
                        self._handle_signaling(controller, handle, payload)
        except (OSError, ValueError, RawBleError, usb.core.USBError) as exc:
            if not self._ready.is_set():
                self._startup_error = exc
                self._ready.set()
            elif not self._stop.is_set():
                self.log(f"原始 BLE HCI 异常：{exc}")
        finally:
            if not self._ready.is_set():
                self._ready.set()
            try:
                controller.command(0x0C03)
            except (OSError, RawBleError, usb.core.USBError):
                pass
            controller.close()

    async def stop(self) -> None:
        self._stop.set()
        thread = self._thread
        self._thread = None
        if thread is not None:
            await asyncio.to_thread(thread.join, 3.0)
