package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Brings the remote's helpers back after a force stop without waiting for Home. Redmi Android 9 force-stops every
 * third-party app on each standby, which takes this app's accessibility helper and voice slot off their lists.
 * Android keeps an approved notification listener through a force stop and binds it again at once, so this one
 * only runs ServiceRepair when it connects; it never reads a notification.
 *
 * Enable over adb:
 *   cmd notification allow_listener com.gathin.tvhome/.HelperKeeper
 */
public class HelperKeeper extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        if (Config.load(this).restoreKeyServices) ServiceRepair.restore(this);
    }
}
