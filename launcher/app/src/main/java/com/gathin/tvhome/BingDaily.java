package com.gathin.tvhome;

import android.content.Context;
import android.graphics.BitmapFactory;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The "bing" backdrop chosen in Launcher settings: Bing's picture of the day from cn.bing.com, which mainland China
 * networks reach, fetched at most once a day like the trending pictures into the theme folder's bing/, with its
 * title and copyright line as the caption.
 */
final class BingDaily {
    static final String FOLDER = "bing";
    static final String STATUS = "bing_status.txt";
    private static final String HOST = "https://cn.bing.com";
    private static final int LIST_LIMIT = 64 << 10, IMAGE_LIMIT = 8 << 20;

    private BingDaily() {
    }

    static List<TrendingPosters.Item> saved(File themeDir) {
        return TrendingPosters.saved(themeDir, FOLDER);
    }

    static void refreshIfDue(Context ctx, Runnable updated) {
        File dir = Config.dir(ctx);
        if (dir == null) return;
        TrendingPosters.runDaily(ctx, dir, "bing_fetched", "bing_tried", 0, FOLDER, STATUS, () -> fetch(dir), updated);
    }

    /** Bing gives a path on its own host ("/th?id=OHR..."); anything else is not fetched. */
    static boolean validImageUrl(String url) {
        return url != null && url.startsWith("/th?id=") && url.matches("[A-Za-z0-9/?=&._%-]+");
    }

    private static int fetch(File themeDir) throws IOException {
        JSONObject image;
        try {
            image = new JSONObject(new String(TrendingPosters.get(HOST + "/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN",
                    null, LIST_LIMIT), StandardCharsets.UTF_8)).getJSONArray("images").getJSONObject(0);
        } catch (JSONException bad) {
            throw new IOException("answer is not JSON");
        }
        String url = image.optString("url", null);
        if (!validImageUrl(url)) throw new IOException("unexpected picture address");
        File fresh = new File(themeDir, FOLDER + ".new");
        TrendingPosters.deleteTree(fresh);
        if (!fresh.mkdirs()) throw new IOException("cannot create " + fresh.getName());
        File file = new File(fresh, "01.jpg");
        TrendingPosters.writeFile(file, TrendingPosters.get(HOST + url, null, IMAGE_LIMIT));
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            TrendingPosters.deleteTree(fresh);
            throw new IOException("not a picture");
        }
        String title = Cards.cleanLabel(image.optString("title", ""), "");
        String copyright = image.optString("copyright", "").replaceAll("\\s+", " ").trim();
        TrendingPosters.writeFile(new File(fresh, TrendingPosters.TITLES), ("01.jpg\t" + title.replace('\t', ' ')
                + "\t" + copyright + "\n").getBytes(StandardCharsets.UTF_8));
        TrendingPosters.swapIn(themeDir, FOLDER, fresh);
        return 1;
    }
}
