import unittest

from open_carlink_pc.rtsp import RtspSinkHandshake


class RtspSinkHandshakeTests(unittest.TestCase):
    def test_options_gets_response_and_sink_options(self) -> None:
        request = (
            b"OPTIONS * RTSP/1.0\r\n"
            b"CSeq: 7\r\n"
            b"Require: org.wfa.wfd1.0\r\n\r\n"
        )
        responses = RtspSinkHandshake().handle(request)
        self.assertEqual(len(responses), 2)
        self.assertIn(b"RTSP/1.0 200 OK\r\nCSeq: 7", responses[0][1])
        self.assertIn(b"OPTIONS * RTSP/1.0", responses[1][1])

    def test_coalesced_response_and_get_parameter_are_both_parsed(self) -> None:
        handshake = RtspSinkHandshake()
        handshake.handle(b"OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n")
        request_body = (
            b"wfd_video_formats\r\n"
            b"wfd_audio_codecs\r\n"
            b"wfd_client_rtp_ports\r\n"
            b"wfd_uibc_capability\r\n"
        )
        payload = (
            b"RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"
            b"GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n"
            b"CSeq: 2\r\n"
            b"Content-Type: text/parameters\r\n"
            + f"Content-Length: {len(request_body)}\r\n\r\n".encode("ascii")
            + request_body
        )
        responses = handshake.handle(payload)
        self.assertEqual(len(responses), 1)
        response = responses[0][1]
        self.assertIn(b"RTSP/1.0 200 OK\r\nCSeq: 2", response)
        self.assertIn(b"wfd_video_formats: 28 00 01 01 FFFFFFFF", response)
        self.assertIn(b"RTP/AVP/TCP;unicast 19000 0 mode=play", response)
        self.assertIn(b"wfd_uibc_capability: input_category_list=GENERIC", response)
        self.assertIn(b"uibc_encrypted=false", response)
        self.assertIn(
            b"wfd_car_display_mode: mode=0;display=1280:720;dpi=320;fps=30",
            response,
        )
        self.assertIn(b"ovm_control_capability: supported", response)
        self.assertIn(b"wfd_standby_resume_capability: supported", response)

    def test_fragmented_message_is_buffered(self) -> None:
        handshake = RtspSinkHandshake()
        self.assertEqual(handshake.handle(b"OPTIONS * RTSP/1.0\r\nCSeq:"), [])
        responses = handshake.handle(b" 9\r\n\r\n")
        self.assertEqual(len(responses), 2)
        self.assertIn(b"CSeq: 9", responses[0][1])

    def test_setup_trigger_starts_setup_and_acknowledges_phone(self) -> None:
        body = b"wfd_trigger_method: SETUP\r\n"
        request = (
            b"SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n"
            b"CSeq: 4\r\n"
            + f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
            + body
        )
        responses = RtspSinkHandshake().handle(request)
        self.assertEqual(len(responses), 2)
        self.assertIn(b"SETUP rtsp://127.0.0.1/wfd1.0/streamid=0", responses[0][1])
        self.assertIn(b"Transport: RTP/AVP/TCP;unicast", responses[0][1])
        self.assertIn(b"client_port=15550\r\n", responses[0][1])
        self.assertNotIn(b"client_port=15550-15551", responses[0][1])
        self.assertIn(b"RTSP/1.0 200 OK\r\nCSeq: 4", responses[1][1])

    def test_presentation_url_from_phone_is_used_for_setup(self) -> None:
        handshake = RtspSinkHandshake()
        params = b"wfd_presentation_URL: rtsp://10.0.0.8/wfd1.0/streamid=0 none\r\n"
        message = (
            b"SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\n"
            + f"Content-Length: {len(params)}\r\n\r\n".encode("ascii")
            + params
        )
        handshake.handle(message)
        trigger = b"wfd_trigger_method: SETUP\r\n"
        responses = handshake.handle(
            b"SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n"
            + f"Content-Length: {len(trigger)}\r\n\r\n".encode("ascii")
            + trigger
        )
        self.assertIn(b"SETUP rtsp://10.0.0.8/wfd1.0/streamid=0", responses[0][1])

    def test_teardown_uses_negotiated_session_and_presentation_url(self) -> None:
        handshake = RtspSinkHandshake()
        params = b"wfd_presentation_URL: rtsp://10.0.0.8/wfd1.0/streamid=0 none\r\n"
        handshake.handle(
            b"SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\n"
            + f"Content-Length: {len(params)}\r\n\r\n".encode("ascii")
            + params
        )
        trigger = b"wfd_trigger_method: SETUP\r\n"
        handshake.handle(
            b"SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n"
            + f"Content-Length: {len(trigger)}\r\n\r\n".encode("ascii")
            + trigger
        )
        handshake.handle(
            b"RTSP/1.0 200 OK\r\nCSeq: 1\r\nSession: 12345678;timeout=30\r\n\r\n"
        )

        teardown = handshake.build_teardown()

        self.assertIn(
            b"TEARDOWN rtsp://10.0.0.8/wfd1.0/streamid=0 RTSP/1.0",
            teardown,
        )
        self.assertIn(b"Session: 12345678\r\n", teardown)

    def test_teardown_is_empty_before_setup_completes(self) -> None:
        self.assertEqual(RtspSinkHandshake().build_teardown(), b"")


if __name__ == "__main__":
    unittest.main()
