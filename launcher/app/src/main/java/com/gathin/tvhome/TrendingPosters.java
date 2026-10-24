package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * trending_posters: this week's popular films and series from TMDB (api.tmdb.org) as the cinematic shelf's backdrop, for dock apps
 * without a posters/ picture of their own. When Home comes up it fetches at most once a day, on a background
 * thread: the list, then up to COUNT backdrops at 1280 px into the theme folder's trending/, swapped in whole. A
 * failed fetch keeps the previous pictures and is tried again an hour later at the earliest, or at once when
 * the key is replaced; trending_status.txt says how the last try went, without the key. Nothing is fetched
 * without the viewer's own TMDB key, typed in Launcher settings (or pushed as tmdb_key.txt next to config.json),
 * which stays in the app's private files; no key is built in. TMDB asks for credit, which the shelf's line under
 * the name gives with every picture.
 */
final class TrendingPosters {
    static final String FOLDER = "trending";
    static final String KEY_FILE = "tmdb_key.txt";
    static final String TITLES = "titles.txt";
    static final String STATUS = "trending_status.txt";
    static final int COUNT = 10;
    private static final String LANGUAGE = "zh-CN";
    private static final String API_HOST = "api.tmdb.org";
    private static final long DAY_MS = 20L * 60 * 60 * 1000;
    private static final long RETRY_MS = 60L * 60 * 1000;
    private static final int LIST_LIMIT = 1 << 20, IMAGE_LIMIT = 4 << 20;
    private static final Pattern IMAGE_PATH = Pattern.compile("/[A-Za-z0-9_-]+\\.(jpg|png)");
    private static final Pattern V3_KEY = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern READ_TOKEN = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final String PREFS = "trending";
    private static final ExecutorService fetcher = Executors.newSingleThreadExecutor();
    private static volatile boolean running;

    private TrendingPosters() {
    }

    /** One backdrop: its file in trending/ and the film's or series' name. */
    static final class Item {
        final File file;
        final String title;
        final String overview;

        Item(File file, String title, String overview) {
            this.file = file;
            this.title = title;
            this.overview = overview;
        }
    }

    /** The saved pictures in TMDB's order; empty before the first fetch. */
    static List<Item> saved(File themeDir) {
        return saved(themeDir, FOLDER);
    }

    /** The pictures saved in this folder of the theme (trending/, or BingDaily's bing/). */
    static List<Item> saved(File themeDir, String folder) {
        if (themeDir == null) return Collections.emptyList();
        File dir = new File(themeDir, folder);
        List<Item> items = new ArrayList<>();
        try {
            for (String[] entry : parseTitles(new String(Files.readAllBytes(new File(dir, TITLES).toPath()),
                    StandardCharsets.UTF_8))) {
                File file = new File(dir, entry[0]);
                if (file.isFile()) items.add(new Item(file, entry[1], entry[2]));
            }
        } catch (IOException | RuntimeException none) {
            return Collections.emptyList();
        }
        return items;
    }

