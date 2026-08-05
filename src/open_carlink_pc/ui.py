from __future__ import annotations

import queue
import subprocess
import time
import tkinter as tk
from tkinter import messagebox

from PIL import Image, ImageTk

from . import __version__
from .config import app_data_dir, load_identity
from .controller import CarLinkController
from .uibc import ACTION_DOWN, ACTION_MOVE, ACTION_UP, KEY_CODE_BACK, KEY_CODE_MAIN
from .wireless_controller import WirelessCarLinkController


class MainWindow:
    SOURCE_WIDTH = 1280
    SOURCE_HEIGHT = 720
    USB_MODE = "USB 有线"
    WIRELESS_MODE = "无线实验"

    BG = "#0c0f0e"
    SURFACE = "#141816"
    SURFACE_RAISED = "#1c211e"
    SURFACE_ACTIVE = "#29312d"
    BORDER = "#2b332f"
    TEXT = "#f2f5f3"
    TEXT_MUTED = "#8e9a93"
    TEXT_DIM = "#66716b"
    GREEN = "#39d98a"
    GREEN_DARK = "#176b48"
    RED = "#ef6b63"
    AMBER = "#e8ad55"

    def __init__(self, auto_connect: bool = False) -> None:
        self.root = tk.Tk()
        self.root.title(f"OpenCarLink PC v{__version__} — OPPO ICCOA 有线互联")
        self.root.geometry("1320x820")
        self.root.minsize(960, 600)
        self.root.configure(background=self.BG)

        self.identity = load_identity()
        self.events: queue.Queue[tuple[str, str]] = queue.Queue()
        self.video_frames: queue.Queue[Image.Image] = queue.Queue(maxsize=1)
        controller_args = (
            self.identity,
            lambda message: self.events.put(("log", message)),
            lambda state: self.events.put(("state", state)),
            self._queue_video_frame,
        )
        self.usb_controller = CarLinkController(*controller_args)
        self.wireless_controller = WirelessCarLinkController(*controller_args)
        self.controller: CarLinkController | WirelessCarLinkController = self.usb_controller

        self.connection_mode = tk.StringVar(value=self.USB_MODE)
        self.display_mode = tk.StringVar(value="fit")
        self.full_payload = tk.BooleanVar(value=False)
        self.state_text = tk.StringVar(value="等待连接")
        self.state_detail = tk.StringVar(value="USB · OPPO ICCOA")
        self.fill_mode = False
        self.log_visible = False
        self.fullscreen = False
        self._log_before_fullscreen = False
        self._video_photo: ImageTk.PhotoImage | None = None
        self._last_video_frame: Image.Image | None = None
        self._video_mapping: tuple[float, float, float, int, int] | None = None
        self._resize_job: str | None = None
        self._touch_active = False
        self._last_motion_time = 0.0
        self._last_progress_index = -1

        self._build()
        self._set_state("USB 有线未启动")
        self.root.after(100, self._drain_events)
        self.root.protocol("WM_DELETE_WINDOW", self._close)
        self.root.bind("<F11>", lambda _event: self._toggle_fullscreen())
        self.root.bind("<Escape>", self._leave_fullscreen)
        if auto_connect:
            self.root.after(400, self._connect)

    def _build(self) -> None:
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(2, weight=1)
        self._build_header()
        self._build_control_bar()
        self._build_video_surface()
        self._build_log_panel()

    def _build_header(self) -> None:
        self.header = tk.Frame(self.root, background=self.SURFACE, height=70, padx=18, pady=10)
        self.header.grid(row=0, column=0, sticky="ew")
        self.header.grid_propagate(False)
        self.header.columnconfigure(4, weight=1)

        brand_mark = tk.Canvas(
            self.header,
            width=42,
            height=42,
            background=self.SURFACE,
            highlightthickness=0,
        )
        brand_mark.grid(row=0, column=0, rowspan=2, padx=(0, 10))
        brand_mark.create_oval(3, 3, 39, 39, fill=self.GREEN_DARK, outline="")
        brand_mark.create_oval(12, 12, 30, 30, outline=self.GREEN, width=3)
        brand_mark.create_line(4, 21, 14, 21, fill=self.GREEN, width=3)
        brand_mark.create_line(28, 21, 38, 21, fill=self.GREEN, width=3)

        tk.Label(
            self.header,
            text="OpenCarLink",
            background=self.SURFACE,
            foreground=self.TEXT,
            font=("Segoe UI", 16, "bold"),
        ).grid(row=0, column=1, sticky="sw")
        tk.Label(
            self.header,
            text="ICCOA RECEIVER",
            background=self.SURFACE,
            foreground=self.TEXT_DIM,
            font=("Segoe UI", 8, "bold"),
        ).grid(row=1, column=1, sticky="nw")

        mode_group = tk.Frame(
            self.header,
            background=self.SURFACE_RAISED,
            highlightbackground=self.BORDER,
            highlightthickness=1,
            padx=3,
            pady=3,
        )
        mode_group.grid(row=0, column=2, rowspan=2, padx=(28, 10))
        self.mode_buttons: list[tk.Radiobutton] = []
        for column, (label, value) in enumerate(
            (("USB 有线", self.USB_MODE), ("无线实验", self.WIRELESS_MODE))
        ):
            button = tk.Radiobutton(
                mode_group,
                text=label,
                variable=self.connection_mode,
                value=value,
                command=self._mode_changed,
                indicatoron=False,
                selectcolor=self.SURFACE_ACTIVE,
                background=self.SURFACE_RAISED,
                foreground=self.TEXT_MUTED,
                activebackground=self.SURFACE_ACTIVE,
                activeforeground=self.TEXT,
                disabledforeground=self.TEXT_DIM,
                relief="flat",
                borderwidth=0,
                padx=14,
                pady=7,
                font=("Microsoft YaHei UI", 9),
            )
            button.grid(row=0, column=column)
            self.mode_buttons.append(button)

        self.connect_button = self._make_button(
            self.header,
            "连接手机",
            self._connect,
            kind="primary",
            width=10,
        )
        self.connect_button.grid(row=0, column=3, rowspan=2, padx=(0, 8))
        self.stop_button = self._make_button(
            self.header,
            "停止",
            self._stop,
            kind="danger",
            width=6,
        )
        self.stop_button.configure(state="disabled")
        self.stop_button.grid(row=0, column=4, rowspan=2, sticky="w")

        status = tk.Frame(self.header, background=self.SURFACE)
        status.grid(row=0, column=5, rowspan=2, sticky="e")
        self.status_dot = tk.Canvas(
            status,
            width=16,
            height=42,
            background=self.SURFACE,
            highlightthickness=0,
        )
        self.status_dot.pack(side="left", padx=(0, 8))
        self._status_dot_item = self.status_dot.create_oval(4, 15, 12, 23, fill=self.TEXT_DIM, outline="")
        status_text = tk.Frame(status, background=self.SURFACE)
        status_text.pack(side="left")
        tk.Label(
            status_text,
            textvariable=self.state_text,
            background=self.SURFACE,
            foreground=self.TEXT,
            font=("Microsoft YaHei UI", 10, "bold"),
        ).pack(anchor="e")
        tk.Label(
            status_text,
            textvariable=self.state_detail,
            background=self.SURFACE,
            foreground=self.TEXT_DIM,
            font=("Microsoft YaHei UI", 8),
        ).pack(anchor="e")

    def _build_control_bar(self) -> None:
        self.control_bar = tk.Frame(
            self.root,
            background=self.BG,
            height=54,
            padx=18,
            pady=8,
        )
        self.control_bar.grid(row=1, column=0, sticky="ew")
        self.control_bar.grid_propagate(False)
        self.control_bar.columnconfigure(1, weight=1)

        progress = tk.Frame(self.control_bar, background=self.BG)
        progress.grid(row=0, column=0, sticky="w")
        self.step_nodes: list[tuple[tk.Label, tk.Label]] = []
        for index, label in enumerate(("USB", "AOA", "认证", "画面")):
            if index:
                tk.Frame(progress, width=24, height=1, background=self.BORDER).pack(
                    side="left", padx=4
                )
            node = tk.Label(
                progress,
                text=str(index + 1),
                width=2,
                height=1,
                background=self.SURFACE_RAISED,
                foreground=self.TEXT_DIM,
                font=("Segoe UI", 8, "bold"),
            )
            node.pack(side="left")
            text = tk.Label(
                progress,
                text=label,
                background=self.BG,
                foreground=self.TEXT_DIM,
                font=("Microsoft YaHei UI", 9),
            )
            text.pack(side="left", padx=(5, 0))
            self.step_nodes.append((node, text))

        actions = tk.Frame(self.control_bar, background=self.BG)
        actions.grid(row=0, column=2, sticky="e")
        self._make_button(
            actions,
            "←  返回",
            lambda: self._send_key(KEY_CODE_BACK),
            compact=True,
        ).pack(side="left", padx=(0, 6))
        self._make_button(
            actions,
            "⌂  主页",
            lambda: self._send_key(KEY_CODE_MAIN),
            compact=True,
        ).pack(side="left", padx=(0, 12))

        display_group = tk.Frame(
            actions,
            background=self.SURFACE_RAISED,
            highlightbackground=self.BORDER,
            highlightthickness=1,
            padx=2,
            pady=2,
        )
        display_group.pack(side="left", padx=(0, 8))
        for label, value in (("完整", "fit"), ("铺满", "fill")):
            tk.Radiobutton(
                display_group,
                text=label,
                variable=self.display_mode,
                value=value,
                command=self._set_display_mode,
                indicatoron=False,
                selectcolor=self.SURFACE_ACTIVE,
                background=self.SURFACE_RAISED,
                foreground=self.TEXT_MUTED,
                activebackground=self.SURFACE_ACTIVE,
                activeforeground=self.TEXT,
                relief="flat",
                borderwidth=0,
                padx=10,
                pady=4,
                font=("Microsoft YaHei UI", 8),
            ).pack(side="left")

        self._make_button(actions, "全屏", self._toggle_fullscreen, compact=True).pack(
            side="left", padx=(0, 6)
        )
        self.log_button = self._make_button(actions, "诊断", self._toggle_log, compact=True)
        self.log_button.pack(side="left")

    def _build_video_surface(self) -> None:
        self.video_canvas = tk.Canvas(
            self.root,
            background=self.BG,
            borderwidth=0,
            highlightthickness=0,
            cursor="hand2",
        )
        self.video_canvas.grid(row=2, column=0, sticky="nsew")
        self._video_item = self.video_canvas.create_image(0, 0, anchor="nw")
        self._placeholder_ring = self.video_canvas.create_oval(
            0,
            0,
            88,
            88,
            outline=self.BORDER,
            width=2,
        )
        self._placeholder_icon = self.video_canvas.create_text(
            0,
            0,
            text="USB",
            fill=self.GREEN,
            font=("Segoe UI", 12, "bold"),
        )
        self._placeholder_title = self.video_canvas.create_text(
            0,
            0,
            text="连接 OPPO 手机",
            fill=self.TEXT,
            font=("Microsoft YaHei UI", 19, "bold"),
        )
        self._placeholder_body = self.video_canvas.create_text(
            0,
            0,
            text="解锁手机并将 USB 用途设为“文件传输”\n点击上方“连接手机”开始真实 CarLink 会话",
            fill=self.TEXT_MUTED,
            font=("Microsoft YaHei UI", 10),
            justify="center",
        )
        self._placeholder_meta = self.video_canvas.create_text(
            0,
            0,
            text=f"1280 × 720 · 30 FPS    车机 ID ····{self.identity.car_id[-4:]}",
            fill=self.TEXT_DIM,
            font=("Microsoft YaHei UI", 8),
        )
        self._placeholder_items = (
            self._placeholder_ring,
            self._placeholder_icon,
            self._placeholder_title,
            self._placeholder_body,
            self._placeholder_meta,
        )
        self.video_canvas.bind("<Configure>", self._video_resized)
        self.video_canvas.bind("<ButtonPress-1>", self._touch_down)
        self.video_canvas.bind("<B1-Motion>", self._touch_move)
        self.video_canvas.bind("<ButtonRelease-1>", self._touch_up)
        self.video_canvas.bind("<Double-Button-1>", lambda _event: self._toggle_fullscreen())

    def _build_log_panel(self) -> None:
        self.log_frame = tk.Frame(
            self.root,
            background=self.SURFACE,
            padx=18,
            pady=12,
            highlightbackground=self.BORDER,
            highlightthickness=1,
        )
        self.log_frame.columnconfigure(0, weight=1)
        self.log_frame.rowconfigure(1, weight=1)

        log_header = tk.Frame(self.log_frame, background=self.SURFACE)
        log_header.grid(row=0, column=0, sticky="ew", pady=(0, 8))
        tk.Label(
            log_header,
            text="连接诊断",
            background=self.SURFACE,
            foreground=self.TEXT,
            font=("Microsoft YaHei UI", 10, "bold"),
        ).pack(side="left")
        tk.Label(
            log_header,
            text="协议事件经过脱敏处理",
            background=self.SURFACE,
            foreground=self.TEXT_DIM,
            font=("Microsoft YaHei UI", 8),
        ).pack(side="left", padx=(10, 0))
        self._make_button(log_header, "打开日志目录", self._open_logs, compact=True).pack(
            side="right"
        )
        tk.Checkbutton(
            log_header,
            text="记录完整负载",
            variable=self.full_payload,
            background=self.SURFACE,
            foreground=self.TEXT_MUTED,
            activebackground=self.SURFACE,
            activeforeground=self.TEXT,
            selectcolor=self.SURFACE_RAISED,
            font=("Microsoft YaHei UI", 8),
            borderwidth=0,
            highlightthickness=0,
        ).pack(side="right", padx=(0, 12))

        self.log_text = tk.Text(
            self.log_frame,
            height=8,
            wrap="word",
            state="disabled",
            background="#0e1210",
            foreground="#b9c7bf",
            insertbackground=self.TEXT,
            relief="flat",
            borderwidth=0,
            padx=12,
            pady=10,
            font=("Cascadia Mono", 9),
        )
        self.log_text.grid(row=1, column=0, sticky="nsew")
        self.log_text.tag_configure("time", foreground=self.TEXT_DIM)
        self.log_text.tag_configure("error", foreground=self.RED)
        self.log_text.tag_configure("success", foreground=self.GREEN)

    def _make_button(
        self,
        parent: tk.Misc,
        text: str,
        command,
        *,
        kind: str = "secondary",
        width: int | None = None,
        compact: bool = False,
    ) -> tk.Button:
        colors = {
            "primary": (self.GREEN_DARK, self.TEXT, "#21865c"),
            "danger": (self.SURFACE_RAISED, self.RED, "#3b2523"),
            "secondary": (self.SURFACE_RAISED, self.TEXT_MUTED, self.SURFACE_ACTIVE),
        }
        background, foreground, active = colors[kind]
        options: dict[str, object] = {
            "text": text,
            "command": command,
            "background": background,
            "foreground": foreground,
            "activebackground": active,
            "activeforeground": self.TEXT,
            "disabledforeground": self.TEXT_DIM,
            "relief": "flat",
            "borderwidth": 0,
            "padx": 10 if compact else 14,
            "pady": 5 if compact else 9,
            "font": ("Microsoft YaHei UI", 8 if compact else 9, "bold"),
            "cursor": "hand2",
        }
        if width is not None:
            options["width"] = width
        return tk.Button(parent, **options)

    def _queue_video_frame(self, frame: Image.Image) -> None:
        try:
            self.video_frames.put_nowait(frame)
        except queue.Full:
            try:
                self.video_frames.get_nowait()
            except queue.Empty:
                pass
            try:
                self.video_frames.put_nowait(frame)
            except queue.Full:
                pass

    def _show_video_frame(self, frame: Image.Image) -> None:
        self._last_video_frame = frame
        width = max(1, self.video_canvas.winfo_width())
        height = max(1, self.video_canvas.winfo_height())
        if width < 8 or height < 8:
            return

        if self.fill_mode:
            scale = max(width / frame.width, height / frame.height)
            scaled_width = max(1, round(frame.width * scale))
            scaled_height = max(1, round(frame.height * scale))
            resized = frame.resize((scaled_width, scaled_height), Image.Resampling.BILINEAR)
            crop_x = max(0, (scaled_width - width) // 2)
            crop_y = max(0, (scaled_height - height) // 2)
            displayed = resized.crop((crop_x, crop_y, crop_x + width, crop_y + height))
            draw_x = draw_y = 0
            self._video_mapping = (scale, float(crop_x), float(crop_y), frame.width, frame.height)
        else:
            scale = min(width / frame.width, height / frame.height)
            scaled_width = max(1, round(frame.width * scale))
            scaled_height = max(1, round(frame.height * scale))
            displayed = frame.resize((scaled_width, scaled_height), Image.Resampling.BILINEAR)
            draw_x = (width - scaled_width) // 2
            draw_y = (height - scaled_height) // 2
            self._video_mapping = (
                scale,
                float(-draw_x),
                float(-draw_y),
                frame.width,
                frame.height,
            )

        self._video_photo = ImageTk.PhotoImage(displayed)
        self.video_canvas.itemconfigure(self._video_item, image=self._video_photo)
        self.video_canvas.coords(self._video_item, draw_x, draw_y)
        for item in self._placeholder_items:
            self.video_canvas.itemconfigure(item, state="hidden")

    def _video_resized(self, event: tk.Event) -> None:
        center_x = event.width / 2
        center_y = event.height / 2 - 12
        self.video_canvas.coords(
            self._placeholder_ring,
            center_x - 44,
            center_y - 126,
            center_x + 44,
            center_y - 38,
        )
        self.video_canvas.coords(self._placeholder_icon, center_x, center_y - 82)
        self.video_canvas.coords(self._placeholder_title, center_x, center_y - 4)
        self.video_canvas.coords(self._placeholder_body, center_x, center_y + 53)
        self.video_canvas.coords(self._placeholder_meta, center_x, center_y + 116)
        if self._last_video_frame is None:
            return
        if self._resize_job is not None:
            self.root.after_cancel(self._resize_job)
        self._resize_job = self.root.after(60, self._redraw_last_frame)

    def _redraw_last_frame(self) -> None:
        self._resize_job = None
        if self._last_video_frame is not None:
            self._show_video_frame(self._last_video_frame)

    def _map_touch(self, event: tk.Event) -> tuple[int, int] | None:
        mapping = self._video_mapping
        if mapping is None:
            return None
        scale, crop_x, crop_y, source_width, source_height = mapping
        source_x = (event.x + crop_x) / scale
        source_y = (event.y + crop_y) / scale
        if not (0 <= source_x < source_width and 0 <= source_y < source_height):
            return None
        return (
            min(self.SOURCE_WIDTH - 1, int(source_x * self.SOURCE_WIDTH / source_width)),
            min(self.SOURCE_HEIGHT - 1, int(source_y * self.SOURCE_HEIGHT / source_height)),
        )

    def _touch_down(self, event: tk.Event) -> str:
        point = self._map_touch(event)
        self._touch_active = point is not None
        if point is not None and not self.controller.send_touch(ACTION_DOWN, *point):
            self._append_log("触控尚未就绪；请等待状态显示为“投屏中”")
        return "break"

    def _touch_move(self, event: tk.Event) -> str:
        if not self._touch_active:
            return "break"
        now = time.monotonic()
        if now - self._last_motion_time < 1 / 30:
            return "break"
        self._last_motion_time = now
        point = self._map_touch(event)
        if point is not None:
            self.controller.send_touch(ACTION_MOVE, *point)
        return "break"

    def _touch_up(self, event: tk.Event) -> str:
        if self._touch_active:
            point = self._map_touch(event)
            if point is not None:
                self.controller.send_touch(ACTION_UP, *point)
        self._touch_active = False
        return "break"

    def _send_key(self, key_code: int) -> None:
        if not self.controller.send_key(key_code):
            self._append_log("按键控制尚未就绪；请先完成连接")

    def _all_controllers_stopped(self) -> bool:
        return not self.usb_controller.running and not self.wireless_controller.running

    def _mode_changed(self) -> None:
        if not self._all_controllers_stopped():
            return
        self._clear_video()
        if self.connection_mode.get() == self.WIRELESS_MODE:
            self.controller = self.wireless_controller
            self.connect_button.configure(text="启动无线")
            self._set_state("无线实验服务未启动")
            title = "等待无线 CarLink"
            body = "当前 ColorOS P2P 组网仍处于实验阶段\n建议优先使用已经验证的 USB 有线模式"
            icon = "WLAN"
        else:
            self.controller = self.usb_controller
            self.connect_button.configure(text="连接手机")
            self._set_state("USB 有线未启动")
            title = "连接 OPPO 手机"
            body = "解锁手机并将 USB 用途设为“文件传输”\n点击上方“连接手机”开始真实 CarLink 会话"
            icon = "USB"
        self.video_canvas.itemconfigure(self._placeholder_icon, text=icon)
        self.video_canvas.itemconfigure(self._placeholder_title, text=title)
        self.video_canvas.itemconfigure(self._placeholder_body, text=body)

    def _clear_video(self) -> None:
        try:
            while True:
                self.video_frames.get_nowait()
        except queue.Empty:
            pass
        self._last_video_frame = None
        self._video_mapping = None
        self._video_photo = None
        self.video_canvas.itemconfigure(self._video_item, image="")
        for item in self._placeholder_items:
            self.video_canvas.itemconfigure(item, state="normal")

    def _set_display_mode(self) -> None:
        self.fill_mode = self.display_mode.get() == "fill"
        self._redraw_last_frame()

    def _toggle_log(self) -> None:
        if self.log_visible:
            self.log_frame.grid_remove()
            self.log_button.configure(text="诊断")
        else:
            self.log_frame.grid(row=3, column=0, sticky="nsew")
            self.log_button.configure(text="收起诊断")
        self.log_visible = not self.log_visible

    def _toggle_fullscreen(self) -> str:
        if self.fullscreen:
            self._leave_fullscreen()
        else:
            self.fullscreen = True
            self._log_before_fullscreen = self.log_visible
            if self.log_visible:
                self._toggle_log()
            self.header.grid_remove()
            self.control_bar.grid_remove()
            self.root.attributes("-fullscreen", True)
        return "break"

    def _leave_fullscreen(self, _event: tk.Event | None = None) -> str:
        if self.fullscreen:
            self.root.attributes("-fullscreen", False)
            self.header.grid()
            self.control_bar.grid()
            self.fullscreen = False
            if self._log_before_fullscreen and not self.log_visible:
                self._toggle_log()
        return "break"

    def _append_log(self, message: str) -> None:
        tag = ""
        lowered = message.lower()
        if "错误" in message or "失败" in message or "error" in lowered:
            tag = "error"
        elif "成功" in message or "已启动" in message or "投屏中" in message:
            tag = "success"
        self.log_text.configure(state="normal")
        self.log_text.insert("end", time.strftime("%H:%M:%S  "), "time")
        self.log_text.insert("end", message + "\n", tag)
        self.log_text.see("end")
        self.log_text.configure(state="disabled")

    def _set_state(self, value: str) -> None:
        self.state_text.set(value)
        if "投屏中" in value:
            color = self.GREEN
            detail = "1280 × 720 · 30 FPS · UIBC"
            progress = 4
        elif "失败" in value:
            color = self.RED
            detail = "打开诊断查看失败层级"
            progress = self._last_progress_index
        elif "需要拔插" in value:
            color = self.RED
            detail = "USB 端口未成功复位，请拔插手机后重试"
            progress = -1
        elif "真实断开" in value:
            color = self.TEXT_DIM
            detail = "USB 会话已释放，可以重新连接"
            progress = -1
        elif "停止" in value or "断开" in value or "未启动" in value:
            color = self.TEXT_DIM
            detail = "USB · OPPO ICCOA" if self.controller is self.usb_controller else "BLE · P2P 实验"
            progress = -1
        elif "扫描" in value:
            color = self.AMBER
            detail = "正在枚举 Android USB 设备"
            progress = 0
        elif "切换" in value or "打开 CarLink USB" in value:
            color = self.AMBER
            detail = "Android Open Accessory"
            progress = 1
        elif "USB 已连接" in value or "认证" in value:
            color = self.AMBER
            detail = "正在协商 AUTH / CONTROL / RTSP"
            progress = 2
        else:
            color = self.AMBER
            detail = "正在建立 CarLink 会话"
            progress = max(0, self._last_progress_index)
        self.state_detail.set(detail)
        self.status_dot.itemconfigure(self._status_dot_item, fill=color)
        if progress >= 0:
            self._last_progress_index = progress
        self._update_progress(progress, failed="失败" in value)

    def _update_progress(self, progress: int, failed: bool = False) -> None:
        for index, (node, label) in enumerate(self.step_nodes):
            if progress >= 4 or index < progress:
                node.configure(background=self.GREEN_DARK, foreground=self.GREEN)
                label.configure(foreground=self.TEXT_MUTED)
            elif index == progress:
                node.configure(
                    background=self.RED if failed else self.SURFACE_ACTIVE,
                    foreground=self.TEXT if not failed else "#ffffff",
                )
                label.configure(foreground=self.RED if failed else self.TEXT)
            else:
                node.configure(background=self.SURFACE_RAISED, foreground=self.TEXT_DIM)
                label.configure(foreground=self.TEXT_DIM)

    def _set_mode_buttons_enabled(self, enabled: bool) -> None:
        state = "normal" if enabled else "disabled"
        for button in self.mode_buttons:
            button.configure(state=state)

    def _connect(self) -> None:
        if not self._all_controllers_stopped():
            return
        if self.full_payload.get():
            confirmed = messagebox.askyesno(
                "隐私确认",
                "完整负载可能包含导航、媒体或通话数据。确认记录吗？",
            )
            if not confirmed:
                return
        self._mode_changed()
        self.connect_button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self._set_mode_buttons_enabled(False)
        if self.controller is self.wireless_controller:
            self._append_log("正在启动无线实验模式；当前 ColorOS P2P 可能在组网阶段失败。")
        else:
            self._append_log("正在扫描 USB 手机；请保持手机解锁并选择文件传输。")
        self.controller.start(full_payload=self.full_payload.get())

    def _stop(self) -> None:
        if not self.controller.running:
            return
        self._clear_video()
        self._set_state("正在停止…")
        self._append_log("正在结束当前会话、释放 WinUSB 并重置手机 USB 端口…")
        self.controller.stop()
        self.connect_button.configure(state="disabled")
        self.stop_button.configure(state="disabled")
        self._set_mode_buttons_enabled(False)

    def _open_logs(self) -> None:
        path = app_data_dir() / "captures"
        path.mkdir(parents=True, exist_ok=True)
        subprocess.Popen(["explorer.exe", str(path)])

    def _drain_events(self) -> None:
        try:
            while True:
                event_type, value = self.events.get_nowait()
                if event_type == "log":
                    self._append_log(value)
                elif event_type == "state":
                    self._set_state(value)
                    if value in {
                        "已真实断开，可重新连接",
                        "连接失败",
                        "已停止",
                        "需要拔插 USB",
                    }:
                        self._clear_video()
                        self.connect_button.configure(state="normal")
                        self.stop_button.configure(state="disabled")
                        self._set_mode_buttons_enabled(True)
        except queue.Empty:
            pass

        latest_frame: Image.Image | None = None
        try:
            while True:
                latest_frame = self.video_frames.get_nowait()
        except queue.Empty:
            pass
        if latest_frame is not None:
            self._show_video_frame(latest_frame)
        self.root.after(15, self._drain_events)

    def _close(self) -> None:
        self.usb_controller.stop()
        self.wireless_controller.stop()
        self.root.destroy()

    def run(self) -> None:
        try:
            self.root.state("zoomed")
        except tk.TclError:
            pass
        self.root.mainloop()


def run_gui(auto_connect: bool = False) -> None:
    MainWindow(auto_connect=auto_connect).run()
