from __future__ import annotations

import asyncio
import secrets
import socket
import threading
import time
from dataclasses import replace
from pathlib import Path
from typing import Callable, Literal

from PIL import Image

from .auth import AuthSession, decrypt_session_message, encrypt_session_message
from .capture import CaptureWriter
from .config import CarIdentity, app_data_dir
from .rtsp import RtspSinkHandshake
from .ucar_protocol import UCarStreamReassembler, build_get_config_response, build_heartbeat
from .uibc import build_key_event, build_touch_event
from .video import RtpMpegTsExtractor, VideoDecoder
from .wireless import (
    IccoaBlePeripheral,
    IccoaRawBlePeripheral,
    PhoneBleInfo,
    WindowsMobileHotspot,
    WindowsWiFiDirectGroupOwner,
    WIRELESS_TYPE_SOFT_AP,
    WirelessError,
    phone_address,
)


AUTH_PORT = 57209
CONTROL_PORT = 57219
MEDIA_PORT = 57229
SENSOR_PORT = 57239
CERT_PORT = 57249
RTSP_PORT = 7236
RTP_PORT = 15550
UIBC_PORT = 4321
NetworkMode = Literal["softap", "wfd", "wfd-softap"]
NETWORK_MODES = frozenset(("softap", "wfd", "wfd-softap"))