    /** titles.txt: image, title and overview separated by tabs; old two-column caches remain readable. */
    static List<String[]> parseTitles(String text) {
        List<String[]> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String name = line.substring(0, tab);
            if (!name.matches("[0-9]{2}\\.jpg")) continue;
            String[] fields = line.substring(tab + 1).split("\t", 2);
            out.add(new String[]{name, fields[0].trim(), fields.length > 1 ? fields[1].trim() : ""});
        }
        return out;
    }

    /** A v4 read access token goes in a Bearer header; a 32-character v3 API key goes in the query. */
    static boolean isBearer(String key) {
        return key.length() > 40;
    }

    /** Only a plain TMDB file path ("/abc123.jpg") is put after the image host. */
    static boolean validImagePath(String path) {
        return path != null && IMAGE_PATH.matcher(path).matches();
    }

    /** A typed or pasted key without spaces or "Bearer ", or null when it is neither a v3 API key nor a read token. */
    static String normalizeKey(String input) {
        if (input == null) return null;
        String key = input.trim();
        if (key.regionMatches(true, 0, "Bearer ", 0, 7)) key = key.substring(7);
        key = key.replaceAll("\\s+", "");
        boolean token = isBearer(key) && READ_TOKEN.matcher(key).matches();
        return token || V3_KEY.matcher(key).matches() ? key : null;
    }

    /** The viewer's key, in the app's private files: other apps cannot read them, unlike the theme folder. */
    static File keyFile(Context ctx) {
        return new File(ctx.getFilesDir(), KEY_FILE);
    }

    static boolean hasKey(Context ctx) {
        return readKey(keyFile(ctx)) != null;
    }

    /** Keeps the key, or removes it with null. */
    static void saveKey(Context ctx, String key) throws IOException {
        File file = keyFile(ctx);
        if (key == null) {
            if (file.exists() && !file.delete()) throw new IOException("cannot delete " + KEY_FILE);
            return;
        }
        writeFile(file, (key + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** A tmdb_key.txt pushed next to config.json replaces the kept key and is deleted; an empty one removes it. */
    static void importPushedKey(Context ctx) {
        File dir = Config.dir(ctx);
        File pushed = dir == null ? null : new File(dir, KEY_FILE);
        if (pushed == null || !pushed.isFile()) return;
        try {
            saveKey(ctx, readKey(pushed));
        } catch (IOException notNow) {
            return;   // the pushed file stays for the next try
        }
        if (!pushed.delete()) pushed.deleteOnExit();
    }

    /** The first line of trending_status.txt, or null before the first try. */
    static String lastStatus(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return null;
        try {
            String text = new String(Files.readAllBytes(new File(dir, STATUS).toPath()), StandardCharsets.UTF_8);
            String line = text.split("\n")[0].trim();
            return line.isEmpty() ? null : line;
        } catch (IOException | RuntimeException none) {
            return null;
        }
    }

    static String readKey(File file) {
        try {
            for (String line : new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\n")) {
                String key = line.trim();
                if (key.regionMatches(true, 0, "Bearer ", 0, 7)) key = key.substring(7).trim();
                if (!key.isEmpty()) return key;
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    /** Fetches on a background thread when a day has passed; runs updated on the UI thread after new pictures. */
    static void refreshIfDue(Context ctx, Runnable updated) {
        File dir = Config.dir(ctx);
        if (dir == null) return;
        File keyFile = keyFile(ctx);
        if (!keyFile.isFile()) return;
        // A new cache generation fills overviews immediately even when today's old pictures are still fresh.
        runDaily(ctx, dir, "fetched_v2", "tried_v2", keyFile.lastModified(), FOLDER, STATUS,
                () -> fetch(dir, readKey(keyFile)), updated);
    }

    interface Fetch {
        int run() throws IOException;
    }

    /**
     * Runs fetch on the shared thread at most once a day, an hour after a failed try at the earliest unless the input
     * changed since (changedAt), and notes the outcome in statusName next to config.json.
     */
    static void runDaily(Context ctx, File dir, String fetchedKey, String triedKey, long changedAt, String folder,
                         String statusName, Fetch fetch, Runnable updated) {
        if (running) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long fetched = prefs.getLong(fetchedKey, 0), tried = prefs.getLong(triedKey, 0);
        boolean fresh = now >= fetched && now - fetched < DAY_MS && !saved(dir, folder).isEmpty();
        if (fresh || (now >= tried && now - tried < RETRY_MS && changedAt <= tried)) return;
        prefs.edit().putLong(triedKey, now).apply();
        running = true;
        Handler main = new Handler(Looper.getMainLooper());
        fetcher.execute(() -> {
            String status;
            try {
                int count = fetch.run();
                prefs.edit().putLong(fetchedKey, System.currentTimeMillis()).apply();
                main.post(updated);
                status = "OK " + count;
            } catch (Exception | OutOfMemoryError failed) {
                // No network, TMDB unreachable or a bad key: the previous pictures stay.
                status = "FAILED " + failed.getClass().getSimpleName() + ": "
                        + String.valueOf(failed.getMessage()).replaceAll("api_key=[^&\\s]*", "api_key=*");
            } finally {
                running = false;
            }
            try {
                writeFile(new File(dir, statusName), (String.format(Locale.ROOT, "%tF %<tT ", System.currentTimeMillis())
                        + status + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Only a note for whoever looks; the pictures do not depend on it.
            }
        });
    }

    /** The number of pictures now in trending/; throws with the reason when none could be had. */
    private static int fetch(File themeDir, String key) throws IOException {
        if (key == null) throw new IOException("no key in " + KEY_FILE);
        boolean bearer = isBearer(key);
        // TMDB's alternate API host: api.themoviedb.org does not resolve to TMDB on mainland China networks.
        String url = "https://" + API_HOST + "/3/trending/all/week?language=" + LANGUAGE
                + (bearer ? "" : "&api_key=" + URLEncoder.encode(key, "UTF-8"));
        JSONArray results;
        try {
            results = new JSONObject(new String(get(url, bearer ? key : null, LIST_LIMIT), StandardCharsets.UTF_8))
                    .optJSONArray("results");
        } catch (org.json.JSONException bad) {
            throw new IOException("list is not JSON");
        }
        if (results == null) throw new IOException("no results in the list");
        File fresh = new File(themeDir, FOLDER + ".new");
        deleteTree(fresh);
        if (!fresh.mkdirs()) throw new IOException("cannot create " + fresh.getName());
        StringBuilder titles = new StringBuilder();
        int saved = 0;
        for (int i = 0; i < results.length() && saved < COUNT; i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String path = r.optString("backdrop_path", null);
            if (!validImagePath(path)) continue;
            String title = Cards.cleanLabel(r.optString("title", r.optString("name", "")), "");
            String name = String.format(Locale.ROOT, "%02d.jpg", saved + 1);
            File file = new File(fresh, name);
            try {
                writeFile(file, get("https://image.tmdb.org/t/p/w1280" + path, null, IMAGE_LIMIT));
            } catch (IOException skipped) {
                continue;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                if (!file.delete()) file.deleteOnExit();
                continue;
            }
            String overview = r.optString("overview", "").replaceAll("\\s+", " ").trim();
            titles.append(name).append('\t').append(title.replace('\t', ' '))
                    .append('\t').append(overview).append('\n');
            saved++;
        }
        if (saved == 0) {
            deleteTree(fresh);
            throw new IOException("no picture downloaded");
        }
        writeFile(new File(fresh, TITLES), titles.toString().getBytes(StandardCharsets.UTF_8));
        swapIn(themeDir, FOLDER, fresh);
        return saved;
    }

    /** Replaces the folder with the freshly filled one in whole, so a half-done fetch never shows. */
    static void swapIn(File themeDir, String folder, File fresh) throws IOException {
        File live = new File(themeDir, folder), old = new File(themeDir, folder + ".old");
        deleteTree(old);
        if (live.exists() && !live.renameTo(old)) deleteTree(live);
        boolean swapped = fresh.renameTo(live);
        deleteTree(old);
        if (!swapped) throw new IOException("cannot replace " + folder);
    }

    static byte[] get(String url, String bearer, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (out.size() + n > limit) throw new IOException("too large");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static void writeFile(File file, byte[] bytes) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }
}
