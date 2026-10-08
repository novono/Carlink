package com.opencarlink.receiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class AudioDecoderFallbackTest {
    @Test
    public void namedC2DecoderWorksEvenWhenByTypeLookupWouldFail() throws Exception {
        List<String> attempts = new ArrayList<>();
        String opened = AudioDecoderFallback.open(Arrays.asList("c2.android.aac.decoder"), name -> {
            attempts.add(name);
            if (name == null) { throw new IOException("Failed to find matching codec"); }
            return name;
        });

        assertEquals("c2.android.aac.decoder", opened);
        assertEquals(Arrays.asList("c2.android.aac.decoder"), attempts);
    }

    @Test
    public void brokenPreferredDecoderCanRecoverThroughItsSoftwareAlias() throws Exception {
        List<String> attempts = new ArrayList<>();
        String opened = AudioDecoderFallback.open(Arrays.asList(
            "c2.android.aac.decoder", "c2.android.aac.decoder", "OMX.google.aac.decoder"), name -> {
            attempts.add(name);
            if ("OMX.google.aac.decoder".equals(name)) { return name; }
            throw new IllegalStateException("Codec configure failed");
        });

        assertEquals("OMX.google.aac.decoder", opened);
        assertEquals(Arrays.asList("c2.android.aac.decoder", "OMX.google.aac.decoder"), attempts);
    }

    @Test
    public void mimeSelectionIsTriedAfterAllNamedCandidatesFail() throws Exception {
        List<String> attempts = new ArrayList<>();
        String opened = AudioDecoderFallback.open(Arrays.asList("vendor.aac"), name -> {
            attempts.add(name);
            if (name != null) { throw new IOException("Vendor unavailable"); }
            return "mime-decoder";
        });

        assertEquals("mime-decoder", opened);
        assertEquals(Arrays.asList("vendor.aac", null), attempts);
    }

    @Test
    public void unavailableAudioPreservesEveryFailureForAnHonestDiagnosis() {
        try {
            AudioDecoderFallback.open(Arrays.asList("c2.android.aac.decoder"), name -> {
                throw new IOException(name == null ? "MIME lookup failed" : "C2 unavailable");
            });
            fail("Audio unavailability must be reported");
        } catch (IOException error) {
            assertEquals(2, error.getSuppressed().length);
            assertEquals("MIME lookup failed", error.getSuppressed()[1].getMessage());
        }
    }

    @Test
    public void fatalJvmErrorsAreNotMisreportedAsUnsupportedAudio() throws Exception {
        AssertionError fatal = new AssertionError("fatal");
        try {
            AudioDecoderFallback.open(Arrays.asList("aac"), name -> { throw fatal; });
            fail("Fatal error must escape");
        } catch (AssertionError error) {
            assertSame(fatal, error);
        }
    }
}
