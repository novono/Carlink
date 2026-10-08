package com.opencarlink.receiver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
        assertTrue(ascii(actions.get(0).response).startsWith("RTSP/1.0 200 OK\r\nCSeq: 10\r\n"));
        String setup = ascii(actions.get(1).response);
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

    @Test
    public void emptyGetParameterAcknowledgesKeepaliveWithNegotiatedSession() {
        RtspSinkSession session = establishedSession();
        List<RtspSinkSession.Action> actions = feed(session,
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n"
                + "CSeq: 40\r\nSession: 123456\r\nContent-Length: 0\r\n\r\n"
        );

        assertEquals(1, actions.size());
        assertEquals(
            "RTSP/1.0 200 OK\r\nCSeq: 40\r\nSession: 123456\r\n\r\n",
            ascii(actions.get(0).response)
        );
        assertFalse(actions.get(0).terminated);
        assertFalse(ascii(actions.get(0).response).contains("wfd_video_formats"));
    }

    @Test
    public void keepaliveWithoutSessionHeaderStillGetsSuccessfulResponse() {
        RtspSinkSession session = establishedSession();
        List<RtspSinkSession.Action> actions = feed(session,
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 41\r\n\r\n"
        );

        assertEquals(1, actions.size());
        assertTrue(ascii(actions.get(0).response).startsWith("RTSP/1.0 200 OK\r\nCSeq: 41\r\n"));
        assertTrue(ascii(actions.get(0).response).contains("Session: 123456\r\n"));
        assertFalse(actions.get(0).terminated);
    }

    @Test
    public void phoneTeardownIsAcknowledgedBeforeTerminatingAndCannotRestartSession() {
        RtspSinkSession session = establishedSession();
        List<RtspSinkSession.Action> actions = feed(session,
            "TEARDOWN rtsp://10.0.0.8/wfd1.0/streamid=0 RTSP/1.0\r\n"
                + "CSeq: 50\r\nSession: 123456\r\n\r\n"
        );

        assertEquals(1, actions.size());
        assertEquals(
            "RTSP/1.0 200 OK\r\nCSeq: 50\r\nSession: 123456\r\n\r\n",
            ascii(actions.get(0).response)
        );
        assertTrue(actions.get(0).terminated);
        assertEquals(0, session.teardownRequest().length);
        assertTrue(feed(session, "OPTIONS * RTSP/1.0\r\nCSeq: 51\r\n\r\n").isEmpty());
    }

    @Test
    public void teardownTriggerAcknowledgesFirstAndWaitsForTeardownConfirmation() {
        RtspSinkSession session = establishedSession();
        List<RtspSinkSession.Action> actions = feed(session,
            parameterRequest(60, "wfd_trigger_method: TEARDOWN\r\n")
        );

        assertEquals(2, actions.size());
        assertTrue(ascii(actions.get(0).response).startsWith("RTSP/1.0 200 OK\r\nCSeq: 60\r\n"));
        assertTrue(ascii(actions.get(0).response).contains("Session: 123456\r\n"));
        assertEquals(
            "TEARDOWN rtsp://10.0.0.8/wfd1.0/streamid=0 RTSP/1.0\r\n"
                + "CSeq: 3\r\nSession: 123456\r\n\r\n",
            ascii(actions.get(1).response)
        );
        assertFalse(actions.get(0).terminated);
        assertFalse(actions.get(1).terminated);
        assertEquals(0, session.teardownRequest().length);

        actions = feed(session, "RTSP/1.0 200 OK\r\nCSeq: 3\r\n\r\n");
        assertEquals(1, actions.size());
        assertTrue(actions.get(0).terminated);
        assertEquals(0, actions.get(0).response.length);
        assertTrue(feed(session, "OPTIONS * RTSP/1.0\r\nCSeq: 61\r\n\r\n").isEmpty());
    }

    @Test
    public void localTeardownUsesPresentationUrlSessionAndOnlySendsOnce() {
        RtspSinkSession session = new RtspSinkSession();
        assertEquals(0, session.teardownRequest().length);
        establish(session);

        assertEquals(
            "TEARDOWN rtsp://10.0.0.8/wfd1.0/streamid=0 RTSP/1.0\r\n"
                + "CSeq: 3\r\nSession: 123456\r\n\r\n",
            ascii(session.teardownRequest())
        );
        assertEquals(0, session.teardownRequest().length);
        List<RtspSinkSession.Action> actions = feed(session,
            "RTSP/1.0 200 OK\r\nCSeq: 3\r\n\r\n"
        );
        assertEquals(1, actions.size());
        assertTrue(actions.get(0).terminated);
    }

    @Test
    public void teardownBeforeSetupOnlyAcknowledgesAndEndsTheLocalSession() {
        RtspSinkSession session = new RtspSinkSession();
        List<RtspSinkSession.Action> actions = feed(session,
            parameterRequest(70, "wfd_trigger_method: TEARDOWN\r\n")
        );

        assertEquals(1, actions.size());
        assertTrue(ascii(actions.get(0).response).startsWith("RTSP/1.0 200 OK\r\nCSeq: 70\r\n"));
        assertTrue(actions.get(0).terminated);
        assertEquals(0, session.teardownRequest().length);
    }

    @Test
    public void clearErasesPartialDataAndReleasesSessionState() throws Exception {
        RtspSinkSession session = establishedSession();
        byte[] partial = "GET_PARAMETER rtsp://phone/wfd1.0 RTSP/1.0\r\nSession: 123456"
            .getBytes(StandardCharsets.US_ASCII);
        assertTrue(session.feed(partial, partial.length).isEmpty());
        Field bufferField = RtspSinkSession.class.getDeclaredField("buffer");
        bufferField.setAccessible(true);
        byte[] retainedBuffer = (byte[]) bufferField.get(session);
        assertArrayEquals(partial, retainedBuffer);

        session.clear();
        session.clear();

        assertArrayEquals(new byte[retainedBuffer.length], retainedBuffer);
        assertEquals(0, ((byte[]) bufferField.get(session)).length);
        for (String fieldName : Arrays.asList("session", "presentationUrl")) {
            Field field = RtspSinkSession.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            assertEquals("", field.get(session));
        }
        Field pending = RtspSinkSession.class.getDeclaredField("pending");
        pending.setAccessible(true);
        assertTrue(((Map<?, ?>) pending.get(session)).isEmpty());
        assertEquals(0, session.teardownRequest().length);
        assertTrue(feed(session, "OPTIONS * RTSP/1.0\r\nCSeq: 80\r\n\r\n").isEmpty());
    }

    private static RtspSinkSession establishedSession() {
        RtspSinkSession session = new RtspSinkSession();
        establish(session);
        return session;
    }

    private static void establish(RtspSinkSession session) {
        feed(session, parameterRequest(10,
            "wfd_presentation_url: rtsp://10.0.0.8/wfd1.0/streamid=0 none\r\n"
                + "wfd_trigger_method: SETUP\r\n"
        ));
        feed(session,
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nSession: 123456;timeout=30\r\n\r\n"
        );
        feed(session, "RTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n");
    }

    private static String parameterRequest(int cseq, String body) {
        return "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: " + cseq
            + "\r\nContent-Length: " + body.getBytes(StandardCharsets.US_ASCII).length
            + "\r\n\r\n" + body;
    }

    private static List<RtspSinkSession.Action> feed(RtspSinkSession session, String message) {
        byte[] value = message.getBytes(StandardCharsets.US_ASCII);
        return session.feed(value, value.length);
    }

    private static String ascii(byte[] value) {
        return new String(value, StandardCharsets.US_ASCII);
    }
}
