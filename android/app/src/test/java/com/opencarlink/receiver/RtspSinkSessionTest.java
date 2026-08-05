package com.opencarlink.receiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RtspSinkSessionTest {
    @Test
    public void handlesSplitOptionsAndAdvertisesCarCapabilities() {
        RtspSinkSession session = new RtspSinkSession();
        byte[] options = "OPTIONS * RTSP/1.0\r\nCSeq: 8\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII);
        assertTrue(session.feed(options, 12).isEmpty());
        byte[] remainder = java.util.Arrays.copyOfRange(options, 12, options.length);
        List<RtspSinkSession.Action> actions = session.feed(remainder, remainder.length);
        assertEquals(2, actions.size());
        assertTrue(ascii(actions.get(0).response).contains("RTSP/1.0 200 OK"));
        assertTrue(ascii(actions.get(1).response).startsWith("OPTIONS * RTSP/1.0"));

        byte[] queryBody = "wfd_video_formats\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] query = (
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n"
                + "CSeq: 9\r\nContent-Length: " + queryBody.length + "\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII);
        actions = session.feed(
            IccoaAuthSession.concat(query, queryBody),
            query.length + queryBody.length
        );
        String capabilityResponse = ascii(actions.get(0).response);
        assertTrue(capabilityResponse.contains("wfd_car_display_mode"));
        assertTrue(capabilityResponse.contains("1280:720"));
        assertTrue(capabilityResponse.contains("19000 0 mode=play"));
    }

    @Test
    public void advancesFromSetupTriggerToPlay() {
        RtspSinkSession session = new RtspSinkSession();
        byte[] body = (
            "wfd_presentation_url: rtsp://192.168.1.2/wfd1.0/streamid=0 none\r\n"
                + "wfd_trigger_method: SETUP\r\n"
        ).getBytes(StandardCharsets.US_ASCII);
        byte[] request = (
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n"
                + "CSeq: 10\r\nContent-Length: " + body.length + "\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII);
        List<RtspSinkSession.Action> actions = session.feed(
            IccoaAuthSession.concat(request, body),
            request.length + body.length
        );
        assertEquals(2, actions.size());
        String setup = ascii(actions.get(0).response);
        assertTrue(setup.startsWith("SETUP rtsp://192.168.1.2/"));
        assertTrue(setup.contains("client_port=15550"));

        byte[] setupOk = (
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nSession: 123456;timeout=30\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII);
        actions = session.feed(setupOk, setupOk.length);
        assertEquals(1, actions.size());
        assertTrue(ascii(actions.get(0).response).startsWith("PLAY "));

        byte[] playOk = "RTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII);
        actions = session.feed(playOk, playOk.length);
        assertEquals("RTSP PLAY 成功，等待手机视频流", actions.get(0).description);
    }

    private static String ascii(byte[] value) {
        return new String(value, StandardCharsets.US_ASCII);
    }
}
