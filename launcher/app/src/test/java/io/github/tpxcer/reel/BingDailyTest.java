package io.github.tpxcer.reel;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Only a picture path on Bing's own host is fetched. */
public class BingDailyTest {

    @Test
    public void bingsOwnPictureAddress() {
        assertTrue(BingDaily.validImageUrl("/th?id=OHR.RoyalReadingRoom_ZH-CN1234567890_1920x1080.jpg&rf=LaDigue_1920x1080.jpg&pid=hp"));
    }

    @Test
    public void anythingElseIsRefused() {
        assertFalse(BingDaily.validImageUrl(null));
        assertFalse(BingDaily.validImageUrl(""));
        assertFalse(BingDaily.validImageUrl("https://example.com/th?id=x.jpg"));
        assertFalse(BingDaily.validImageUrl("//example.com/th?id=x.jpg"));
        assertFalse(BingDaily.validImageUrl("/az/hprichbg/x.jpg"));
        assertFalse(BingDaily.validImageUrl("/th?id=x.jpg\nHost: example.com"));
        assertFalse(BingDaily.validImageUrl("/th?id=x.jpg#@example.com"));
    }
}
