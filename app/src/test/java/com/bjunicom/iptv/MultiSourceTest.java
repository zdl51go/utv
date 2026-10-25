package com.bjunicom.iptv;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class MultiSourceTest {

    @Test
    public void bundledMobileAndTelecomSourcesAreParseable() throws Exception {
        assertAssetHasChannels("bj-mobile-iptv.m3u", true);
        assertAssetHasChannels("bj-telecom-iptv.m3u", false);
    }

    @Test
    public void providerDefaultsUseTheMatchingScanSubnet() {
        assertTrue("239.3.1".equals(
                IptvSettings.getDefaultScanSubnetForIsp(IptvSettings.ISP_BEIJING_UNICOM)));
        assertTrue("225.1.8".equals(
                IptvSettings.getDefaultScanSubnetForIsp(IptvSettings.ISP_BEIJING_TELECOM)));
    }

    private void assertAssetHasChannels(String assetName, boolean expectHls) throws Exception {
        try (InputStream input = RuntimeEnvironment.getApplication().getAssets().open(assetName)) {
            List<IptvChannel> channels = M3uParser.parse(input);
            assertFalse(channels.isEmpty());
            if (expectHls) {
                assertTrue(channels.get(0).isHlsStream());
            }
        }
    }
}
