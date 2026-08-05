from __future__ import annotations

from dataclasses import dataclass


_VIDEO_FORMATS = (
    "28 00 01 01 FFFFFFFF FFFFFFFF FFFFFFFF "
    "00 0000 0000 00 none none"
)

_AOA_RTP_PORT = 15550
_WFD_ADVERTISED_RTP_PORT = 19000


@dataclass(frozen=True, slots=True)
class RtspMessage:
    start_line: str
    headers: dict[str, str]
    body: bytes

    @classmethod
    def parse(cls, payload: bytes) -> "RtspMessage":
        message, consumed = cls.parse_prefix(payload)
        if consumed != len(payload):
            raise ValueError("RTSP 数据中包含多条消息，请使用流式解析")
        return message

    @classmethod
    def parse_prefix(cls, payload: bytes) -> tuple["RtspMessage", int]:
        header_end = payload.find(b"\r\n\r\n")
        if header_end < 0:
            raise ValueError("RTSP 消息头尚未接收完整")

        header_bytes = payload[:header_end]
        lines = header_bytes.decode("utf-8", errors="replace").split("\r\n")
        if not lines or not lines[0]:
            raise ValueError("RTSP 消息缺少起始行")

        headers: dict[str, str] = {}
        for line in lines[1:]:
            name, colon, value = line.partition(":")
            if colon:
                headers[name.strip().lower()] = value.strip()

        try:
            content_length = int(headers.get("content-length", "0"))
        except ValueError as exc:
            raise ValueError("RTSP Content-Length 无效") from exc
        if content_length < 0:
            raise ValueError("RTSP Content-Length 不能为负数")

        consumed = header_end + 4 + content_length
        if len(payload) < consumed:
            raise ValueError("RTSP 消息正文尚未接收完整")
        body = payload[header_end + 4 : consumed]
        return cls(lines[0], headers, body), consumed


class RtspSinkHandshake:
    def __init__(self) -> None:
        self._next_cseq = 1
        self._sent_own_options = False
        self._buffer = bytearray()
        self._pending: dict[int, str] = {}
        self._session = ""
        self._presentation_url = "rtsp://127.0.0.1/wfd1.0/streamid=0"

    @staticmethod
    def _response(cseq: str, extra_headers: tuple[str, ...] = (), body: bytes = b"") -> bytes:
        lines = ["RTSP/1.0 200 OK", f"CSeq: {cseq}", *extra_headers]
        if body:
            lines.extend(("Content-Type: text/parameters", f"Content-Length: {len(body)}"))
        return ("\r\n".join(lines) + "\r\n\r\n").encode("ascii") + body

    def _request(
        self,
        method: str,
        uri: str,
        extra_headers: tuple[str, ...] = (),
        pending: str = "",
    ) -> bytes:
        cseq = self._next_cseq
        self._next_cseq += 1
        if pending:
            self._pending[cseq] = pending
        lines = [f"{method} {uri} RTSP/1.0", f"CSeq: {cseq}", *extra_headers]
        return ("\r\n".join(lines) + "\r\n\r\n").encode("ascii")

    @staticmethod
    def _capability_body(requested_body: bytes) -> bytes:
        # The ICCOA sink returns its complete fixed capability set for M3.
        # ColorOS does not list the vendor extensions in its query, but still
        # requires wfd_car_display_mode before it starts the screen encoder.
        del requested_body
        capabilities = (
            (
                "wfd_car_display_mode",
                "wfd_car_display_mode: mode=0;display=1280:720;dpi=320;fps=30",
            ),
            ("wfd_video_formats", f"wfd_video_formats: {_VIDEO_FORMATS}"),
            ("wfd_audio_codecs", "wfd_audio_codecs: AAC 0000000F 00"),
            (
                "wfd_client_rtp_ports",
                "wfd_client_rtp_ports: RTP/AVP/TCP;unicast "
                f"{_WFD_ADVERTISED_RTP_PORT} 0 mode=play",
            ),
            (
                "wfd_uibc_capability",
                "wfd_uibc_capability: input_category_list=GENERIC;"
                "generic_cap_list=Keyboard, Mouse, SingleTouch;"
                "hidc_cap_list=none;port=none;uibc_encrypted=false",
            ),
            ("ovm_control_capability", "ovm_control_capability: supported"),
            (
                "wfd_standby_resume_capability",
                "wfd_standby_resume_capability: supported",
            ),
        )
        lines = [value for _name, value in capabilities]
        return ("\r\n".join(lines) + ("\r\n" if lines else "")).encode("ascii")

    def _handle_message(self, message: RtspMessage) -> list[tuple[str, bytes]]:
        output: list[tuple[str, bytes]] = []
        cseq = message.headers.get("cseq", "1")

        if message.start_line.startswith("RTSP/"):
            try:
                cseq_number = int(cseq)
            except ValueError:
                return output
            pending = self._pending.pop(cseq_number, "")
            if pending == "setup" and message.start_line.startswith("RTSP/1.0 200"):
                session = message.headers.get("session", "").split(";", 1)[0].strip()
                if session:
                    self._session = session
                    play = self._request(
                        "PLAY",
                        self._presentation_url,
                        (f"Session: {session}",),
                        pending="play",
                    )
                    output.append(("SETUP 成功，发送 RTSP PLAY", play))
            elif pending == "play" and message.start_line.startswith("RTSP/1.0 200"):
                output.append(("RTSP PLAY 成功，等待手机视频流", b""))
            return output

        if message.start_line.startswith("OPTIONS "):
            output.append(
                (
                    "回复手机 RTSP OPTIONS",
                    self._response(
                        cseq,
                        ("Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER",),
                    ),
                )
            )
            if not self._sent_own_options:
                self._sent_own_options = True
                request = self._request(
                    "OPTIONS",
                    "*",
                    ("Require: org.wfa.wfd1.0",),
                    pending="options",
                )
                output.append(("发送车机 RTSP OPTIONS", request))
        elif message.start_line.startswith("GET_PARAMETER "):
            body = self._capability_body(message.body) if message.body else b""
            output.append(("回复手机 RTSP 能力查询", self._response(cseq, body=body)))
        elif message.start_line.startswith("SET_PARAMETER "):
            for raw_line in message.body.splitlines():
                line = raw_line.decode("ascii", errors="ignore").strip()
                if line.lower().startswith("wfd_presentation_url:"):
                    value = line.split(":", 1)[1].strip().split(" ", 1)[0]
                    if value.startswith("rtsp://"):
                        self._presentation_url = value
            if b"wfd_trigger_method: SETUP" in message.body:
                setup = self._request(
                    "SETUP",
                    self._presentation_url,
                    (
                        "Transport: RTP/AVP/TCP;unicast;"
                        f"client_port={_AOA_RTP_PORT}",
                    ),
                    pending="setup",
                )
                output.append(("手机触发 SETUP，发送 RTSP SETUP", setup))
            output.append(("回复手机 RTSP 参数设置", self._response(cseq)))
        return output

    def handle(self, payload: bytes) -> list[tuple[str, bytes]]:
        self._buffer.extend(payload)
        output: list[tuple[str, bytes]] = []
        while self._buffer:
            try:
                message, consumed = RtspMessage.parse_prefix(bytes(self._buffer))
            except ValueError as exc:
                if "尚未接收完整" in str(exc):
                    break
                self._buffer.clear()
                raise
            del self._buffer[:consumed]
            output.extend(self._handle_message(message))
        return output

    def build_teardown(self) -> bytes:
        if not self._session:
            return b""
        return self._request(
            "TEARDOWN",
            self._presentation_url,
            (f"Session: {self._session}",),
            pending="teardown",
        )
