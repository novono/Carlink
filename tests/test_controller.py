import unittest
from unittest.mock import Mock, patch

from open_carlink_pc.auth import decrypt_session_message
from open_carlink_pc.config import CarIdentity
from open_carlink_pc.controller import CarLinkController
from open_carlink_pc.ucar_protocol import is_session_disconnect


class ControllerStopTests(unittest.TestCase):
    def setUp(self) -> None:
        self.log = Mock()
        self.controller = CarLinkController(
            CarIdentity(car_id="001122334455"),
            self.log,
            Mock(),
        )
        self.transport = Mock()
        self.multiplexer = Mock()
        self.controller._transport = self.transport
        self.controller._multiplexer = self.multiplexer

    @patch("open_carlink_pc.controller.threading.Timer")
    def test_stop_allows_teardown_to_flush_before_closing_usb(self, timer_class: Mock) -> None:
        self.controller._rtsp = Mock(build_teardown=Mock(return_value=b"TEARDOWN"))
        timer = timer_class.return_value

        self.controller.stop()

        self.multiplexer.write_frame.assert_called_once_with(3, b"TEARDOWN")
        self.assertTrue(self.controller._stopping.is_set())
        self.assertTrue(self.controller._cycle_after_stop.is_set())
        self.assertFalse(self.controller._stop.is_set())
        self.transport.close.assert_not_called()
        timer.start.assert_called_once_with()

        timer_class.call_args.args[1]()

        self.assertTrue(self.controller._stop.is_set())
        self.transport.close.assert_called_once_with()

    @patch("open_carlink_pc.controller.threading.Timer")
    def test_stop_without_rtsp_session_closes_usb_immediately(self, timer_class: Mock) -> None:
        self.controller._rtsp = Mock(build_teardown=Mock(return_value=b""))

        self.controller.stop()

        timer_class.assert_not_called()
        self.assertTrue(self.controller._stop.is_set())
        self.assertTrue(self.controller._cycle_after_stop.is_set())
        self.transport.close.assert_called_once_with()

    @patch("open_carlink_pc.controller.threading.Timer")
    def test_stop_sends_encrypted_ucar_disconnect(self, timer_class: Mock) -> None:
        session_key = bytes(range(16))
        self.controller._rtsp = Mock(build_teardown=Mock(return_value=b""))
        self.controller._auth_session = Mock(
            confirmed=True,
            session_key=session_key,
        )

        self.controller.stop()

        encrypted = self.multiplexer.write_frame.call_args.args[1]
        self.assertEqual(self.multiplexer.write_frame.call_args.args[0], 2)
        self.assertTrue(is_session_disconnect(decrypt_session_message(session_key, encrypted)))
        timer_class.return_value.start.assert_called_once_with()

    @patch("open_carlink_pc.controller.wait_for_phone_mode", return_value=(0x22D9, 0x1234))
    @patch("open_carlink_pc.controller.cycle_phone_port", return_value=(True, "cycled"))
    def test_usb_reset_enables_reconnect_only_after_phone_returns(
        self,
        cycle_phone_port_mock: Mock,
        wait_for_phone_mode_mock: Mock,
    ) -> None:
        self.controller._usb_instance_id = r"USB\VID_18D1&PID_2D01\PHONE"

        state = self.controller._reset_phone_usb()

        cycle_phone_port_mock.assert_called_once_with(self.controller._usb_instance_id)
        self.assertEqual(wait_for_phone_mode_mock.call_args.args, (12.0,))
        self.assertEqual(wait_for_phone_mode_mock.call_args.kwargs["stable_seconds"], 3.0)
        self.assertTrue(callable(wait_for_phone_mode_mock.call_args.kwargs["observation"]))
        self.assertEqual(state, "已真实断开，可重新连接")

    @patch(
        "open_carlink_pc.controller.wait_for_phone_mode",
        side_effect=[None, (0x22D9, 0x2765)],
    )
    @patch("open_carlink_pc.controller.cycle_phone_port", return_value=(True, "cycled"))
    def test_usb_reset_retries_when_phone_returns_to_aoa(
        self,
        cycle_phone_port_mock: Mock,
        wait_for_phone_mode_mock: Mock,
    ) -> None:
        self.controller._usb_instance_id = r"USB\VID_18D1&PID_2D01\PHONE"

        state = self.controller._reset_phone_usb()

        self.assertEqual(cycle_phone_port_mock.call_count, 2)
        self.assertEqual(wait_for_phone_mode_mock.call_count, 2)
        self.assertEqual(state, "已真实断开，可重新连接")

    @patch("open_carlink_pc.controller.cycle_phone_port", return_value=(False, "denied"))
    def test_usb_reset_requires_manual_replug_when_cycle_fails(
        self,
        cycle_phone_port_mock: Mock,
    ) -> None:
        self.controller._usb_instance_id = r"USB\VID_18D1&PID_2D01\PHONE"

        state = self.controller._reset_phone_usb()

        self.assertEqual(cycle_phone_port_mock.call_count, 2)
        self.assertEqual(state, "需要拔插 USB")

    @patch("open_carlink_pc.controller.cycle_phone_port")
    def test_usb_reset_never_cycles_an_unknown_port(self, cycle_phone_port_mock: Mock) -> None:
        state = self.controller._reset_phone_usb()

        cycle_phone_port_mock.assert_not_called()
        self.assertEqual(state, "需要拔插 USB")


if __name__ == "__main__":
    unittest.main()
