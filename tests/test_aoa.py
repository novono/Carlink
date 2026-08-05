import unittest

import usb.core

from open_carlink_pc.aoa import _is_timeout_error


class TimeoutDetectionTests(unittest.TestCase):
    def test_windows_socket_timeout_is_recognized(self) -> None:
        error = usb.core.USBError("Operation timed out", errno=10060)
        self.assertTrue(_is_timeout_error(error))

    def test_libusb_timeout_is_recognized(self) -> None:
        error = usb.core.USBError("LIBUSB_ERROR_TIMEOUT")
        error.backend_error_code = -7
        self.assertTrue(_is_timeout_error(error))

    def test_unrelated_usb_error_is_not_timeout(self) -> None:
        error = usb.core.USBError("Access denied", errno=13)
        self.assertFalse(_is_timeout_error(error))


if __name__ == "__main__":
    unittest.main()
