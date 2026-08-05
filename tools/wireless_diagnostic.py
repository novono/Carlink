from __future__ import annotations

import argparse
import base64
import ctypes
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Iterable

from open_carlink_pc.config import load_identity
from open_carlink_pc.wireless_controller import WirelessCarLinkController


PROJECT_DIR = Path(__file__).resolve().parents[1]
WORK_DIR = PROJECT_DIR.parent
DEFAULT_CAPTURE_ROOT = WORK_DIR / "wireless-captures"
WLAN_EVENT_LOG = "Microsoft-Windows-WLAN-AutoConfig/Operational"
PHONE_CATEGORY_PATTERNS = {
    "carconnect": re.compile(r"CarConnect|OCar|BLEService", re.IGNORECASE),
    "wpa-supplicant": re.compile(
        r"wpa_supplicant|\bp2p0\b|\bP2P-|CTRL-EVENT", re.IGNORECASE
    ),
    "p2p-service": re.compile(
        r"WifiP2pService|SupplicantP2pIfaceCallback", re.IGNORECASE
    ),
    "vendor-wifi": re.compile(
        r"OplusWfd|Oplus\w*(?:Wifi|Wlan|P2p)|"
        r"(?:vendor|HAL|driver).*(?:wifi|wlan|p2p)|"
        r"(?:wifi|wlan|p2p).*(?:vendor|HAL|driver)",
        re.IGNORECASE,
    ),
}
PHONE_HIGH_VALUE = re.compile(
    r"CarConnect|OCar|BLEService|"
    r"setPcAutonomousGo|FAST_CONNECTION|Add group with config|"
    r"BSS:\s+Add new|selected BSS|Request association|Trying to associate|"
    r"authentication with|Trying to authenticate|AUTH-REJECT|ASSOC-REJECT|"
    r"CTRL-EVENT-(?:CONNECTED|DISCONNECTED|ASSOC-REJECT|AUTH-REJECT)|"
    r"P2P-(?:DEVICE-FOUND|GROUP-|GO-|INVITATION)|Group Formation|"
    r"WPS:|4-Way Handshake|WPA:|reason=|status_code|"
    r"DHCPACK|IpClient.*(?:BOUND|PROVISIONING|SUCCESS)|IP-COMMIT|IP-ACQUIRED|"
    r"connectivityLevelFailureCode|groupRole=|OplusWfd|"
    r"SupplicantP2pIfaceCallback|WifiP2pService.*(?:connect|group|failure|state)",
    re.IGNORECASE,
)
PHONE_SCAN_NOISE = re.compile(
    r"p2p_stop_listen_for_freq|BSS:\s+(?:Start|End) scan result update|"
    r"nl80211:\s+(?:Scan|New scan results)|"
    r"CTRL-EVENT-SCAN-(?:STARTED|RESULTS)",
    re.IGNORECASE,
)
PHONE_WIFI_SUMMARY = re.compile(
    r"connectionType=FAST|connectivityLevelFailureCode=|groupRole=CLIENT|"
    r"\bsta freq=|\bp2p0\b|WifiP2p|mWifiInfo|"
    r"current(?:Network|Connection).*Wifi",
    re.IGNORECASE,
)
PHONE_TIMESTAMP = re.compile(
    r"^(?:(?P<year>\d{4})-)?(?P<month>\d{2})-(?P<day>\d{2}) "
    r"(?P<hour>\d{2}):(?P<minute>\d{2}):(?P<second>\d{2})\.(?P<millis>\d{3})"
)
CONTROLLER_TIMESTAMP = re.compile(r"^(?P<timestamp>\S+)\s+\[(?:LOG|STATE)\]")
TIMELINE_RULES = (
    ("BLE Client Info", re.compile(r"Client info:", re.IGNORECASE)),
    ("BLE Server Info 已下发", re.compile(r"Server info received|BLE GATT.*下发", re.IGNORECASE)),
    (
        "ColorOS 设置 PC Autonomous GO",
        re.compile(r"setPcAutonomousGo.*true.*(?:;\s*|freq=)(?!0\b)\d+", re.IGNORECASE),
    ),
    (
        "ColorOS 发起 freq=0 第二阶段重试",
        re.compile(r"setPcAutonomousGo.*true.*(?:;\s*|freq=)0\b", re.IGNORECASE),
    ),
    ("FAST_CONNECTION", re.compile(r"FAST_CONNECTION", re.IGNORECASE)),
    ("P2P Add group", re.compile(r"Add group with config", re.IGNORECASE)),
    (
        "发现目标 BSS",
        re.compile(r"BSS:\s+Add new.*SSID|P2P-DEVICE-FOUND", re.IGNORECASE),
    ),
    ("选中目标 BSS", re.compile(r"selected BSS", re.IGNORECASE)),
    ("发起认证", re.compile(r"Trying to authenticate|authentication with", re.IGNORECASE)),
    ("发起关联", re.compile(r"Request association|Trying to associate", re.IGNORECASE)),
    ("认证被拒绝", re.compile(r"AUTH-REJECT|CTRL-EVENT-AUTH-REJECT", re.IGNORECASE)),
    ("关联被拒绝", re.compile(r"ASSOC-REJECT|CTRL-EVENT-ASSOC-REJECT", re.IGNORECASE)),
    (
        "二层连接成功",
        re.compile(r"CTRL-EVENT-CONNECTED|P2P-GROUP-STARTED", re.IGNORECASE),
    ),
    (
        "DHCP/IP 成功",
        re.compile(
            r"DHCPACK|IpClient.*(?:BOUND|PROVISIONING_SUCCESS)|"
            r"IP-COMMIT|IP-ACQUIRED",
            re.IGNORECASE,
        ),
    ),
    ("P2P Group Formation 超时", re.compile(r"Group Formation timed out", re.IGNORECASE)),
    (
        "P2P Group Formation 失败",
        re.compile(r"P2P-GROUP-FORMATION-FAILURE|FORMATION_FAILED", re.IGNORECASE),
    ),
    (
        "手机连接 TCP 57209",
        re.compile(r"手机已连接无线认证通道", re.IGNORECASE),
    ),
)
WINDOWS_AUTH_ASSOC = re.compile(
    r"(?:received|incoming|indication).{0,80}(?:authentication|association)|"
    r"(?:authentication|association).{0,80}(?:request|frame|indication)",
    re.IGNORECASE,
)
SECRET_PATTERNS = (
    (re.compile(r"(?i)(pinCodeOrAuthentication\s*[:=]\s*)[^\s,}\]]+"), r"\1<redacted>"),
    (re.compile(r"(?i)(\bauth=')[^']*(')"), r"\1<redacted>\2"),
    (re.compile(r"(?i)(authentication=')[^']*(')"), r"\1<redacted>\2"),
    (re.compile(r'(?i)(["\'](?:psk|passphrase)["\']\s*[:=]\s*["\'])[^"\']*'), r"\1<redacted>"),
)