class WirelessCarLinkController:
    def __init__(
        self,
        identity: CarIdentity,
        log: Callable[[str], None],
        state: Callable[[str], None],
        video: Callable[[Image.Image], None] | None = None,
        network_mode: NetworkMode = "softap",
    ) -> None:
        if network_mode not in NETWORK_MODES:
            raise ValueError(f"不支持的无线网络模式：{network_mode}")
        self.identity = identity
        self.log = log
        self.state = state
        self.video = video
        self.network_mode = network_mode
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._sockets: set[socket.socket] = set()
        self._sockets_lock = threading.Lock()
        self._uibc_socket: socket.socket | None = None
        self._uibc_lock = threading.Lock()
        self._channel_threads: list[threading.Thread] = []
        self._channel_started = False
        self._channel_lock = threading.Lock()
        self._auth_session: AuthSession | None = None
        self._wireless_pin = ""
        self._pin_lock = threading.Lock()
        self._capture: CaptureWriter | None = None
        self.capture_path: Path | None = None

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self, full_payload: bool = False) -> None:
        if self.running:
            return
        self._stop.clear()
        self._channel_started = False
        self._thread = threading.Thread(
            target=self._run,
            args=(full_payload,),
            name="CarLinkWirelessWorker",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._close_all_sockets()

    def _register_socket(self, value: socket.socket) -> socket.socket:
        with self._sockets_lock:
            self._sockets.add(value)
        return value

    def _forget_socket(self, value: socket.socket) -> None:
        with self._sockets_lock:
            self._sockets.discard(value)

    def _close_socket(self, value: socket.socket | None) -> None:
        if value is None:
            return
        self._forget_socket(value)
        try:
            value.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            value.close()
        except OSError:
            pass

    def _close_all_sockets(self) -> None:
        with self._sockets_lock:
            values = list(self._sockets)
            self._sockets.clear()
        for value in values:
            try:
                value.close()
            except OSError:
                pass
        with self._uibc_lock:
            self._uibc_socket = None

    def _set_pin(self, value: str) -> None:
        with self._pin_lock:
            self._wireless_pin = value

    def _get_pin(self) -> str:
        with self._pin_lock:
            return self._wireless_pin

    def _new_pin(self) -> str:
        value = f"{secrets.randbelow(1_000_000):06d}"
        self._set_pin(value)
        return value

    def _send_uibc(self, payload: bytes) -> bool:
        with self._uibc_lock:
            connection = self._uibc_socket
            if connection is None:
                return False
            try:
                connection.sendall(payload)
                capture = self._capture
                if capture is not None:
                    capture.frame("pc_to_phone", 5, payload)
                return True
            except OSError:
                self._uibc_socket = None
                return False

    def send_touch(self, action: int, x: int, y: int) -> bool:
        try:
            return self._send_uibc(build_touch_event(action, x, y))
        except ValueError:
            return False

    def send_key(self, key_code: int) -> bool:
        return self._send_uibc(build_key_event(key_code))

    def _run(self, full_payload: bool) -> None:
        stopped_by_user = False
        capture = CaptureWriter(app_data_dir() / "captures", full_payload=full_payload)
        self._capture = capture
        self.capture_path = capture.path
        capture.event(
            "wireless_session_start",
            car_id_hash=self.identity.car_id[-4:].rjust(12, "*"),
            model_id=self.identity.model_id,
            protocol_version=self.identity.protocol_version,
            full_payload=full_payload,
        )
        try:
            asyncio.run(self._run_async(capture))
        except (OSError, WirelessError, ValueError) as exc:
            if not self._stop.is_set():
                self.log(f"错误：{exc}")
                capture.event("error", message=str(exc))
                self.state("连接失败")
        except Exception as exc:  # noqa: BLE001 - diagnostic boundary
            if not self._stop.is_set():
                self.log(f"未处理错误：{type(exc).__name__}: {exc}")
                capture.event("error", message=f"{type(exc).__name__}: {exc}")
                self.state("连接失败")
        finally:
            stopped_by_user = self._stop.is_set()
            self._stop.set()
            self._close_all_sockets()
            for thread in self._channel_threads:
                thread.join(timeout=0.5)
            self._channel_threads.clear()
            self._auth_session = None
            self._capture = None
            capture.event("wireless_session_end")
            if stopped_by_user:
                self.state("已停止")

    async def _run_async(self, capture: CaptureWriter) -> None:
        if self.network_mode == "softap":
            hotspot = WindowsMobileHotspot(self.log)
        else:
            hotspot = WindowsWiFiDirectGroupOwner(self.log)
        ble: IccoaBlePeripheral | IccoaRawBlePeripheral | None = None
        auth_server: asyncio.Server | None = None
        pin_task: asyncio.Task[None] | None = None
        video_decoder: VideoDecoder | None = None
        try:
            self.state("正在启动 Windows 移动热点…")
            hotspot_info = await hotspot.start()
            if self.network_mode == "wfd-softap":
                hotspot_info = replace(
                    hotspot_info,
                    connection_type=WIRELESS_TYPE_SOFT_AP,
                )
                self.log("诊断模式：使用 Wi-Fi Direct GO，但向手机声明无线类型 1002")
            self.log(
                f"Windows 热点已启动：{hotspot_info.ssid}，车机地址 {hotspot_info.address}"
            )
            capture.event(
                "hotspot_started",
                ssid=hotspot_info.ssid,
                address=hotspot_info.address,
                mac=hotspot_info.mac_address,
                frequency=hotspot_info.frequency,
            )

            if self.video is not None:
                video_decoder = VideoDecoder(self.video, self.log)
                video_decoder.start()

            initial_pin = self._new_pin()
            auth_server = await asyncio.start_server(
                lambda reader, writer: self._handle_auth_client(
                    reader,
                    writer,
                    capture,
                    video_decoder,
                ),
                host="0.0.0.0",
                port=AUTH_PORT,
                reuse_address=True,
            )
            self.log(f"无线认证服务已监听 TCP {AUTH_PORT}")

            ble = IccoaRawBlePeripheral(
                self.identity,
                hotspot_info,
                self.log,
                self._on_phone_ble_info,
            )
            self.state("正在启动 ICCOA 蓝牙广播…")
            await ble.start()
            self.state(f"等待 OPPO 手机 · 配对 PIN {initial_pin}")
            self.log("请打开手机的车载服务/Car+，搜索并选择 PC CarLink")
            pin_task = asyncio.create_task(self._rotate_pin())

            while not self._stop.is_set():
                await asyncio.sleep(0.2)
        finally:
            if pin_task is not None:
                pin_task.cancel()
                try:
                    await pin_task
                except asyncio.CancelledError:
                    pass
            if auth_server is not None:
                auth_server.close()
                await auth_server.wait_closed()
            if ble is not None:
                await ble.stop()
            await hotspot.stop()
            if video_decoder is not None:
                video_decoder.stop()

    async def _rotate_pin(self) -> None:
        elapsed = 0.0
        while not self._stop.is_set() and self._auth_session is None:
            await asyncio.sleep(1.0)
            elapsed += 1.0
            if elapsed < 120.0:
                continue
            elapsed = 0.0
            pin = self._new_pin()
            self.state(f"等待 OPPO 手机 · 配对 PIN {pin}")
            self.log("无线配对 PIN 已自动刷新")

    def _on_phone_ble_info(self, info: PhoneBleInfo) -> None:
        if info.pin_code:
            self._set_pin(info.pin_code)
        name = info.name or info.model or "OPPO 手机"
        self.log(
            f"BLE 已发现手机：{name}，请求无线类型 {info.requested_type}，首选信道 {info.channel or '自动'}"
        )
        self.state(f"手机已发现，正在连接热点 · 配对 PIN {self._get_pin()}")

    async def _handle_auth_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        capture: CaptureWriter,
        video_decoder: VideoDecoder | None,
    ) -> None:
        peer = writer.get_extra_info("peername")
        peer_ip = str(peer[0]) if isinstance(peer, tuple) and peer else ""
        if self._auth_session is not None and self._auth_session.confirmed:
            writer.close()
            await writer.wait_closed()
            return
        self.log(f"手机已连接无线认证通道：{peer_ip}")
        self.state(f"正在认证 OPPO 手机 · PIN {self._get_pin()}")
        auth = AuthSession(
            self.identity,
            app_data_dir() / "auth",
            pin_code=self._get_pin(),
        )
        reassembler = UCarStreamReassembler()
        try:
            while not self._stop.is_set():
                data = await reader.read(64 * 1024)
                if not data:
                    break
                capture.frame("phone_to_pc", 7, data)
                for message in reassembler.feed(data):
                    description, response = auth.handle(message)
                    self.log(description)
                    if response:
                        writer.write(response)
                        await writer.drain()
                        capture.frame("pc_to_phone", 7, response)
                    if auth.confirmed and auth.session_key is not None:
                        self._auth_session = auth
                        address = phone_address(auth.connection_info, peer_ip)
                        capture.event(
                            "wireless_auth_confirmed",
                            phone_address=address,
                            phone_id=auth.phone_id or "",
                        )
                        self.log(f"无线认证完成，手机地址 {address}")
                        self.state("认证成功，正在建立投屏通道…")
                        self._start_network_channels(
                            address,
                            auth.session_key,
                            capture,
                            video_decoder,
                        )
                        return
        except (ConnectionError, OSError, ValueError) as exc:
            if not self._stop.is_set():
                self.log(f"无线认证通道异常：{exc}")
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except OSError:
                pass

    def _start_thread(self, target: Callable[..., None], *args: object, name: str) -> None:
        thread = threading.Thread(target=target, args=args, name=name, daemon=True)
        self._channel_threads.append(thread)
        thread.start()

    def _start_network_channels(
        self,
        phone_ip: str,
        session_key: bytes,
        capture: CaptureWriter,
        video_decoder: VideoDecoder | None,
    ) -> None:
        with self._channel_lock:
            if self._channel_started:
                return
            self._channel_started = True
        rtp_ready = threading.Event()
        self._start_thread(
            self._rtp_worker,
            capture,
            video_decoder,
            rtp_ready,
            name="CarLinkRtpServer",
        )
        rtp_ready.wait(timeout=1.0)
        self._start_thread(
            self._control_worker,
            phone_ip,
            session_key,
            capture,
            name="CarLinkControl",
        )
        self._start_thread(
            self._rtsp_worker,
            phone_ip,
            capture,
            name="CarLinkRtsp",
        )
        self._start_thread(
            self._uibc_worker,
            phone_ip,
            name="CarLinkUibc",
        )
        for label, port, channel_id in (
            ("MEDIA", MEDIA_PORT, 6),
            ("SENSOR", SENSOR_PORT, 8),
            ("CERT", CERT_PORT, 9),
        ):
            self._start_thread(
                self._drain_channel_worker,
                phone_ip,
                label,
                port,
                channel_id,
                capture,
                name=f"CarLink{label.title()}",
            )

    def _connect_with_retry(self, phone_ip: str, port: int, label: str) -> socket.socket | None:
        attempt = 0
        while not self._stop.is_set():
            attempt += 1
            connection = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            connection.settimeout(2.0)
            try:
                connection.connect((phone_ip, port))
                connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                connection.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                connection.settimeout(0.5)
                self._register_socket(connection)
                self.log(f"{label} 无线通道已连接：{phone_ip}:{port}")
                return connection
            except OSError:
                connection.close()
                if attempt == 1:
                    self.log(f"等待手机启动 {label} 服务…")
                self._stop.wait(0.5)
        return None

    def _control_worker(
        self,
        phone_ip: str,
        session_key: bytes,
        capture: CaptureWriter,
    ) -> None:
        connection = self._connect_with_retry(phone_ip, CONTROL_PORT, "CONTROL")
        if connection is None:
            return
        reassembler = UCarStreamReassembler()
        heartbeat_sequence = 1
        next_heartbeat: float | None = None
        try:
            while not self._stop.is_set():
                now = time.monotonic()
                if next_heartbeat is not None and now >= next_heartbeat:
                    heartbeat = encrypt_session_message(
                        session_key,
                        build_heartbeat(heartbeat_sequence),
                    )
                    connection.sendall(heartbeat)
                    capture.frame("pc_to_phone", 2, heartbeat)
                    heartbeat_sequence += 1
                    next_heartbeat = now + 2.0
                try:
                    data = connection.recv(64 * 1024)
                except TimeoutError:
                    continue
                if not data:
                    break
                capture.frame("phone_to_pc", 2, data)
                for message in reassembler.feed(data):
                    plaintext = (
                        decrypt_session_message(session_key, message)
                        if len(message) > 20
                        else message
                    )
                    response = build_get_config_response(plaintext, self.identity)
                    if response is None:
                        continue
                    encrypted = encrypt_session_message(session_key, response)
                    connection.sendall(encrypted)
                    capture.frame("pc_to_phone", 2, encrypted)
                    self.log("已回复手机 UCar 无线显示配置")
                    if next_heartbeat is None:
                        next_heartbeat = time.monotonic()
                        self.log("已启动无线 CONTROL 心跳")
        except (ConnectionError, OSError, ValueError) as exc:
            if not self._stop.is_set():
                self.log(f"CONTROL 通道结束：{exc}")
        finally:
            self._close_socket(connection)

    def _rtsp_worker(self, phone_ip: str, capture: CaptureWriter) -> None:
        connection = self._connect_with_retry(phone_ip, RTSP_PORT, "RTSP")
        if connection is None:
            return
        handshake = RtspSinkHandshake()
        try:
            while not self._stop.is_set():
                try:
                    data = connection.recv(64 * 1024)
                except TimeoutError:
                    continue
                if not data:
                    break
                capture.frame("phone_to_pc", 3, data)
                for description, response in handshake.handle(data):
                    if response:
                        connection.sendall(response)
                        capture.frame("pc_to_phone", 3, response)
                    self.log(description)
        except (ConnectionError, OSError, ValueError) as exc:
            if not self._stop.is_set():
                self.log(f"RTSP 通道结束：{exc}")
        finally:
            self._close_socket(connection)

    def _rtp_worker(
        self,
        capture: CaptureWriter,
        video_decoder: VideoDecoder | None,
        ready: threading.Event,
    ) -> None:
        listener = self._register_socket(socket.socket(socket.AF_INET, socket.SOCK_STREAM))
        try:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("0.0.0.0", RTP_PORT))
            listener.listen(1)
            listener.settimeout(0.5)
            ready.set()
            self.log(f"RTP 视频接收端已监听 TCP {RTP_PORT}")
            connection: socket.socket | None = None
            while not self._stop.is_set() and connection is None:
                try:
                    value, peer = listener.accept()
                except TimeoutError:
                    continue
                connection = self._register_socket(value)
                connection.settimeout(0.5)
                connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self.log(f"手机 RTP 视频流已接入：{peer[0]}")
            if connection is None:
                return

            extractor = RtpMpegTsExtractor()
            while not self._stop.is_set():
                try:
                    data = connection.recv(256 * 1024)
                except TimeoutError:
                    continue
                if not data:
                    break
                previous_count = extractor.packet_count
                capture.frame("phone_to_pc", 4, data)
                transport_stream = extractor.feed(data)
                if transport_stream and video_decoder is not None:
                    video_decoder.feed(transport_stream)
                if previous_count == 0 and extractor.packet_count > 0:
                    self.log("已收到首个无线 RTP/MPEG-TS 视频包")
                    self.state("无线投屏中")
                elif extractor.packet_count // 300 > previous_count // 300:
                    self.log(f"已接收 {extractor.packet_count} 个无线 RTP 视频包")
            self._close_socket(connection)
        except (ConnectionError, OSError, ValueError) as exc:
            ready.set()
            if not self._stop.is_set():
                self.log(f"RTP 视频通道结束：{exc}")
        finally:
            ready.set()
            self._close_socket(listener)

    def _uibc_worker(self, phone_ip: str) -> None:
        connection = self._connect_with_retry(phone_ip, UIBC_PORT, "UIBC")
        if connection is None:
            return
        with self._uibc_lock:
            self._uibc_socket = connection
        self.log("鼠标与车机按键控制已就绪")
        try:
            while not self._stop.is_set():
                try:
                    data = connection.recv(1024)
                except TimeoutError:
                    continue
                if not data:
                    break
        except OSError:
            pass
        finally:
            with self._uibc_lock:
                if self._uibc_socket is connection:
                    self._uibc_socket = None
            self._close_socket(connection)

    def _drain_channel_worker(
        self,
        phone_ip: str,
        label: str,
        port: int,
        channel_id: int,
        capture: CaptureWriter,
    ) -> None:
        connection = self._connect_with_retry(phone_ip, port, label)
        if connection is None:
            return
        captured_first = False
        try:
            while not self._stop.is_set():
                try:
                    data = connection.recv(64 * 1024)
                except TimeoutError:
                    continue
                if not data:
                    break
                if not captured_first or capture.full_payload:
                    capture.frame("phone_to_pc", channel_id, data)
                    captured_first = True
        except OSError:
            pass
        finally:
            self._close_socket(connection)
