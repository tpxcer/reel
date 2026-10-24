package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    // Redmi Android 9 blocks application switches briefly after the physical Home key.
    private final Handler redirectHandler = new Handler(Looper.getMainLooper());
    private final Runnable retryRedirect = () -> {
        if (!stockHomeHeld) openLocalHome();
    };
    private final Runnable checkStockHome = this::openIfStockHomeShows;
    private String stockHomePackage = "";
    private BroadcastReceiver homeReceiver;
    /** Sent by Xiaomi's PowerManagerService when the screensaver time is up and no video plays. */
    private static final String XIAOMI_SCREENSAVER = "com.duokan.intent.action.show.screensaver";
    /** The stock launcher's tile opened it on purpose: leave it up until the next Home press. */
    private static volatile boolean stockHomeHeld;
    /** The Home press the tile made for that, which must not count as the next one. */
    private static volatile long ownHomeUntil;
    /** This service while stock_home_redirect is configured and Android has it bound; null otherwise. */
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static HomeKeyService redirecting;

    /** The 「小米桌面」tile. Only the system may start that launcher, so press Home for it; false if unavailable. */
    static boolean openStockHome() {
        HomeKeyService service = redirecting;
        if (service == null) return false;
        stockHomeHeld = true;
        ownHomeUntil = SystemClock.uptimeMillis() + 2000;
        if (service.performGlobalAction(GLOBAL_ACTION_HOME)) return true;
        stockHomeHeld = false;
        ownHomeUntil = 0;
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    @Override
    public void onDestroy() {
        if (redirecting == this) redirecting = null;
        redirectHandler.removeCallbacks(retryRedirect);
        redirectHandler.removeCallbacks(checkStockHome);
        if (homeReceiver != null) unregisterReceiver(homeReceiver);
        stopWaitingForUnlock();
        super.onDestroy();
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        stockHome.clear();
        stockHomePackage = config.stockHomeRedirect;
        if (!stockHomePackage.isEmpty() && homeReceiver == null) {
            homeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                        checkStockHomeSoon();
                        return;
                    }
                    if (XIAOMI_SCREENSAVER.equals(intent.getAction())) {
                        showScreensaver();
                        return;
                    }
                    if (!"homekey".equals(intent.getStringExtra("reason"))) return;
                    if (SystemClock.uptimeMillis() < ownHomeUntil) {
                        ownHomeUntil = 0;
                        return;
                    }
                    stockHomeHeld = false;
                    redirectHandler.removeCallbacks(retryRedirect);
                    // The stock launcher already in front raises no window event. With HomeVoiceService the early
                    // try goes through; without it Android holds it back until the second one.
                    redirectHandler.postDelayed(retryRedirect, 700);
                    redirectHandler.postDelayed(retryRedirect, 6500);
                }
            };
            // Observe the system Home notification without filtering any key events.
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            homeFilter.addAction(Intent.ACTION_SCREEN_ON);
            // Xiaomi's timer asks its own screensaver app, disabled on this TV, to show; the launcher's answers.
            if (config.homeIdleClockMinutes > 0) homeFilter.addAction(XIAOMI_SCREENSAVER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(homeReceiver, homeFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerLegacyHomeReceiver(homeFilter);
            }
        }
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
            redirecting = this;
            // Bound again after a force stop (HelperKeeper) or at boot, while the stock launcher may be showing.
            if (getSystemService(PowerManager.class).isInteractive()) checkStockHomeSoon();
        }
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
    }

    // Called only below API 33, where receiver export flags are not required.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyHomeReceiver(IntentFilter filter) {
        registerReceiver(homeReceiver, filter);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, appScancodes.get(event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (RemoteKeys.handle(this, event, appKeys.get(event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        if (redirecting == this) redirecting = null;
        if (repairRecord) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        if (stockHome.isEmpty() || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        // Another application took focus: never pull it away with the pending retry.
        if (pkg != null && !stockHomePackage.contentEquals(pkg)
                && !"android".contentEquals(pkg) && !"com.android.systemui".contentEquals(pkg)
                && !"com.xiaomi.mitv.systemui".contentEquals(pkg)) {
            redirectHandler.removeCallbacks(retryRedirect);
            redirectHandler.removeCallbacks(checkStockHome);
        }
        CharSequence cls = event.getClassName();
        if (cls != null && stockHome.contains(cls.toString()) && !stockHomeHeld) {
            redirectHandler.removeCallbacks(retryRedirect);
            redirectHandler.postDelayed(retryRedirect, 5500);
            openLocalHome();
        }
    }

    private void showScreensaver() {
        try {
            startActivity(new Intent(this, ClockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException refused) {
            // The firmware held it back; the timer asks again next time.
        }
    }

    /** Standby on Redmi Android 9 opens the stock launcher, which raises no window event on waking. */
    private void checkStockHomeSoon() {
        redirectHandler.removeCallbacks(checkStockHome);
        // Once the wake-up screen is gone, and again in case it was still up.
        redirectHandler.postDelayed(checkStockHome, 1000);
        redirectHandler.postDelayed(checkStockHome, 3000);
    }

    private void openIfStockHomeShows() {
        if (stockHomeHeld) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        root.recycle();
        if (pkg != null && stockHomePackage.contentEquals(pkg)) openLocalHome();
    }

    private void openLocalHome() {
        startActivity(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onInterrupt() {
    }
}
