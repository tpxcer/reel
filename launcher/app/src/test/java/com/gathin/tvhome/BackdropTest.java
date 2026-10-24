package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The backdrop behind the dock: the one picked in Launcher settings, else what the theme turns on. */
public class BackdropTest {

    @Test
    public void aPickedBackdropWins() {
        assertEquals(Config.BACKDROP_BING, Config.backdrop("bing", true, true));
        assertEquals(Config.BACKDROP_SCENES, Config.backdrop("scenes", false, false));
        assertEquals(Config.BACKDROP_TMDB, Config.backdrop("tmdb", false, true));
    }

    @Test
    public void otherwiseTheThemeDecides() {
        assertEquals(Config.BACKDROP_TMDB, Config.backdrop(null, true, true));
        assertEquals(Config.BACKDROP_POSTERS, Config.backdrop(null, false, true));
        assertEquals("", Config.backdrop(null, false, false));
        assertEquals("", Config.backdrop("aurora", false, false));
    }
}
