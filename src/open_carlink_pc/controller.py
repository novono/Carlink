from __future__ import annotations

import threading
import time
from collections.abc import Callable
from pathlib import Path

from PIL import Image

from .aoa import AoaError, AoaTransport, describe_device, enumerate_usb_devices
from .auth import AuthSession, decrypt_session_message, encrypt_session_message
from .capture import CaptureWriter
from .config import CarIdentity, app_data_dir
from .mux import CONTROL_CHANNEL_ID, Multiplexer
from .rtsp import RtspSinkHandshake
from .ucar_protocol import (
    UCarStreamReassembler,
    build_get_config_response,
    build_heartbeat,
    build_session_disconnect,
    is_session_disconnect,
)
from .uibc import build_key_event, build_touch_event
from .usb_cycle import cycle_phone_port, find_accessory_instance_id, wait_for_phone_mode
from .video import RtpMpegTsExtractor, VideoDecoder

_TEARDOWN_GRACE_SECONDS = 0.5
_USB_SESSION_IDLE_SECONDS = 12.0
_USB_RESET_ATTEMPTS = 2
_USB_RESET_VERIFY_SECONDS = 12.0


class CarLinkController:
    def __init__(
        self,
        identity: CarIdentity,
        log: Callable[[str], None],
        state: Callable[[str], None],
        video: Callable[[Image.Image], None] | None = None,
    ) -> None:
        self.identity = identity
        self.log = log
        self.state = state
        self.video = video
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._stopping = threading.Event()
        self._phone_disconnected = threading.Event()
        self._cycle_after_stop = threading.Event()
        self._usb_instance_id: str | None = None
        self._transport: AoaTransport | None = None
        self._multiplexer: Multiplexer | None = None
        self._auth_session: AuthSession | None = None
        self._rtsp: RtspSinkHandshake | None = None
        self.capture_path: Path | None = None

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self, full_payload: bool = False) -> None:
        if self.running:
            return
        self._stop.clear()
        self._stopping.clear()
        self._phone_disconnected.clear()
        self._cycle_after_stop.clear()
        self._usb_instance_id = None
        self._thread = threading.Thread(
            target=self._run,
            args=(full_payload,),
            name="CarLinkUsbWorker",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        if self._stopping.is_set():
            return
        self._stopping.set()
        self._cycle_after_stop.set()
        multiplexer = self._multiplexer
        rtsp = self._rtsp
        auth = self._auth_session
        notified_phone = False
        if multiplexer is not None and rtsp is not None:
            teardown = rtsp.build_teardown()
            if teardown:
                try:
                    multiplexer.write_frame(3, teardown)
                    self.log("已通知手机结束当前 RTSP 投屏会话")
                except (AoaError, ValueError):
                    pass
                else:
                    notified_phone = True
        if (
            multiplexer is not None
            and auth is not None
            and auth.confirmed
            and auth.session_key is not None
        ):
            try:
                disconnect = build_session_disconnect(int(time.time() * 1000) & 0xFFFFFFFF)
                encrypted = encrypt_session_message(auth.session_key, disconnect)
                multiplexer.write_frame(2, encrypted)
                self.log("已发送加密 UCar 会话断开通知")
            except (AoaError, ValueError):
                pass
            else:
                notified_phone = True
        if notified_phone:
            timer = threading.Timer(
                _TEARDOWN_GRACE_SECONDS,
                self._finish_stop,
            )
            timer.daemon = True
            timer.start()
            return
        self._finish_stop()

    def _finish_stop(self) -> None:
        self._stop.set()
        transport = self._transport
        if transport is not None:
            transport.close()

    def send_touch(self, action: int, x: int, y: int) -> bool:
        if self._stopping.is_set():
            return False
        multiplexer = self._multiplexer
        if multiplexer is None:
            return False
        try:
            multiplexer.write_frame(5, build_touch_event(action, x, y))
            return True
        except (AoaError, ValueError):
            return False

    def send_key(self, key_code: int) -> bool:
        if self._stopping.is_set():
            return False
        multiplexer = self._multiplexer
        if multiplexer is None:
            return False
        try:
            multiplexer.write_frame(5, build_key_event(key_code))
            return True
        except (AoaError, ValueError):
            return False

    def _reset_phone_usb(self, capture: CaptureWriter | None = None) -> str:
        self.state("正在重置手机 USB 端口…")
        if self._usb_instance_id is None:
            self.log("无法安全定位当前手机的 USB 端口；请手动拔插 USB")
            return "需要拔插 USB"

        for attempt in range(1, _USB_RESET_ATTEMPTS + 1):
            self.log(f"正在执行第 {attempt}/{_USB_RESET_ATTEMPTS} 次 USB 端口复位…")
            cycled, detail = cycle_phone_port(self._usb_instance_id)
            self.log(detail)
            if capture is not None:
                capture.event(
                    "usb_port_cycle",
                    attempt=attempt,
                    success=cycled,
                    detail=detail,
                )
            if not cycled:
                continue

            self.log("正在确认 AOA 已消失且普通 USB 模式保持稳定…")
            last_observation: tuple[bool, tuple[int, int] | None] | None = None

            def record_observation(
                accessory_present: bool,
                phone_mode: tuple[int, int] | None,
                attempt_number: int = attempt,
            ) -> None:
                nonlocal last_observation
                current = accessory_present, phone_mode
                if current == last_observation:
                    return
                last_observation = current
                phone_id = (
                    f"{phone_mode[0]:04X}:{phone_mode[1]:04X}"
                    if phone_mode is not None
                    else "无"
                )
                self.log(
                    f"USB 枚举状态：AOA={'存在' if accessory_present else '已消失'}，"
                    f"普通手机={phone_id}"
                )
                if capture is not None:
                    capture.event(
                        "usb_enumeration",
                        attempt=attempt_number,
                        accessory_present=accessory_present,
                        phone_vendor_id=(
                            f"{phone_mode[0]:04X}" if phone_mode is not None else None
                        ),
                        phone_product_id=(
                            f"{phone_mode[1]:04X}" if phone_mode is not None else None
                        ),
                    )

            phone_mode = wait_for_phone_mode(
                _USB_RESET_VERIFY_SECONDS,
                stable_seconds=3.0,
                observation=record_observation,
            )
            if phone_mode is not None:
                vendor_id, product_id = phone_mode
                self.log(
                    "手机已稳定恢复普通 USB 模式："
                    f"{vendor_id:04X}:{product_id:04X}"
                )
                return "已真实断开，可重新连接"
            if attempt < _USB_RESET_ATTEMPTS:
                self.log("手机仍处于/重新进入 AOA 模式，准备再次复位")

        self.log("两次复位后手机仍未稳定退出 AOA；请手动拔插 USB")
        return "需要拔插 USB"

    def _run(self, full_payload: bool) -> None:
        capture = CaptureWriter(app_data_dir() / "captures", full_payload=full_payload)
        heartbeat_stop = threading.Event()
        heartbeat_thread: threading.Thread | None = None
        video_decoder: VideoDecoder | None = None
        terminal_state: str | None = None
        self.capture_path = capture.path
        capture.event(
            "session_start",
            car_id_hash=self.identity.car_id[-4:].rjust(12, "*"),
            model_id=self.identity.model_id,
            protocol_version=self.identity.protocol_version,
            full_payload=full_payload,
        )
        try:
            self.state("正在扫描 USB 手机…")
            devices = enumerate_usb_devices()
            if not devices:
                raise AoaError("未发现 OPPO/Android USB 设备；请解锁手机并选择文件传输")

            accessory = next((device for device, info in devices if info.accessory_mode), None)
            if accessory is None:
                device, info = next(
                    (
                        item
                        for item in devices
                        if item[1].vendor_id == 0x22D9
                    ),
                    devices[0],
                )
                self.log(f"发现手机：{info.display_name}")
                capture.event(
                    "usb_phone",
                    vendor_id=f"{info.vendor_id:04X}",
                    product_id=f"{info.product_id:04X}",
                    product=info.product,
                )
                self.state("正在切换手机到 CarLink AOA 模式…")
                AoaTransport.switch_to_accessory(device, self.identity, self.log)
                accessory = AoaTransport.wait_for_accessory(15.0, self.log)
            else:
                self.log("手机已经处于 CarLink AOA 模式")

            accessory_info = describe_device(accessory)
            self._usb_instance_id = find_accessory_instance_id(
                accessory_info.vendor_id,
                accessory_info.product_id,
                accessory_info.serial,
            )
            if self._usb_instance_id is not None:
                self.log(f"已定位手机 USB 设备：{self._usb_instance_id}")
            else:
                self.log("未能定位手机对应的 Windows USB 设备实例")

            self.state("正在打开 CarLink USB 通道…")
            transport = AoaTransport(accessory, self.log)
            self._transport = transport
            transport.open()
            multiplexer = Multiplexer(transport, capture, self.log)
            self._multiplexer = multiplexer
            rtsp = RtspSinkHandshake()
            self._rtsp = rtsp
            auth = AuthSession(self.identity, app_data_dir() / "auth")
            self._auth_session = auth
            pending_control: list[bytes] = []
            control_stream = UCarStreamReassembler()
            auth_stream = UCarStreamReassembler()
            rtp_extractor = RtpMpegTsExtractor()
            if self.video is not None:
                first_decoded_frame = True

                def publish_video_frame(frame: Image.Image) -> None:
                    nonlocal first_decoded_frame
                    if self._stopping.is_set():
                        return
                    self.video(frame)
                    if first_decoded_frame:
                        first_decoded_frame = False
                        self.log("已解码首帧视频画面")
                        self.state("投屏中")

                video_decoder = VideoDecoder(publish_video_frame, self.log)
                video_decoder.start()
            self.state("USB 已连接，等待 OPPO Car+ 数据…")

            def process_control(payload: bytes) -> None:
                try:
                    phone_requested_disconnect = is_session_disconnect(payload)
                except ValueError:
                    phone_requested_disconnect = False
                if phone_requested_disconnect:
                    self.log("手机已通知断开当前 CarLink 会话")
                    capture.event("phone_session_disconnect")
                    self.state("手机已断开，正在清理 USB 会话…")
                    self._phone_disconnected.set()
                    self._cycle_after_stop.set()
                    self._stopping.set()
                    self._stop.set()
                    return
                if not auth.confirmed or auth.session_key is None:
                    pending_control.append(payload)
                    self.log("已暂存 CONTROL 请求，等待 AUTH 认证完成")
                    return
                try:
                    plaintext = (
                        decrypt_session_message(auth.session_key, payload)
                        if len(payload) > 20
                        else payload
                    )
                    response = build_get_config_response(plaintext, self.identity)
                except ValueError as exc:
                    self.log(f"CONTROL 消息解密/解析失败：{exc}")
                    return
                if response is None:
                    self.log(f"收到 CONTROL 数据 {len(payload)} 字节")
                    return
                encrypted_response = encrypt_session_message(auth.session_key, response)
                multiplexer.write_frame(2, encrypted_response)
                self.log("已加密回复手机 UCar 车机显示配置")
                nonlocal heartbeat_thread
                if heartbeat_thread is None:
                    def send_heartbeats() -> None:
                        sequence_id = 1
                        while (
                            not heartbeat_stop.is_set()
                            and not self._stop.is_set()
                            and not self._stopping.is_set()
                        ):
                            try:
                                heartbeat = build_heartbeat(sequence_id)
                                encrypted = encrypt_session_message(auth.session_key, heartbeat)
                                multiplexer.write_frame(2, encrypted)
                            except Exception:  # noqa: BLE001 - USB worker is shutting down
                                return
                            if sequence_id == 1:
                                self.log("已启动加密 UCar 2 秒心跳")
                            sequence_id += 1
                            heartbeat_stop.wait(2.0)

                    heartbeat_thread = threading.Thread(
                        target=send_heartbeats,
                        name="CarLinkHeartbeat",
                        daemon=True,
                    )
                    heartbeat_thread.start()

            last_received_at = time.monotonic()
            session_idle = threading.Event()

            def should_stop_read() -> bool:
                if self._stop.is_set():
                    return True
                if time.monotonic() - last_received_at >= _USB_SESSION_IDLE_SECONDS:
                    session_idle.set()
                    return True
                return False

            while not self._stop.is_set():
                try:
                    frame = multiplexer.read_frame(should_stop_read)
                except AoaError:
                    if not session_idle.is_set():
                        raise
                    self.log(
                        f"连续 {_USB_SESSION_IDLE_SECONDS:g} 秒未收到手机数据，"
                        "判定旧 USB 会话已失效"
                    )
                    capture.event(
                        "usb_session_idle",
                        timeout_seconds=_USB_SESSION_IDLE_SECONDS,
                    )
                    self.state("USB 会话无响应，正在真实断开…")
                    self._cycle_after_stop.set()
                    self._stopping.set()
                    self._stop.set()
                    break
                last_received_at = time.monotonic()
                if frame.channel_id == CONTROL_CHANNEL_ID:
                    multiplexer.handle_control(frame)
                elif frame.channel_id == 2:
                    try:
                        control_messages = control_stream.feed(frame.payload)
                    except ValueError as exc:
                        self.log(f"CONTROL 流重组失败：{exc}")
                    else:
                        for message in control_messages:
                            process_control(message)
                elif frame.channel_id == 3:
                    try:
                        responses = rtsp.handle(frame.payload)
                    except ValueError as exc:
                        self.log(f"收到未完整解析的 RTSP 数据：{exc}")
                    else:
                        for description, response in responses:
                            if response:
                                multiplexer.write_frame(3, response)
                            self.log(description)
                elif frame.channel_id == 7:
                    try:
                        auth_messages = auth_stream.feed(frame.payload)
                    except ValueError as exc:
                        self.log(f"AUTH 流重组失败：{exc}")
                    else:
                        for message in auth_messages:
                            try:
                                description, response = auth.handle(message)
                            except ValueError as exc:
                                self.log(f"认证消息解析失败：{exc}")
                                continue
                            if response:
                                multiplexer.write_frame(7, response)
                            self.log(description)
                            if auth.confirmed and pending_control:
                                queued = pending_control[:]
                                pending_control.clear()
                                for payload in queued:
                                    process_control(payload)
                elif frame.channel_id == 4:
                    previous_count = rtp_extractor.packet_count
                    try:
                        transport_stream = rtp_extractor.feed(frame.payload)
                    except ValueError as exc:
                        self.log(f"RTP 视频流解析失败：{exc}")
                    else:
                        if transport_stream and video_decoder is not None:
                            video_decoder.feed(transport_stream)
                        if previous_count == 0 and rtp_extractor.packet_count > 0:
                            self.log("已收到首个 RTP/MPEG-TS 视频包")
                        elif rtp_extractor.packet_count // 300 > previous_count // 300:
                            self.log(f"已接收 {rtp_extractor.packet_count} 个 RTP 视频包")
                else:
                    self.log(
                        f"收到 {multiplexer.describe_channel(frame.channel_id)} "
                        f"数据 {len(frame.payload)} 字节"
                    )
        except AoaError as exc:
            if not self._stopping.is_set():
                self.log(f"错误：{exc}")
                capture.event("error", message=str(exc))
                if self._usb_instance_id is not None:
                    self.log("AOA 会话异常，正在强制复位 USB 以清除残留会话")
                    self._cycle_after_stop.set()
                    self._stopping.set()
                else:
                    terminal_state = "连接失败"
        except Exception as exc:  # noqa: BLE001 - diagnostic boundary
            if not self._stopping.is_set():
                self.log(f"未处理错误：{type(exc).__name__}: {exc}")
                capture.event("error", message=f"{type(exc).__name__}: {exc}")
                if self._usb_instance_id is not None:
                    self.log("AOA 会话异常，正在强制复位 USB 以清除残留会话")
                    self._cycle_after_stop.set()
                    self._stopping.set()
                else:
                    terminal_state = "连接失败"
        finally:
            heartbeat_stop.set()
            if heartbeat_thread is not None:
                heartbeat_thread.join(timeout=0.5)
            if video_decoder is not None:
                video_decoder.stop()
            if self._transport is not None:
                self._transport.close()
            self._transport = None
            self._multiplexer = None
            self._auth_session = None
            self._rtsp = None
            cycle_requested = self._cycle_after_stop.is_set()
            capture.event(
                "session_end",
                phone_disconnected=self._phone_disconnected.is_set(),
                cycle_requested=cycle_requested,
            )
            if cycle_requested:
                terminal_state = self._reset_phone_usb(capture)
            elif self._stopping.is_set():
                terminal_state = "已停止"
            self._thread = None
            if terminal_state is not None:
                self.state(terminal_state)
