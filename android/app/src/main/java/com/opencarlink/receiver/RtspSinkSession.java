package com.opencarlink.receiver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RtspSinkSession {
    static final class Action {
        final String description;
        final byte[] response;
        final boolean terminated;

        Action(String description, byte[] response) {
            this(description, response, false);
        }

        Action(String description, byte[] response, boolean terminated) {
            this.description = description;
            this.response = response;
            this.terminated = terminated;
        }
    }

    private static final String VIDEO_FORMATS =
        "28 00 01 01 FFFFFFFF FFFFFFFF FFFFFFFF 00 0000 0000 00 none none";
    private byte[] buffer = new byte[0];
    private int nextCseq = 1;
    private boolean sentOwnOptions;
    private final Map<Integer, String> pending = new HashMap<>();
    private String session = "";
    private String presentationUrl = "rtsp://127.0.0.1/wfd1.0/streamid=0";
    private boolean teardownSent;
    private boolean cleared;

    synchronized List<Action> feed(byte[] data, int length) {
        if (cleared) {
            return new ArrayList<>();
        }
        int oldLength = buffer.length;
        byte[] previous = buffer;
        buffer = Arrays.copyOf(previous, oldLength + length);
        Arrays.fill(previous, (byte) 0);
        System.arraycopy(data, 0, buffer, oldLength, length);
        List<Action> actions = new ArrayList<>();
        while (true) {
            Parsed parsed = parsePrefix(buffer);
            if (parsed == null) {
                break;
            }
            previous = buffer;
            buffer = Arrays.copyOfRange(previous, parsed.consumed, previous.length);
            Arrays.fill(previous, (byte) 0);
            try {
                actions.addAll(handle(parsed));
            } finally {
                Arrays.fill(parsed.body, (byte) 0);
            }
            if (cleared) {
                break;
            }
        }
        return actions;
    }

    synchronized byte[] teardownRequest() {
        if (cleared || teardownSent || session.isEmpty()) {
            return new byte[0];
        }
        teardownSent = true;
        return request(
            "TEARDOWN", presentationUrl, new String[]{"Session: " + session}, "teardown"
        );
    }

    synchronized void clear() {
        cleared = true;
        Arrays.fill(buffer, (byte) 0);
        buffer = new byte[0];
        session = "";
        presentationUrl = "";
        pending.clear();
        sentOwnOptions = false;
        teardownSent = true;
    }

    private List<Action> handle(Parsed message) {
        List<Action> actions = new ArrayList<>();
        String cseq = message.headers.getOrDefault("cseq", "1");
        if (message.startLine.startsWith("RTSP/")) {
            int number;
            try {
                number = Integer.parseInt(cseq);
            } catch (NumberFormatException ignored) {
                return actions;
            }
            String waiting = pending.remove(number);
            if ("teardown".equals(waiting)) {
                actions.add(new Action("手机已回复 RTSP TEARDOWN，结束投屏会话", new byte[0], true));
                clear();
                return actions;
            }
            if (teardownSent) {
                return actions;
            }
            if ("setup".equals(waiting) && message.startLine.startsWith("RTSP/1.0 200")) {
                session = message.headers.getOrDefault("session", "").split(";", 2)[0].trim();
                if (!session.isEmpty()) {
                    actions.add(new Action(
                        "SETUP 成功，发送 RTSP PLAY",
                        request("PLAY", presentationUrl, new String[]{"Session: " + session}, "play")
                    ));
                }
            } else if ("play".equals(waiting) && message.startLine.startsWith("RTSP/1.0 200")) {
                actions.add(new Action("RTSP PLAY 成功，等待手机视频流", new byte[0]));
            }
            return actions;
        }

        if (message.startLine.startsWith("OPTIONS ")) {
            actions.add(new Action(
                "回复手机 RTSP OPTIONS",
                response(cseq, new String[]{"Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER"}, new byte[0])
            ));
            if (!sentOwnOptions) {
                sentOwnOptions = true;
                actions.add(new Action(
                    "发送车机 RTSP OPTIONS",
                    request("OPTIONS", "*", new String[]{"Require: org.wfa.wfd1.0"}, "options")
                ));
            }
        } else if (message.startLine.startsWith("GET_PARAMETER ")) {
            byte[] body = message.body.length == 0 ? new byte[0] : capabilityBody();
            String description = message.body.length == 0
                ? "回复手机 RTSP 保活" : "回复手机 RTSP 能力查询";
            actions.add(new Action(description, response(cseq, sessionHeaders(message), body)));
        } else if (message.startLine.startsWith("SET_PARAMETER ")) {
            String body = new String(message.body, StandardCharsets.US_ASCII);
            String trigger = "";
            for (String rawLine : body.split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.toLowerCase(Locale.US).startsWith("wfd_presentation_url:")) {
                    String value = line.substring(line.indexOf(':') + 1).trim().split(" ", 2)[0];
                    if (value.startsWith("rtsp://")) {
                        presentationUrl = value;
                    }
                } else if (line.toLowerCase(Locale.US).startsWith("wfd_trigger_method:")) {
                    trigger = line.substring(line.indexOf(':') + 1).trim().toUpperCase(Locale.US);
                }
            }
            // WFD M5 must be acknowledged before initiating its triggered M6/M8 request.
            actions.add(new Action(
                "回复手机 RTSP 参数设置", response(cseq, sessionHeaders(message), new byte[0])
            ));
            if ("SETUP".equals(trigger) && !teardownSent) {
                actions.add(new Action(
                    "手机触发 SETUP，发送 RTSP SETUP",
                    request(
                        "SETUP",
                        presentationUrl,
                        new String[]{"Transport: RTP/AVP/TCP;unicast;client_port=15550"},
                        "setup"
                    )
                ));
            } else if ("TEARDOWN".equals(trigger)) {
                byte[] teardown = teardownRequest();
                if (teardown.length > 0) {
                    actions.add(new Action("手机触发 TEARDOWN，发送 RTSP TEARDOWN", teardown));
                } else if (session.isEmpty()) {
                    Action acknowledgement = actions.remove(actions.size() - 1);
                    actions.add(new Action(acknowledgement.description, acknowledgement.response, true));
                    clear();
                }
            }
        } else if (message.startLine.startsWith("TEARDOWN ")) {
            actions.add(new Action(
                "手机结束 RTSP 投屏会话",
                response(cseq, sessionHeaders(message), new byte[0]),
                true
            ));
            clear();
        }
        return actions;
    }

    private String[] sessionHeaders(Parsed message) {
        String value = session.isEmpty()
            ? message.headers.getOrDefault("session", "").split(";", 2)[0].trim()
            : session;
        return value.isEmpty() ? new String[0] : new String[]{"Session: " + value};
    }

    private byte[] request(String method, String uri, String[] headers, String waiting) {
        int cseq = nextCseq++;
        if (!waiting.isEmpty()) {
            pending.put(cseq, waiting);
        }
        StringBuilder result = new StringBuilder(method)
            .append(' ').append(uri).append(" RTSP/1.0\r\nCSeq: ").append(cseq).append("\r\n");
        for (String header : headers) {
            result.append(header).append("\r\n");
        }
        return result.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] response(String cseq, String[] headers, byte[] body) {
        StringBuilder result = new StringBuilder("RTSP/1.0 200 OK\r\nCSeq: ")
            .append(cseq).append("\r\n");
        for (String header : headers) {
            result.append(header).append("\r\n");
        }
        if (body.length > 0) {
            result.append("Content-Type: text/parameters\r\nContent-Length: ")
                .append(body.length).append("\r\n");
        }
        byte[] head = result.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
        return IccoaAuthSession.concat(head, body);
    }

    private static byte[] capabilityBody() {
        String value = String.join("\r\n",
            "wfd_car_display_mode: mode=0;display=1280:720;dpi=320;fps=60",
            "wfd_video_formats: " + VIDEO_FORMATS,
            "wfd_audio_codecs: AAC 0000000F 00",
            "wfd_client_rtp_ports: RTP/AVP/TCP;unicast 19000 0 mode=play",
            "wfd_uibc_capability: input_category_list=GENERIC;generic_cap_list=Keyboard, Mouse, SingleTouch, MultiTouch;hidc_cap_list=none;port=none;uibc_encrypted=false",
            "ovm_control_capability: supported",
            "wfd_standby_resume_capability: supported"
        ) + "\r\n";
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static Parsed parsePrefix(byte[] value) {
        int headerEnd = find(value, new byte[]{'\r', '\n', '\r', '\n'});
        if (headerEnd < 0) {
            return null;
        }
        String headerBlock = new String(value, 0, headerEnd, StandardCharsets.UTF_8);
        String[] lines = headerBlock.split("\\r\\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            throw new IllegalArgumentException("RTSP 消息缺少起始行");
        }
        Map<String, String> headers = new HashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon > 0) {
                headers.put(
                    lines[index].substring(0, colon).trim().toLowerCase(Locale.US),
                    lines[index].substring(colon + 1).trim()
                );
            }
        }
        int bodyLength;
        try {
            bodyLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("RTSP Content-Length 无效", error);
        }
        if (bodyLength < 0) {
            throw new IllegalArgumentException("RTSP Content-Length 不能为负数");
        }
        int consumed = headerEnd + 4 + bodyLength;
        if (value.length < consumed) {
            return null;
        }
        return new Parsed(
            lines[0],
            headers,
            Arrays.copyOfRange(value, headerEnd + 4, consumed),
            consumed
        );
    }

    private static int find(byte[] value, byte[] needle) {
        for (int index = 0; index <= value.length - needle.length; index++) {
            boolean match = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (value[index + offset] != needle[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return index;
            }
        }
        return -1;
    }

    private static final class Parsed {
        final String startLine;
        final Map<String, String> headers;
        final byte[] body;
        final int consumed;

        Parsed(String startLine, Map<String, String> headers, byte[] body, int consumed) {
            this.startLine = startLine;
            this.headers = headers;
            this.body = body;
            this.consumed = consumed;
        }
    }
}