def _sanitize(value: str) -> str:
    for pattern, replacement in SECRET_PATTERNS:
        value = pattern.sub(replacement, value)
    return value


def _run_text(command: list[str], *, timeout: float = 30.0) -> tuple[int, str]:
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 1, f"{type(exc).__name__}: {exc}\n"
    output = completed.stdout
    if completed.stderr:
        output += "\n[stderr]\n" + completed.stderr
    return completed.returncode, _sanitize(output)


def _write_command(path: Path, command: list[str], *, timeout: float = 30.0) -> int:
    return_code, output = _run_text(command, timeout=timeout)
    path.write_text(output, encoding="utf-8")
    return return_code


def _write_filtered_command(
    path: Path,
    command: list[str],
    pattern: re.Pattern[str],
    *,
    timeout: float = 30.0,
) -> int:
    return_code, output = _run_text(command, timeout=timeout)
    lines = [line for line in output.splitlines() if pattern.search(line)]
    path.write_text(
        "\n".join(lines) + ("\n" if lines else ""),
        encoding="utf-8",
    )
    return return_code


def _find_adb(explicit: str | None) -> Path | None:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit).expanduser())
    path_adb = shutil.which("adb")
    if path_adb:
        candidates.append(Path(path_adb))
    candidates.extend(
        (
            WORK_DIR / "toolchain" / "android" / "platform-tools" / "adb.exe",
            Path(os.environ.get("LOCALAPPDATA", ""))
            / "Android"
            / "Sdk"
            / "platform-tools"
            / "adb.exe",
        )
    )
    return next((candidate.resolve() for candidate in candidates if candidate.is_file()), None)


