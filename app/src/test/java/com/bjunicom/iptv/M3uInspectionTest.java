package com.bjunicom.iptv;

import com.bjunicom.iptv.parser.M3uParser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class M3uInspectionTest {
    @Test
    public void inspectsCustomNameUrlAndBareEntries() {
        String content = "News,rtp://239.3.1.129:8008\n"
                + "#genre#\n"
                + "Movies,https://example.test/live.m3u8\n";
        M3uParser.M3uInspectionReport report = M3uParser.inspectAndParse(content, "custom.m3u");
        assertTrue(report.isValid());
        assertEquals(2, report.validChannelCount);
        assertEquals(1, report.multicastCount);
        assertEquals(1, report.hlsCount);
    }

    @Test
    public void rejectsUnsupportedIgmpSchemeInsteadOfImportingUnplayableChannel() {
        M3uParser.M3uInspectionReport report = M3uParser.inspectAndParse(
                "#EXTM3U\n#EXTINF:-1,broken\nigmp://239.3.1.1:8008\n", "bad.m3u");
        assertFalse(report.isValid());
        assertEquals(0, report.validChannelCount);
    }
}
