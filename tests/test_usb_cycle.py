import unittest
from unittest.mock import Mock, patch

from open_carlink_pc.usb_cycle import find_accessory_instance_id


class FindAccessoryInstanceTests(unittest.TestCase):
    @patch("open_carlink_pc.usb_cycle.os.name", "nt")
    @patch("open_carlink_pc.usb_cycle.subprocess.run")
    def test_matches_accessory_composite_instance_by_serial(self, run_mock: Mock) -> None:
        run_mock.return_value = Mock(
            stdout=(
                "Instance ID: USB\\VID_18D1&PID_2D01\\OTHER\n"
                "Instance ID: USB\\VID_18D1&PID_2D01\\PHONE123\n"
            )
        )

        result = find_accessory_instance_id(0x18D1, 0x2D01, "PHONE123")

        self.assertEqual(result, r"USB\VID_18D1&PID_2D01\PHONE123")

    @patch("open_carlink_pc.usb_cycle.os.name", "nt")
    @patch("open_carlink_pc.usb_cycle.subprocess.run")
    def test_refuses_ambiguous_instances_without_serial(self, run_mock: Mock) -> None:
        run_mock.return_value = Mock(
            stdout=(
                "USB\\VID_18D1&PID_2D01\\FIRST\n"
                "USB\\VID_18D1&PID_2D01\\SECOND\n"
            )
        )

        self.assertIsNone(find_accessory_instance_id(0x18D1, 0x2D01))


if __name__ == "__main__":
    unittest.main()
