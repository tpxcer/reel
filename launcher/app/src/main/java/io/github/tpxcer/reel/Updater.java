package io.github.tpxcer.reel;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launcher settings > Check for updates: the newest Reel TV release on GitHub, only when asked. The APK is checked
 * against the .sha256 file published next to it and handed to the system installer, where the viewer confirms.
 * Android installs it only over a build with the same package and signing key, so this is offered to the release
 * build alone.
 */
final class Updater {
    private static final String LATEST = "https://api.github.com/repos/tpxcer/reel-tv/releases/latest";
    /** Release assets are only fetched from this repository's downloads. */
    static final String DOWNLOADS = "https://github.com/tpxcer/reel-tv/releases/download/";
    /** SHA-256 of Reel TV's release signing certificate. */
    static final String RELEASE_CERT = "09a2d23c6122d87052ba0f9d9d6cd67dd9635c9242ae10573c15c95d367ef701";
    private static final int LIST_LIMIT = 256 << 10, APK_LIMIT = 16 << 20, SHA_LIMIT = 4 << 10;

    private Updater() {
    }

    /** A release's version, its APK and the checksum file published with it. */
    static final class Release {
        final String version;
        final String apkUrl;
        final String shaUrl;
        final long size;

        Release(String version, String apkUrl, String shaUrl, long size) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.shaUrl = shaUrl;
            this.size = size;
        }
    }

    /** "v0.3.1" or "0.3.1-beta" as {0, 3, 1}; null when it is not a version. */
    static int[] parseVersion(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] parts = s.split("\\.");
        if (parts.length == 0 || parts.length > 3) return null;
        int[] out = new int[3];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException notANumber) {
            return null;
        }
        return out;
    }

    /** Whether candidate is a later version than current; false when either cannot be read. */
    static boolean newer(String candidate, String current) {
        int[] a = parseVersion(candidate), b = parseVersion(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    /** The release's .apk asset and its .sha256 file, both from this repository, or null. Assets are {name, url, size}. */
    static Release pick(String tag, List<String[]> assets) {
        String[] apk = null;
        for (String[] a : assets) {
            if (a[0].toLowerCase(Locale.ROOT).endsWith(".apk") && a[1].startsWith(DOWNLOADS)) {
                apk = a;
                break;
            }
        }
        if (apk == null || parseVersion(tag) == null) return null;
        for (String[] a : assets) {
            if (a[0].equals(apk[0] + ".sha256") && a[1].startsWith(DOWNLOADS)) {
                long size;
                try {
                    size = Long.parseLong(apk[2]);
                } catch (NumberFormatException unknown) {
                    size = 0;
                }
                return new Release(tag.startsWith("v") ? tag.substring(1) : tag, apk[1], a[1], size);
            }
        }
        return null;
    }

    /** The checksum in a "sha256sum" line ("&lt;64 hex&gt;  file.apk"), lower case, or null. */
    static String parseSha(String text) {
        if (text == null) return null;
        String first = text.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return first.matches("[0-9a-f]{64}") ? first : null;
    }

    static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String currentVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException impossible) {
            return "";
        }
    }

    /** Whether this build is signed with Reel TV's release key, so a release can install over it. */
    @SuppressWarnings("deprecation")
    static boolean isRelease(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            for (Signature s : info.signatures) {
                if (RELEASE_CERT.equals(sha256(s.toByteArray()))) return true;
            }
        } catch (PackageManager.NameNotFoundException impossible) {
            return false;
        }
        return false;
    }

    /** The latest release with an APK, or null when GitHub has none. Network; not on the UI thread. */
    static Release latest() throws IOException {
        try {
            JSONObject release = new JSONObject(new String(TrendingPosters.get(LATEST, null, LIST_LIMIT), StandardCharsets.UTF_8));
            JSONArray list = release.optJSONArray("assets");
            List<String[]> assets = new ArrayList<>();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                assets.add(new String[]{a.optString("name", ""), a.optString("browser_download_url", ""),
                        String.valueOf(a.optLong("size", 0))});
            }
            return pick(release.optString("tag_name", ""), assets);
        } catch (JSONException bad) {
            throw new IOException("GitHub answered something that is not a release");
        }
    }

    /** Downloads the release, checks it against its .sha256 and starts the installer. Network; not on the UI thread. */
    static void downloadAndInstall(Context ctx, Release release) throws IOException {
        byte[] apk = TrendingPosters.get(release.apkUrl, null, APK_LIMIT);
        String expected = parseSha(new String(TrendingPosters.get(release.shaUrl, null, SHA_LIMIT), StandardCharsets.UTF_8));
        if (expected == null || !expected.equals(sha256(apk))) throw new IOException(ctx.getString(R.string.update_bad_checksum));
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("reel-tv.apk", 0, apk.length)) {
                out.write(apk);
                session.fsync(out);
            }
            // The installer adds its status to this intent, so it must stay mutable on Android 12 and later.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent done = PendingIntent.getBroadcast(ctx, id, new Intent(ctx, UpdateReceiver.class), flags);
            session.commit(done.getIntentSender());
        }
    }
}