def _is_admin() -> bool:
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except (AttributeError, OSError):
        return False


def _classify_phone_line(line: str) -> tuple[str, ...]:
    return tuple(
        name for name, pattern in PHONE_CATEGORY_PATTERNS.items() if pattern.search(line)
    )


def _is_high_value_phone_line(line: str) -> bool:
    if PHONE_SCAN_NOISE.search(line):
        return False
    return bool(PHONE_HIGH_VALUE.search(line))


def _phone_datetime(
    line: str,
    *,
    capture_started: datetime,
) -> datetime | None:
    match = PHONE_TIMESTAMP.match(line)
    if match is None:
        return None
    year = int(match.group("year") or capture_started.year)
    timezone = capture_started.tzinfo
    value = datetime(
        year,
        int(match.group("month")),
        int(match.group("day")),
        int(match.group("hour")),
        int(match.group("minute")),
        int(match.group("second")),
        int(match.group("millis")) * 1000,
        tzinfo=timezone,
    )
    if match.group("year") is None:
        candidates = (value - timedelta(days=365), value, value + timedelta(days=365))
        value = min(candidates, key=lambda item: abs(item - capture_started))
    return value


def _controller_datetime(line: str) -> datetime | None:
    match = CONTROLLER_TIMESTAMP.match(line)
    if match is None:
        return None
    try:
        return datetime.fromisoformat(match.group("timestamp"))
    except ValueError:
        return None


def _timeline_entries(
    phone_lines: Iterable[str],
    controller_lines: Iterable[str],
    *,
    capture_started: datetime,
) -> list[tuple[datetime, str, str, str]]:
    entries: list[tuple[datetime, str, str, str]] = []
    for source, lines in (("PHONE", phone_lines), ("WINDOWS", controller_lines)):
        for line in lines:
            timestamp = (
                _phone_datetime(line, capture_started=capture_started)
                if source == "PHONE"
                else _controller_datetime(line)
            )
            if timestamp is None:
                continue
            for label, pattern in TIMELINE_RULES:
                if pattern.search(line):
                    entries.append((timestamp, source, label, line.strip()))
                    break
    entries.sort(key=lambda item: item[0])
    return entries


