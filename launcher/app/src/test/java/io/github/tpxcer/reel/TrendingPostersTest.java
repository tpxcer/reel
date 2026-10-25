package io.github.tpxcer.reel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;
public class TrendingPostersTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Test public void readsTitlesAndSkipsAnythingElse() {
        List<String[]> t = TrendingPosters.parseTitles("01.jpg\t沙丘2\n\nbad line\n../x.jpg\tescape\n02.jpg\t 黑袍纠察队 \n");
        assertEquals(2, t.size());
        assertArrayEquals(new String[]{"01.jpg", "沙丘2", ""}, t.get(0));
        assertArrayEquals(new String[]{"02.jpg", "黑袍纠察队", ""}, t.get(1));
    }
    @Test public void keepsEachOverviewWithItsPictureAndSupportsMissingOverviews() {
        List<String[]> t = TrendingPosters.parseTitles("01.jpg\t影片甲\t 甲的介绍 \n02.jpg\t剧集乙\t乙的介绍\n03.jpg\t旧缓存\n04.jpg\t无简介\t\n");
        assertArrayEquals(new String[]{"01.jpg", "影片甲", "甲的介绍"}, t.get(0));
        assertArrayEquals(new String[]{"02.jpg", "剧集乙", "乙的介绍"}, t.get(1));
        assertEquals("", t.get(2)[2]);
        assertEquals("", t.get(3)[2]);
    }
    @Test public void tellsAReadTokenFromAnApiKey() {
        assertFalse(TrendingPosters.isBearer("0123456789abcdef0123456789abcdef"));
        assertTrue(TrendingPosters.isBearer("eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIwMTIzNDU2Nzg5YWJjZGVmIn0.signature"));
    }
    @Test public void typedKeysAreCleanedAndChecked() {
        assertEquals("0123456789abcdef0123456789ABCDEF", TrendingPosters.normalizeKey(" 0123456789abcdef 0123456789ABCDEF\n"));
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIwMTIzNDU2Nzg5YWJjZGVmIn0.c2lnbmF0dXJl_-x";
        assertEquals(token, TrendingPosters.normalizeKey("Bearer " + token));
        assertEquals(token, TrendingPosters.normalizeKey(token.substring(0, 30) + "\n" + token.substring(30)));
        assertNull(TrendingPosters.normalizeKey(null));
        assertNull(TrendingPosters.normalizeKey(""));
        assertNull(TrendingPosters.normalizeKey("0123456789abcdef0123456789abcde"));    // one short
        assertNull(TrendingPosters.normalizeKey("0123456789abcdef0123456789abcdeg"));   // not hex
        assertNull(TrendingPosters.normalizeKey("eyJabc.def.ghi"));                     // too short for a token
        assertNull(TrendingPosters.normalizeKey(token.replace('.', '/')));
    }
    @Test public void onlyPlainImagePathsAreFetched() {
        assertTrue(TrendingPosters.validImagePath("/aBc-12_3.jpg"));
        assertFalse(TrendingPosters.validImagePath(null));
        assertFalse(TrendingPosters.validImagePath("/../etc/passwd"));
        assertFalse(TrendingPosters.validImagePath("https://evil.example/x.jpg"));
        assertFalse(TrendingPosters.validImagePath("/x.jpg?y=1"));
    }
    @Test public void readsTheFirstKeyLineWithOrWithoutBearer() throws Exception {
        File key = tmp.newFile("tmdb_key.txt");
        Files.write(key.toPath(), "\n  Bearer eyJabc.def.ghi  \nignored\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("eyJabc.def.ghi", TrendingPosters.readKey(key));
        Files.write(key.toPath(), "0123456789abcdef0123456789abcdef\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("0123456789abcdef0123456789abcdef", TrendingPosters.readKey(key));
        assertNull(TrendingPosters.readKey(new File(tmp.getRoot(), "missing.txt")));
    }
    @Test public void savedListsOnlyPicturesThatExist() throws Exception {
        File theme = tmp.newFolder("theme");
        File dir = new File(theme, TrendingPosters.FOLDER);
        assertTrue(dir.mkdir());
        assertTrue(new File(dir, "01.jpg").createNewFile());
        Files.write(new File(dir, TrendingPosters.TITLES).toPath(),
                "01.jpg\tA\tOverview A\n02.jpg\tB\tOverview B\n".getBytes(StandardCharsets.UTF_8));
        List<TrendingPosters.Item> items = TrendingPosters.saved(theme);
        assertEquals(1, items.size());
        assertEquals("A", items.get(0).title);
        assertEquals("Overview A", items.get(0).overview);
        assertTrue(TrendingPosters.saved(tmp.newFolder("empty")).isEmpty());
        assertTrue(TrendingPosters.saved(null).isEmpty());
    }
}
