from __future__ import annotations

import asyncio
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

from open_carlink_pc.config import CarIdentity
from open_carlink_pc.wireless import HotspotInfo, WirelessError
from open_carlink_pc.wireless_controller import WirelessCarLinkController
from tools.wireless_diagnostic import (
    _classify_phone_line,
    _diagnosis,
    _format_summary,
    _is_high_value_phone_line,
    _sanitize,
    _timeline_entries,
)


class WirelessDiagnosticTests(unittest.TestCase):
    def test_controller_rejects_unknown_network_mode(self) -> None:
        identity = CarIdentity(
            car_id="001122334455",
            model_id="001122334455",
            protocol_version="1.2",
            short_name="PC CarLink",
            vendor_data="0000",
        )

        with self.assertRaisesRegex(ValueError, "不支持的无线网络模式"):
            WirelessCarLinkController(
                identity,
                lambda _value: None,
                lambda _value: None,
                network_mode="unknown",  # type: ignore[arg-type]
            )

        with self.assertRaisesRegex(ValueError, "不支持的 BLE 后端"):
            WirelessCarLinkController(
                identity,
                lambda _value: None,
                lambda _value: None,
                ble_mode="unknown",  # type: ignore[arg-type]
            )

    def test_auto_ble_falls_back_to_winrt(self) -> None:
        identity = CarIdentity(
            car_id="001122334455",
            model_id="001122334455",
            protocol_version="1.2",
            short_name="PC CarLink",
            vendor_data="0000",
        )
        logs: list[str] = []
        controller = WirelessCarLinkController(
            identity,
            logs.append,
            lambda _value: None,
        )
        hotspot = HotspotInfo(
            ssid="DIRECT-AA-OpenCarLink",
            passphrase="secret123",
            address="192.168.137.1",
            mac_address="02:00:00:00:00:00",
        )

        class FailedRawBle:
            def __init__(self, *_args, **_kwargs) -> None:
                pass

            async def start(self) -> None:
                raise WirelessError("raw unavailable")

            async def stop(self) -> None:
                pass

        class WorkingWinRtBle:
            def __init__(self, *_args, **_kwargs) -> None:
                pass

            async def start(self) -> None:
                pass

            async def stop(self) -> None:
                pass

        async def run_fallback() -> tuple[object, str]:
            with (
                patch(
                    "open_carlink_pc.wireless_controller.IccoaRawBlePeripheral",
                    FailedRawBle,
                ),
                patch(
                    "open_carlink_pc.wireless_controller.IccoaBlePeripheral",
                    WorkingWinRtBle,
                ),
            ):
                return await controller._start_ble(hotspot)

        peripheral, backend = asyncio.run(run_fallback())

        self.assertIsInstance(peripheral, WorkingWinRtBle)
        self.assertEqual(backend, "winrt")
        self.assertTrue(any("改用 Windows BLE" in message for message in logs))

    def test_sanitize_redacts_phone_authentication_variants(self) -> None:
        source = (
            "pinCodeOrAuthentication: abc12345 "
            "authentication='secret-one' auth='secret-two'"
        )

        sanitized = _sanitize(source)

        self.assertNotIn("abc12345", sanitized)
        self.assertNotIn("secret-one", sanitized)
        self.assertNotIn("secret-two", sanitized)
        self.assertEqual(sanitized.count("<redacted>"), 3)

    def test_sanitize_redacts_json_network_credentials(self) -> None:
        source = '{"psk":"wifi-secret","passphrase":"other-secret"}'

        sanitized = _sanitize(source)

        self.assertNotIn("wifi-secret", sanitized)
        self.assertNotIn("other-secret", sanitized)
        self.assertEqual(sanitized.count("<redacted>"), 2)

    def test_phone_log_classification_and_noise_filter(self) -> None:
        service = (
            "08-04 21:24:17.151 22069 22106 D CarConnect: "
            "[WifiP2pService] setPcAutonomousGo value true; 2462"
        )
        vendor = "08-04 21:24:17.152 2235 9194 D OplusWfd: freq=2462"
        noise = (
            "08-04 21:24:17.158 28877 28877 D wpa_supplicant: "
            "P2P: p2p_stop_listen_for_freq(freq=0)"
        )

        self.assertEqual(
            _classify_phone_line(service),
            ("carconnect", "p2p-service"),
        )
        self.assertEqual(_classify_phone_line(vendor), ("vendor-wifi",))
        self.assertTrue(_is_high_value_phone_line(service))
        self.assertTrue(_is_high_value_phone_line(vendor))
        self.assertFalse(_is_high_value_phone_line(noise))

    def test_timeline_uses_local_capture_date_for_android_threadtime(self) -> None:
        started = datetime(2026, 8, 4, 21, 24, tzinfo=timezone.utc)
        phone = [
            "08-04 21:24:17.223 1 1 D wpa_supplicant: "
            "p2p0: Request association with 4a:d5:7a:ce:c5:bf",
            "08-04 21:24:27.170 1 1 D wpa_supplicant: "
            "P2P: Group Formation timed out",
        ]

        entries = _timeline_entries(phone, [], capture_started=started)

        self.assertEqual([entry[2] for entry in entries], [
            "发起关联",
            "P2P Group Formation 超时",
        ])
        self.assertEqual(entries[0][0].year, 2026)
        self.assertEqual(entries[0][0].month, 8)
        self.assertEqual(entries[0][0].day, 4)

    def test_diagnosis_identifies_association_timeout_without_reject(self) -> None:
        phone = "\n".join(
            (
                "Server info received",
                "p2p0: BSS: Add new id 3 BSSID aa:bb SSID 'XHCODING 1868'",
                "p2p0: Request association with aa:bb",
                "P2P: Group Formation timed out",
                "setPcAutonomousGo to : true,freq=0",
            )
        )

        result = _diagnosis(phone, "")

        self.assertTrue(result["found_bss"])
        self.assertTrue(result["association_requested"])
        self.assertTrue(result["formation_timeout"])
        self.assertTrue(result["retry_freq_zero"])
        self.assertFalse(result["auth_reject"])
        self.assertFalse(result["assoc_reject"])
        self.assertFalse(result["tcp_57209"])
        self.assertIn("ASSOCIATING", result["conclusion"])

    def test_diagnosis_does_not_treat_listener_as_tcp_connection(self) -> None:
        result = _diagnosis("", "无线认证服务已监听 TCP 57209")

        self.assertFalse(result["tcp_57209"])
        self.assertIn("[NO ] 到达 TCP 57209", _format_summary(result))

    def test_diagnosis_detects_established_tcp_snapshot(self) -> None:
        tcp = (
            "LocalAddress LocalPort RemoteAddress RemotePort State\n"
            "0.0.0.0      57209     192.168.137.66 45678 Established"
        )

        result = _diagnosis("", "", tcp_text=tcp)

        self.assertTrue(result["tcp_57209"])
        self.assertIn("TCP 57209 均已到达", result["conclusion"])


if __name__ == "__main__":
    unittest.main()
