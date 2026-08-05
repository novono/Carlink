import json
import unittest
from unittest.mock import patch

from open_carlink_pc.config import CarIdentity
from open_carlink_pc.wireless import (
    HotspotInfo,
    WIRELESS_TYPE_BOTH,
    WIRELESS_TYPE_SOFT_AP,
    _new_group_owner_interface,
    _wifi_channel_to_frequency,
    _wifi_direct_ssid,
    build_car_name_service_data,
    build_primary_service_data,
    build_server_info,
    parse_phone_ble_info,
    phone_address,
)


class WirelessProtocolTests(unittest.TestCase):
    def test_wifi_channel_to_frequency(self) -> None:
        self.assertEqual(_wifi_channel_to_frequency(1), 2412)
        self.assertEqual(_wifi_channel_to_frequency(11), 2462)
        self.assertEqual(_wifi_channel_to_frequency(36), 5180)

    def test_wifi_direct_ssid_uses_standard_prefix(self) -> None:
        ssid = _wifi_direct_ssid()
        self.assertTrue(ssid.startswith("DIRECT-"))
        self.assertTrue(ssid.endswith("-OpenCarLink"))
        self.assertLessEqual(len(ssid.encode("utf-8")), 32)

    @patch("open_carlink_pc.wireless._network_interface_details")
    def test_group_owner_interface_is_newly_addressed_interface(self, interfaces) -> None:
        interfaces.return_value = [
            ("Ethernet", "10.11.0.42", "74:86:e2:18:e0:e6"),
            ("Local Area Connection* 11", "192.168.137.1", "4a:d5:7a:ce:c5:bf"),
        ]
        self.assertEqual(
            _new_group_owner_interface({"Ethernet": "10.11.0.42"}),
            ("Local Area Connection* 11", "192.168.137.1", "4a:d5:7a:ce:c5:bf"),
        )

    def test_primary_service_data_matches_coloros_layout(self) -> None:
        identity = CarIdentity(
            car_id="010203040506",
            model_id="11223344",
            protocol_version="1.2",
            vendor_data="aabb",
        )
        payload = build_primary_service_data(identity, serial=7)
        self.assertEqual(len(payload), 15)
        self.assertEqual(
            payload,
            bytes.fromhex("01020701020304050611223344aabb"),
        )

    def test_phone_ble_info_defaults_and_requested_type(self) -> None:
        info = parse_phone_ble_info(
            json.dumps(
                {
                    "id": "phone-id",
                    "name": "OPPO",
                    "model": "PKT110",
                    "band": 2,
                    "mac": "00:11:22:33:44:55",
                    "pinCodeOrAuthentication": "123456",
                    "type": WIRELESS_TYPE_BOTH,
                }
            ).encode()
        )
        self.assertEqual(info.model, "PKT110")
        self.assertEqual(info.pin_code, "123456")
        self.assertEqual(info.requested_type, WIRELESS_TYPE_BOTH)
        self.assertEqual(info.channel, 0)

    def test_car_name_is_zero_padded_to_fixed_scan_response_width(self) -> None:
        self.assertEqual(
            build_car_name_service_data("PC CarLink"),
            b"PC CarLink" + (b"\0" * 6),
        )

    def test_car_name_is_utf8_safely_truncated(self) -> None:
        result = build_car_name_service_data("这是一个非常长的车机名称")
        self.assertEqual(len(result), 16)
        self.assertTrue(result.rstrip(b"\0").decode("utf-8").endswith("…"))

    def test_server_info_selects_soft_ap(self) -> None:
        hotspot = HotspotInfo(
            ssid="OpenCarLink",
            passphrase="secret123",
            address="192.168.137.1",
            mac_address="02:11:22:33:44:55",
            frequency=2412,
        )
        value = json.loads(build_server_info(hotspot, "PC CarLink"))
        self.assertEqual(value["ssid"], "OpenCarLink")
        self.assertEqual(value["type"], WIRELESS_TYPE_SOFT_AP)
        self.assertEqual(value["port"], 0)

    def test_phone_address_prefers_valid_confirmed_address(self) -> None:
        self.assertEqual(
            phone_address("192.168.137.23:7236", "192.168.137.20"),
            "192.168.137.23",
        )
        self.assertEqual(
            phone_address("not-an-address", "192.168.137.20"),
            "192.168.137.20",
        )


if __name__ == "__main__":
    unittest.main()
