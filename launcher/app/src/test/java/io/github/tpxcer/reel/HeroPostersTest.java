package io.github.tpxcer.reel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;
public class HeroPostersTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Test public void findsThePackagesPictureJpgFirst() throws Exception {
        File dir = tmp.newFolder("posters");
        File png = new File(dir, "com.kkys.tv1.png");
        assertTrue(png.createNewFile());
        assertEquals(png, HeroPosters.fileFor(dir, "com.kkys.tv1"));
        File jpg = new File(dir, "com.kkys.tv1.jpg");
        assertTrue(jpg.createNewFile());
        assertEquals(jpg, HeroPosters.fileFor(dir, "com.kkys.tv1"));
    }
    @Test public void noPictureNoFolderOrNoPackageGivesNull() throws Exception {
        File dir = tmp.newFolder("posters");
        assertNull(HeroPosters.fileFor(dir, "org.smarttube.stable"));
        assertNull(HeroPosters.fileFor(null, "org.smarttube.stable"));
        assertNull(HeroPosters.fileFor(dir, null));
        assertNull(HeroPosters.fileFor(dir, ""));
    }
    @Test public void eachDayTheDockMovesOnFourTrendingPictures() {
        assertEquals(0, HeroPosters.trendingIndex(0, 0, 10));
        assertEquals(3, HeroPosters.trendingIndex(3, 0, 10));
        assertEquals(4, HeroPosters.trendingIndex(0, 1, 10));
        assertEquals(1, HeroPosters.trendingIndex(3, 2, 10));
        assertEquals(2, HeroPosters.trendingIndex(0, 2, 3));
        for (long day = -5; day < 40; day++) {
            for (int slot = 0; slot < 4; slot++) {
                int i = HeroPosters.trendingIndex(slot, day, 10);
                assertTrue(i >= 0 && i < 10);
            }
        }
    }
    @Test public void aFolderNamedLikeThePictureIsNotOne() throws Exception {
        File dir = tmp.newFolder("posters");
        assertTrue(new File(dir, "com.cibn.tv.jpg").mkdir());
        assertNull(HeroPosters.fileFor(dir, "com.cibn.tv"));
    }
}
