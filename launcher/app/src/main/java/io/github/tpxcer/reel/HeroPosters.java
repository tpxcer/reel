package io.github.tpxcer.reel;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * hero_posters: a sharp full-screen picture per dock app behind the cinematic shelf, from the theme folder's
 * posters/&lt;package&gt;.jpg (or .png), instead of the softened artwork. With trending_posters, a dock app without
 * one gets the TrendingPosters picture for its dock slot, with that title as the caption. Decoded off the UI
 * thread at screen size, the way the wallpaper is; the last two stay in memory so moving between neighbours does
 * not decode again.
 */
final class HeroPosters {
    static final String FOLDER = "posters";
    private static final String[] EXTENSIONS = {".jpg", ".png"};
    private static final int KEEP = 2;

    private final File dir;
    private final int width, height;
    private final Bitmap.Config bitmapConfig;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Access order, so the eldest entry is the one looked at longest ago. Keyed by file path. */
    private final LinkedHashMap<String, Bitmap> recent = new LinkedHashMap<>(4, 0.75f, true);
    private final boolean own;
    private List<TrendingPosters.Item> trending = Collections.emptyList();

    /** A picture to show and, for a trending one, the title to credit under the name; null for the owner's. */
    static final class Source {
        final File file;
        final String caption;
        final String overview;

        Source(File file, String caption, String overview) {
            this.file = file;
            this.caption = caption;
            this.overview = overview;
        }
    }

    HeroPosters(File themeDir, boolean own, int width, int height, boolean trueColor) {
        this.dir = themeDir == null ? null : new File(themeDir, FOLDER);
        this.own = own;
        this.width = width;
        this.height = height;
        this.bitmapConfig = trueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
    }

    /** The picture for this package in the posters folder, or null when there is none. */
    static File fileFor(File postersDir, String pkg) {
        if (postersDir == null || pkg == null || pkg.isEmpty()) return null;
        for (String ext : EXTENSIONS) {
            File file = new File(postersDir, pkg + ext);
            if (file.isFile()) return file;
        }
        return null;
    }

    /** New trending pictures (or none): the next look decodes them. */
    void setTrending(List<TrendingPosters.Item> items) {
        trending = items;
        recent.clear();
    }

    /** The owner's picture for this app, else the trending one for its dock slot; null for neither. */
    Source sourceFor(String pkg, int dockSlot) {
        File mine = own ? fileFor(dir, pkg) : null;
        if (mine != null) return new Source(mine, null, "");
        if (trending.isEmpty() || dockSlot < 0) return null;
        TrendingPosters.Item item = trending.get(trendingIndex(dockSlot, LocalDate.now().toEpochDay(), trending.size()));
        return new Source(item.file, item.title, item.overview);
    }

    /** The week's list changes slowly, so each day the dock moves on to the next four pictures. */
    static int trendingIndex(int dockSlot, long epochDay, int count) {
        return (int) ((dockSlot + Math.floorMod(epochDay, count) * 4L) % count);
    }

    /** Hands the picture to done on the UI thread; null when it does not decode. */
    void load(Source source, Consumer<Bitmap> done) {
        File file = source.file;
        String key = file.getPath();
        Bitmap cached = recent.get(key);
        if (cached != null) {
            done.accept(cached);
            return;
        }
        decoder.execute(() -> {
            Bitmap poster = decode(file);
            main.post(() -> {
                if (poster != null) {
                    recent.put(key, poster);
                    Iterator<Map.Entry<String, Bitmap>> eldest = recent.entrySet().iterator();
                    while (recent.size() > KEEP) {
                        eldest.next();
                        eldest.remove();
                    }
                }
                done.accept(poster);
            });
        });
    }

    /** Home stopped or the tiles changed: let the pictures go; they decode again when needed. */
    void clear() {
        recent.clear();
    }

    void close() {
        decoder.shutdownNow();
        recent.clear();
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int w = options.outWidth, h = options.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = WallpaperSize.sampleSize(w, h, width, height);
            if (!WallpaperSize.accepted(w / sample, h / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(w / sample, h / sample, width, height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            // GPU memory only, as the wallpaper does, instead of a software bitmap plus its texture.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
