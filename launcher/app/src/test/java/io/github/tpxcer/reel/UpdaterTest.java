package io.github.tpxcer.reel;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Check for updates: which release counts as newer, which assets are fetched and how the checksum is read. */
public class UpdaterTest {
    private static final String D = Updater.DOWNLOADS;

    @Test
    public void readsTagsAndVersionNames() {
        assertArrayEquals(new int[]{0, 3, 0}, Updater.parseVersion("v0.3.0"));
        assertArrayEquals(new int[]{0, 10, 2}, Updater.parseVersion("0.10.2"));
        assertArrayEquals(new int[]{1, 2, 0}, Updater.parseVersion("v1.2"));
        assertArrayEquals(new int[]{0, 3, 0}, Updater.parseVersion("0.3.0-pre"));
        assertNull(Updater.parseVersion("latest"));
        assertNull(Updater.parseVersion(null));
    }

    @Test
    public void onlyALaterVersionIsOffered() {
        assertTrue(Updater.newer("0.3.1", "0.3.0"));
        assertTrue(Updater.newer("0.10.0", "0.9.9"));
        assertTrue(Updater.newer("0.3.0", "0.2.9"));
        assertFalse(Updater.newer("0.3.0", "0.3.0"));
        assertFalse(Updater.newer("0.2.9", "0.3.0"));
        assertFalse(Updater.newer("next", "0.3.0"));
    }

    @Test
    public void picksTheApkAndItsChecksumFromThisRepository() {
        List<String[]> assets = Arrays.asList(
                new String[]{"Reel-TV-v0.3.0.apk.sha256", D + "v0.3.0/Reel-TV-v0.3.0.apk.sha256", "84"},
                new String[]{"Reel-TV-v0.3.0.apk", D + "v0.3.0/Reel-TV-v0.3.0.apk", "512000"});
        Updater.Release r = Updater.pick("v0.3.0", assets);
        assertNotNull(r);
        assertEquals("0.3.0", r.version);
        assertEquals(D + "v0.3.0/Reel-TV-v0.3.0.apk", r.apkUrl);
        assertEquals(D + "v0.3.0/Reel-TV-v0.3.0.apk.sha256", r.shaUrl);
        assertEquals(512000, r.size);
    }

    @Test
    public void refusesAReleaseWithoutAChecksumOrFromElsewhere() {
        assertNull(Updater.pick("v0.3.0", Collections.singletonList(
                new String[]{"Reel-TV-v0.3.0.apk", D + "v0.3.0/Reel-TV-v0.3.0.apk", "1"})));
        assertNull(Updater.pick("v0.3.0", Arrays.asList(
                new String[]{"Reel-TV-v0.3.0.apk", "https://example.com/Reel-TV-v0.3.0.apk", "1"},
                new String[]{"Reel-TV-v0.3.0.apk.sha256", D + "v0.3.0/Reel-TV-v0.3.0.apk.sha256", "1"})));
        assertNull(Updater.pick("nightly", Arrays.asList(
                new String[]{"a.apk", D + "x/a.apk", "1"}, new String[]{"a.apk.sha256", D + "x/a.apk.sha256", "1"})));
    }

    @Test
    public void readsTheChecksumLine() {
        String sum = "C0F87C733255178D25E15415F7C4258E5CE262493D46C94B17449874B9CFF87A";
        assertEquals(sum.toLowerCase(), Updater.parseSha(sum + "  Reel-TV-v0.2.1.apk\n"));
        assertNull(Updater.parseSha("not a checksum"));
        assertNull(Updater.parseSha(null));
    }

    @Test
    public void hashesLikeSha256sum() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Updater.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }
}