def _diagnosis(
    phone_text: str,
    controller_text: str,
    *,
    windows_text: str = "",
    tcp_text: str = "",
) -> dict[str, object]:
    combined_phone = f"{phone_text}\n{controller_text}"
    flags = {
        "ble_server_info": bool(
            re.search(r"Server info received|BLE GATT.*下发", combined_phone, re.IGNORECASE)
        ),
        "found_bss": bool(
            re.search(
                r"BSS:\s+Add new.*SSID|selected BSS|P2P-DEVICE-FOUND",
                phone_text,
                re.IGNORECASE,
            )
        ),
        "association_requested": bool(
            re.search(
                r"Request association|Trying to associate",
                phone_text,
                re.IGNORECASE,
            )
        ),
        "auth_reject": bool(
            re.search(r"AUTH-REJECT|CTRL-EVENT-AUTH-REJECT", phone_text, re.IGNORECASE)
        ),
        "assoc_reject": bool(
            re.search(r"ASSOC-REJECT|CTRL-EVENT-ASSOC-REJECT", phone_text, re.IGNORECASE)
        ),
        "connected": bool(
            re.search(
                r"CTRL-EVENT-CONNECTED|P2P-GROUP-STARTED",
                phone_text,
                re.IGNORECASE,
            )
        ),
        "dhcp": bool(
            re.search(
                r"DHCPACK|IpClient.*(?:BOUND|PROVISIONING_SUCCESS)|"
                r"IP-COMMIT|IP-ACQUIRED",
                phone_text,
                re.IGNORECASE,
            )
        ),
        "formation_timeout": bool(
            re.search(
                r"Group Formation timed out|P2P-GROUP-FORMATION-FAILURE|"
                r"FORMATION_FAILED",
                phone_text,
                re.IGNORECASE,
            )
        ),
        "retry_freq_zero": bool(
            re.search(
                r"setPcAutonomousGo.*true.*(?:;\s*|freq=)0\b",
                phone_text,
                re.IGNORECASE,
            )
        ),
        "tcp_57209": bool(
            re.search(r"手机已连接无线认证通道", controller_text, re.IGNORECASE)
            or bool(
                re.search(
                    r"\b57209\b.*\bEstablished\b|\bEstablished\b.*\b57209\b",
                    tcp_text,
                    re.IGNORECASE,
                )
            )
        ),
        "windows_auth_assoc_evidence": bool(WINDOWS_AUTH_ASSOC.search(windows_text)),
    }

    if flags["tcp_57209"]:
        conclusion = "Wi-Fi/P2P、IP 和 TCP 57209 均已到达，可以继续检查 ICCOA 认证。"
        next_step = "检查认证报文和 PIN/配对数据，不再回头修改 BLE 或 Wi-Fi 参数。"
    elif flags["dhcp"]:
        conclusion = "手机已取得 IP，但尚未连接 TCP 57209。"
        next_step = "检查 Windows 防火墙、监听地址，以及手机到 192.168.137.1:57209 的路由。"
    elif flags["connected"]:
        conclusion = "手机已完成二层关联，但尚未证明 DHCP/IP 成功。"
        next_step = "检查 Windows WFD/ICS 的 DHCP、接口地址和邻居表。"
    elif flags["auth_reject"] or flags["assoc_reject"]:
        rejected = "认证" if flags["auth_reject"] else "关联"
        conclusion = f"手机已发现目标 BSS，但收到明确的{rejected}拒绝。"
        next_step = "从 Windows ETW/WDI 中查拒绝状态码，定位 QCA9377 驱动或 GO 配置限制。"
    elif flags["association_requested"] and flags["formation_timeout"]:
        conclusion = (
            "手机已发现并选中目标 BSS，也发起了关联；没有明确 auth/assoc reject，"
            "最终停在 ASSOCIATING/Group Formation 超时。"
        )
        if flags["windows_auth_assoc_evidence"]:
            next_step = (
                "Windows ETW 中出现认证/关联请求证据；检查相邻 ETW 事件的状态码，"
                "判断 Windows/QCA9377 为何未完成响应。"
            )
        else:
            next_step = (
                "优先检查 windows-wlan-etw.txt：确认 Windows 是否实际收到手机的"
                "认证/关联帧，再决定修改 GO 还是更换可控 P2P 网卡。"
            )
    elif flags["found_bss"]:
        conclusion = "手机发现了目标 BSS，但日志中没有看到关联请求。"
        next_step = "检查 Server Info 的频率、GO/BSSID 和 ColorOS FAST_CONNECTION 配置。"
    else:
        conclusion = "手机日志中没有证明目标 BSS 被发现。"
        next_step = "先检查 Windows GO 是否启动、实际信道和下发的 SSID/BSSID。"

    return {
        **flags,
        "conclusion": conclusion,
        "next_step": next_step,
    }


def _format_summary(diagnosis: dict[str, object]) -> str:
    labels = (
        ("ble_server_info", "BLE/Server Info"),
        ("found_bss", "发现目标 BSS"),
        ("association_requested", "发起关联"),
        ("auth_reject", "收到 AUTH reject"),
        ("assoc_reject", "收到 ASSOC reject"),
        ("connected", "二层连接成功"),
        ("dhcp", "DHCP/IP 成功"),
        ("tcp_57209", "到达 TCP 57209"),
        ("formation_timeout", "Group Formation 超时/失败"),
        ("retry_freq_zero", "出现 freq=0 第二阶段重试"),
        ("windows_auth_assoc_evidence", "Windows ETW 出现 auth/assoc 请求证据"),
    )
    lines = ["ICCOA 无线诊断结论", ""]
    for key, label in labels:
        lines.append(f"[{'YES' if diagnosis[key] else 'NO '}] {label}")
    lines.extend(
        (
            "",
            f"结论：{diagnosis['conclusion']}",
            f"下一步：{diagnosis['next_step']}",
            "",
            "说明：只有出现手机连接认证通道或 Windows Established 57209，才算到达 TCP 阶段。",
        )
    )
    return "\n".join(lines) + "\n"


