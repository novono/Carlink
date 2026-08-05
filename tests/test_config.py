import base64
import unittest

from open_carlink_pc.config import CarIdentity


class CarIdentityTests(unittest.TestCase):
    def test_serial_matches_carlink_layout(self) -> None:
        identity = CarIdentity(
            car_id="010203040506",
            model_id="00000000",
            short_name="PC CarLink",
            protocol_version="1.2",
            vendor_data="0000",
        )
        expected_pin = base64.b64encode(bytes.fromhex("010203040506")).decode("ascii")
        self.assertEqual(identity.pin_code, expected_pin)
        self.assertEqual(
            identity.aoa_serial,
            f"010203040506;00000000;PC CarLink;{expected_pin};1.2;0000",
        )

    def test_utf8_name_is_limited_to_sixteen_bytes(self) -> None:
        identity = CarIdentity(car_id="010203040506", short_name="电脑模拟智慧车机桌面")
        short_name = identity.aoa_serial.split(";")[2]
        self.assertLessEqual(len(short_name.encode("utf-8")), 16)


if __name__ == "__main__":
    unittest.main()

