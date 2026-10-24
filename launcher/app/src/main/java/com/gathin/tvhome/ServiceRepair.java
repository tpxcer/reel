package com.gathin.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Android takes an app's accessibility services off the enabled list when the app is force-stopped (Settings, a
 * cleaner app, or am force-stop), and the remote's Home, Input and app buttons then stop working until the helper
 * is turned on again over ADB. With restore_key_services on, a running helper records itself here and drops the
 * record when Android unbinds it (the user turned it off); a force stop kills the process before that, so the
 * record stays. Home starting again puts recorded helpers back on the list, when the installer granted
 * WRITE_SECURE_SETTINGS. With the flag off nothing is recorded and nothing is restored.
 *
 * The voice interaction slot (HomeVoiceService) is cleared by a force stop the same way, and is recorded and put
 * back the same way, but only while the slot is still empty: an assistant chosen since then is left alone.
 */
final class ServiceRepair {
    private static final String PREFS = "accessibility_helpers";
    private static final String VOICE_PREFS = "voice_slot";
    private static final String VOICE_KEY = "service";
    /** Settings.Secure.VOICE_INTERACTION_SERVICE, hidden from the SDK. */
    private static final String VOICE_SETTING = "voice_interaction_service";

    private ServiceRepair() {
    }

    static void connected(Context context, Class<?> service) {
        prefs(context).edit().putBoolean(service.getName(), true).apply();
    }

    static void disconnected(Context context, Class<?> service) {
        prefs(context).edit().remove(service.getName()).apply();
    }

    static void voiceConnected(Context context, Class<?> service) {
        voicePrefs(context).edit().putString(VOICE_KEY, service.getName()).apply();
    }

    static void voiceDisconnected(Context context) {
        voicePrefs(context).edit().remove(VOICE_KEY).apply();
    }

    static void restore(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            ContentResolver cr = context.getContentResolver();
            restoreHelpers(context, cr);
            String voice = voicePrefs(context).getString(VOICE_KEY, null);
            if (voice != null && isEmptySetting(Settings.Secure.getString(cr, VOICE_SETTING))) {
                Settings.Secure.putString(cr, VOICE_SETTING,
                        new ComponentName(context.getPackageName(), voice).flattenToShortString());
            }
        } catch (RuntimeException ignored) {
            // The setting is the system's; a refusal leaves the helper off, as before.
        }
    }

    private static void restoreHelpers(Context context, ContentResolver cr) {
        String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<ComponentName> enabled = new ArrayList<>();
        if (list != null) {
            for (String entry : list.split(":")) {
                ComponentName name = ComponentName.unflattenFromString(entry);
                if (name != null) enabled.add(name);
            }
        }
        StringBuilder updated = new StringBuilder(isEmptySetting(list) ? "" : list);
        boolean missing = false;
        for (String service : prefs(context).getAll().keySet()) {
            ComponentName name = new ComponentName(context.getPackageName(), service);
            if (enabled.contains(name)) continue;
            if (updated.length() > 0) updated.append(':');
            updated.append(name.flattenToShortString());
            missing = true;
        }
        if (!missing) return;
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated.toString());
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    /** settings get prints "null" for a key that was never written; a cleared slot can also be empty. */
    static boolean isEmptySetting(String value) {
        return value == null || value.isEmpty() || value.equals("null");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences voicePrefs(Context context) {
        return context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE);
    }
}