def _powershell_single_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _elevated_launch_command(arguments: list[str]) -> str:
    python = str(PROJECT_DIR / ".venv-test" / "Scripts" / "python.exe")
    if not Path(python).is_file():
        python = sys.executable
    script = str(Path(__file__).resolve())
    command = (
        f"Set-Location -LiteralPath {_powershell_single_quote(str(PROJECT_DIR))}; "
        "$env:PYTHONPATH = Join-Path (Get-Location) 'src'; "
        f"& {_powershell_single_quote(python)} -u {_powershell_single_quote(script)} "
        + " ".join(_powershell_single_quote(argument) for argument in arguments)
    )
    encoded = base64.b64encode(command.encode("utf-16-le")).decode("ascii")
    return (
        "Start-Process powershell.exe -Verb RunAs "
        f"-ArgumentList '-NoExit','-EncodedCommand','{encoded}'"
    )


def _adb_has_device(adb: Path) -> tuple[bool, str]:
    return_code, output = _run_text([str(adb), "devices", "-l"])
    if return_code != 0:
        return False, output
    lines = [
        line.strip()
        for line in output.splitlines()[1:]
        if line.strip() and "\tdevice" in line
    ]
    return bool(lines), output


class SessionCapture:
    def __init__(
        self,
        output_dir: Path,
        adb: Path | None,
        *,
        full_logcat: bool = False,
    ) -> None:
        self.output_dir = output_dir
        self.adb = adb
        self.full_logcat = full_logcat
        self.started_at = datetime.now().astimezone()
        self._logcat: subprocess.Popen[str] | None = None
        self._logcat_thread: threading.Thread | None = None
        self._phone_signal_file = None
        self._phone_category_files: dict[str, object] = {}
        self._full_logcat_file = None
        self._etw_started = False

    def _adb(self, *args: str) -> list[str]:
        if self.adb is None:
            raise RuntimeError("ADB is unavailable")
        return [str(self.adb), *args]

    def _write_snapshot(self, prefix: str) -> None:
        _write_command(
            self.output_dir / f"windows-wlan-{prefix}.txt",
            ["netsh", "wlan", "show", "interfaces"],
        )
        _write_command(
            self.output_dir / f"windows-adapters-{prefix}.txt",
            [
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Get-NetAdapter -IncludeHidden | "
                "Select-Object Name,InterfaceDescription,Status,MacAddress,"
                "LinkSpeed,ifIndex,InterfaceGuid | Format-List",
            ],
        )
        _write_command(
            self.output_dir / f"windows-ip-config-{prefix}.txt",
            [
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Get-NetIPConfiguration -All | "
                "Select-Object InterfaceAlias,InterfaceDescription,InterfaceIndex,"
                "NetProfile,IPv4Address,IPv4DefaultGateway,DNSServer | Format-List",
            ],
        )
        _write_command(
            self.output_dir / f"windows-neighbors-{prefix}.txt",
            [
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Get-NetNeighbor -ErrorAction SilentlyContinue | "
                "Sort-Object InterfaceIndex,IPAddress | "
                "Select-Object ifIndex,InterfaceAlias,IPAddress,LinkLayerAddress,State | "
                "Format-Table -AutoSize",
            ],
        )
        _write_command(
            self.output_dir / f"windows-tcp-57209-{prefix}.txt",
            [
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Get-NetTCPConnection -LocalPort 57209 -ErrorAction SilentlyContinue | "
                "Select-Object LocalAddress,LocalPort,RemoteAddress,RemotePort,State,"
                "OwningProcess | Format-Table -AutoSize",
            ],
        )
        _write_command(
            self.output_dir / f"windows-wifi-direct-adapters-{prefix}.txt",
            [
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Get-NetAdapter -IncludeHidden | Where-Object { "
                "$_.InterfaceDescription -match 'Wi-Fi Direct|Virtual|Hosted|P2P' "
                "-or $_.Name -match 'Wi-Fi Direct|Virtual|Hosted|P2P' } | "
                "Select-Object Name,InterfaceDescription,Status,MacAddress,LinkSpeed,"
                "ifIndex,InterfaceGuid | Format-List",
            ],
        )
        if self.adb is None:
            return
        _write_command(
            self.output_dir / f"phone-wifip2p-{prefix}.txt",
            self._adb("shell", "dumpsys", "wifip2p"),
            timeout=60.0,
        )
        _write_filtered_command(
            self.output_dir / f"phone-wifi-summary-{prefix}.txt",
            self._adb("shell", "dumpsys", "wifi"),
            PHONE_WIFI_SUMMARY,
            timeout=60.0,
        )

    def _stream_logcat(self) -> None:
        process = self._logcat
        output = self._phone_signal_file
        if process is None or process.stdout is None or output is None:
            return
        for line in process.stdout:
            sanitized = _sanitize(line)
            if self._full_logcat_file is not None:
                self._full_logcat_file.write(sanitized)
                self._full_logcat_file.flush()
            if not _is_high_value_phone_line(sanitized):
                continue
            output.write(sanitized)
            output.flush()
            for category in _classify_phone_line(sanitized):
                category_file = self._phone_category_files.get(category)
                if category_file is not None:
                    category_file.write(sanitized)
                    category_file.flush()

    def _start_etw(self) -> None:
        status_path = self.output_dir / "windows-etw-status.txt"
        if not _is_admin():
            status_path.write_text(
                "Skipped: WLAN ETW capture requires an elevated PowerShell session.\n",
                encoding="utf-8",
            )
            return
        trace_path = self.output_dir / "windows-wlan.etl"
        return_code, output = _run_text(
            [
                "netsh",
                "trace",
                "start",
                "scenario=WLAN",
                "capture=yes",
                "persistent=no",
                "report=no",
                "maxSize=128",
                f"tracefile={trace_path}",
            ],
            timeout=60.0,
        )
        status_path.write_text(output, encoding="utf-8")
        self._etw_started = return_code == 0

    def start(self) -> None:
        self.output_dir.mkdir(parents=True, exist_ok=False)
        metadata = {
            "started_at": self.started_at.isoformat(),
            "computer": platform.node(),
            "python": sys.version,
            "adb": str(self.adb) if self.adb else None,
            "elevated": _is_admin(),
            "timezone": str(self.started_at.tzinfo),
            "full_logcat": self.full_logcat,
        }
        (self.output_dir / "metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        _write_command(
            self.output_dir / "windows-wlan-capabilities.txt",
            ["netsh", "wlan", "show", "wirelesscapabilities"],
        )
        if self.adb is not None:
            _write_command(
                self.output_dir / "phone-device.txt",
                self._adb("devices", "-l"),
            )
            self._phone_signal_file = (self.output_dir / "phone-signals.txt").open(
                "w", encoding="utf-8", newline=""
            )
            self._phone_category_files = {
                category: (
                    self.output_dir / f"phone-{category}.txt"
                ).open("w", encoding="utf-8", newline="")
                for category in PHONE_CATEGORY_PATTERNS
            }
            if self.full_logcat:
                self._full_logcat_file = (
                    self.output_dir / "phone-logcat-full.txt"
                ).open("w", encoding="utf-8", newline="")
            self._logcat = subprocess.Popen(
                self._adb("logcat", "-v", "threadtime", "-T", "1"),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
            self._logcat_thread = threading.Thread(
                target=self._stream_logcat,
                name="CarLinkPhoneLogcat",
                daemon=True,
            )
            self._logcat_thread.start()
        self._write_snapshot("before")
        self._start_etw()

    def _stop_logcat(self) -> None:
        process = self._logcat
        self._logcat = None
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5.0)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5.0)
        thread = self._logcat_thread
        self._logcat_thread = None
        if thread is not None:
            thread.join(timeout=2.0)
        output = self._phone_signal_file
        self._phone_signal_file = None
        if output is not None:
            output.close()
        for category_file in self._phone_category_files.values():
            category_file.close()
        self._phone_category_files.clear()
        full_output = self._full_logcat_file
        self._full_logcat_file = None
        if full_output is not None:
            full_output.close()

    def _export_wlan_events(self) -> None:
        start_value = self.started_at.isoformat()
        script = (
            f"$start=[DateTimeOffset]::Parse('{start_value}').LocalDateTime;"
            f"Get-WinEvent -FilterHashtable @{{LogName='{WLAN_EVENT_LOG}';StartTime=$start}} "
            "-ErrorAction SilentlyContinue | Sort-Object TimeCreated | "
            "Select-Object TimeCreated,Id,LevelDisplayName,Message | Format-List"
        )
        _write_command(
            self.output_dir / "windows-wlan-events.txt",
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
            timeout=60.0,
        )

    def _convert_etw(self) -> None:
        trace_path = self.output_dir / "windows-wlan.etl"
        if not trace_path.exists():
            return
        return_code, output = _run_text(
            [
                "netsh",
                "trace",
                "convert",
                f"input={trace_path}",
                f"output={self.output_dir / 'windows-wlan-etw'}",
                "dump=TXT",
                "report=no",
                "overwrite=yes",
            ],
            timeout=180.0,
        )
        with (self.output_dir / "windows-etw-status.txt").open(
            "a", encoding="utf-8"
        ) as status:
            status.write("\n--- convert ---\n")
            status.write(output)
        if return_code != 0:
            return
        candidates = sorted(self.output_dir.glob("windows-wlan-etw*.txt"))
        if not candidates:
            return
        canonical = self.output_dir / "windows-wlan-etw.txt"
        source = candidates[0]
        if source != canonical:
            canonical.write_text(source.read_text(encoding="utf-8", errors="replace"), encoding="utf-8")

    def _write_analysis(self) -> None:
        phone_path = self.output_dir / "phone-signals.txt"
        controller_path = self.output_dir / "controller.log"
        phone_text = (
            phone_path.read_text(encoding="utf-8", errors="replace")
            if phone_path.exists()
            else ""
        )
        controller_text = (
            controller_path.read_text(encoding="utf-8", errors="replace")
            if controller_path.exists()
            else ""
        )
        windows_parts = []
        for name in ("windows-wlan-etw.txt", "windows-wlan-events.txt"):
            path = self.output_dir / name
            if path.exists():
                windows_parts.append(path.read_text(encoding="utf-8", errors="replace"))
        tcp_path = self.output_dir / "windows-tcp-57209-after.txt"
        tcp_text = (
            tcp_path.read_text(encoding="utf-8", errors="replace")
            if tcp_path.exists()
            else ""
        )
        diagnosis = _diagnosis(
            phone_text,
            controller_text,
            windows_text="\n".join(windows_parts),
            tcp_text=tcp_text,
        )
        (self.output_dir / "diagnosis.json").write_text(
            json.dumps(diagnosis, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        (self.output_dir / "summary.txt").write_text(
            _format_summary(diagnosis),
            encoding="utf-8",
        )
        entries = _timeline_entries(
            phone_text.splitlines(),
            controller_text.splitlines(),
            capture_started=self.started_at,
        )
        timeline_lines = [
            f"{timestamp.astimezone().isoformat(timespec='milliseconds')} "
            f"[{source}] {label}: {line}"
            for timestamp, source, label, line in entries
        ]
        (self.output_dir / "timeline.txt").write_text(
            "\n".join(timeline_lines) + ("\n" if timeline_lines else ""),
            encoding="utf-8",
        )
        (self.output_dir / "time-basis.txt").write_text(
            "controller.log: Windows 本地时区 ISO 8601\n"
            "phone-*.txt: Android threadtime；timeline.txt 已按采集日期补全年份，"
            f"并使用 Windows 本地时区 {self.started_at.tzinfo}\n"
            "windows-wlan-events.txt / windows-wlan-etw.txt: Windows 本地时间\n",
            encoding="utf-8",
        )

    def stop(self) -> None:
        self._stop_logcat()
        if self._etw_started:
            return_code, output = _run_text(["netsh", "trace", "stop"], timeout=120.0)
            with (self.output_dir / "windows-etw-status.txt").open("a", encoding="utf-8") as status:
                status.write("\n--- stop ---\n")
                status.write(output)
            self._etw_started = return_code != 0
            self._convert_etw()
        self._write_snapshot("after")
        self._export_wlan_events()
        self._write_analysis()
        metadata_path = self.output_dir / "metadata.json"
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        metadata["finished_at"] = datetime.now().astimezone().isoformat()
        metadata_path.write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run the wireless ICCOA receiver with synchronized phone/Windows diagnostics"
    )
    parser.add_argument("--seconds", type=int, default=150)
    parser.add_argument(
        "--network-mode",
        choices=("softap", "wfd", "wfd-softap"),
        default="wfd",
        help="Windows network backend and advertised ICCOA type",
    )
    parser.add_argument("--adb", help="Path to adb.exe; auto-detected when omitted")
    parser.add_argument("--output", type=Path, help="Session output directory")
    parser.add_argument(
        "--no-capture",
        action="store_true",
        help="Run only the receiver without phone and Windows diagnostic capture",
    )
    parser.add_argument(
        "--full-logcat",
        action="store_true",
        help="Also save the complete Android logcat; default saves only high-value lines",
    )
    args = parser.parse_args()

    if not args.no_capture and not _is_admin():
        print(
            "[ERROR] 无线底层诊断必须在管理员 PowerShell 中运行，否则无法抓取 WLAN/WDI ETW。",
            file=sys.stderr,
            flush=True,
        )
        print("[ADMIN] 请复制并运行下面的命令：", file=sys.stderr, flush=True)
        print(_elevated_launch_command(sys.argv[1:]), file=sys.stderr, flush=True)
        return 2

    adb = _find_adb(args.adb)
    if not args.no_capture:
        if adb is None:
            print(
                "[ERROR] 未找到 adb.exe；当前诊断需要手机 ADB 日志。",
                file=sys.stderr,
                flush=True,
            )
            return 3
        has_device, devices_output = _adb_has_device(adb)
        if not has_device:
            print(
                "[ERROR] ADB 未检测到已授权手机。请重新插拔手机、开启 USB 调试并确认授权。",
                file=sys.stderr,
                flush=True,
            )
            print(devices_output, file=sys.stderr, flush=True)
            return 3

    timestamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    output_dir = (args.output or DEFAULT_CAPTURE_ROOT / timestamp).resolve()
    capture = (
        None
        if args.no_capture
        else SessionCapture(output_dir, adb, full_logcat=args.full_logcat)
    )
    session_log = None
    log_lock = threading.Lock()

    if capture is not None:
        capture.start()
        session_log = (output_dir / "controller.log").open("w", encoding="utf-8", newline="")
        print(f"[CAPTURE] {output_dir}", flush=True)

    def emit(kind: str, value: str) -> None:
        line = f"{datetime.now().astimezone().isoformat(timespec='milliseconds')} [{kind}] {value}"
        print(line, flush=True)
        if session_log is not None:
            with log_lock:
                session_log.write(_sanitize(line) + "\n")
                session_log.flush()

    controller = WirelessCarLinkController(
        load_identity(),
        lambda value: emit("LOG", value),
        lambda value: emit("STATE", value),
        network_mode=args.network_mode,
    )
    controller.start()
    deadline = time.monotonic() + args.seconds
    try:
        while controller.running and time.monotonic() < deadline:
            time.sleep(0.25)
    except KeyboardInterrupt:
        pass
    finally:
        controller.stop()
        time.sleep(2.0)
        if session_log is not None:
            session_log.close()
        if capture is not None:
            capture.stop()
            print(f"[CAPTURE] complete: {output_dir}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
