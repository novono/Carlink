import struct
import unittest
from unittest.mock import MagicMock, patch

from open_carlink_pc.raw_ble import (
    IccoaGattServer,
    RawBleError,
    RawHciController,
    uuid128_le,
)
from open_carlink_pc.wireless import ICCOA_SHARE_SERVICE_UUID


class RawBleGattTests(unittest.TestCase):
    @patch("open_carlink_pc.raw_ble.usb.util.claim_interface")
    @patch("open_carlink_pc.raw_ble.usb.core.find")
    @patch("open_carlink_pc.raw_ble.libusb_package.get_libusb1_backend")
    def test_raw_hci_selects_supported_realtek_controller(
        self,
        backend,
        find,
        claim_interface,
    ) -> None:
        device = MagicMock()
        backend.return_value = object()
        find.side_effect = [None, device]
        controller = RawHciController()

        controller.open()

        self.assertIs(controller.device, device)
        self.assertEqual(controller.device_name, "Realtek 8821CE Bluetooth")
        claim_interface.assert_called_once_with(device, 0)

    @patch("open_carlink_pc.raw_ble.usb.util.dispose_resources")
    @patch("open_carlink_pc.raw_ble.usb.core.find")
    @patch("open_carlink_pc.raw_ble.libusb_package.get_libusb1_backend")
    def test_raw_hci_reports_native_driver_as_unavailable(
        self,
        backend,
        find,
        dispose_resources,
    ) -> None:
        device = MagicMock()
        device.set_configuration.side_effect = NotImplementedError(
            "Operation not supported"
        )
        backend.return_value = object()
        find.side_effect = [None, device]
        controller = RawHciController()

        with self.assertRaisesRegex(RawBleError, "无法占用 WinUSB HCI"):
            controller.open()

        dispose_resources.assert_called_once_with(device)

    def test_uuid_uses_full_bluetooth_little_endian_order(self) -> None:
        self.assertEqual(
            uuid128_le(ICCOA_SHARE_SERVICE_UUID),
            bytes.fromhex("00917856341284ba8a4f359950c8bc2a"),
        )

    def test_mtu_and_primary_service_discovery(self) -> None:
        server = IccoaGattServer()
        self.assertEqual(server.handle(bytes.fromhex("020002")).response, bytes.fromhex("03f700"))

        first = server.handle(bytes.fromhex("100100ffff0028")).response
        self.assertEqual(first, bytes.fromhex("1106010005000018060006000118"))

        second = server.handle(bytes.fromhex("100700ffff0028")).response
        self.assertIsNotNone(second)
        assert second is not None
        self.assertEqual(second[:6], bytes.fromhex("111407000c00"))
        self.assertEqual(second[6:], uuid128_le(ICCOA_SHARE_SERVICE_UUID))

    def test_characteristic_discovery_and_client_write(self) -> None:
        server = IccoaGattServer()
        server.handle(bytes.fromhex("02f700"))
        response = server.handle(bytes.fromhex("0807000c000328")).response
        self.assertIsNotNone(response)
        assert response is not None
        self.assertEqual(response[:2], bytes((0x09, 21)))
        self.assertEqual(struct.unpack_from("<H", response, 2)[0], 8)
        self.assertEqual(struct.unpack_from("<H", response, 23)[0], 10)

        payload = b'{"pinCodeOrAuthentication":"123456"}'
        result = server.handle(b"\x12\x09\x00" + payload)
        self.assertEqual(result.response, b"\x13")
        self.assertEqual(result.client_info, payload)

    def test_server_info_is_sent_as_indication(self) -> None:
        server = IccoaGattServer()
        server.negotiated_mtu = 247
        indication = server.server_info_indication(b'{"ssid":"XHCODING"}')
        self.assertEqual(indication[:3], bytes.fromhex("1d0b00"))
        self.assertIn(b"XHCODING", indication)


if __name__ == "__main__":
    unittest.main()
