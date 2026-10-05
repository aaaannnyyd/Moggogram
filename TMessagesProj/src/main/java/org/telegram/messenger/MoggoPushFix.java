package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;

import xyz.nextalone.nagram.NaConfig;

/**
 * Moggogram has its own api_id and no Firebase project of its own: Telegram cannot deliver FCM
 * pushes to it, and the bundled NagramX Firebase config refuses the app.moggogram package. With
 * the inherited default (FCM) the keep-alive service never starts, so notifications only arrive
 * while the process happens to be alive. Move such installs to the in-app push connection kept
 * by a foreground service, once.
 */
public final class MoggoPushFix {

    private static final String PREFS = "moggo_push";
    private static final String KEY_MIGRATED = "migrated_v1";
    private static final String KEY_BATTERY_ASKED = "battery_asked_v1";

    private MoggoPushFix() {
    }

    public static void migrateIfNeeded() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_MIGRATED, false)) return;
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply();
        int type = NaConfig.INSTANCE.getPushServiceType().Int();
        if (type == 2) return; // UnifiedPush chosen by the user: leave it alone
        NaConfig.INSTANCE.getPushServiceType().setConfigInt(0);
        NaConfig.INSTANCE.getPushServiceTypeInAppDialog().setConfigBool(true);
        MessagesController.getGlobalNotificationsSettings().edit()
                .putBoolean("pushService", true)
                .putBoolean("pushConnection", true)
                .apply();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            MessagesController.getNotificationsSettings(a).edit()
                    .putBoolean("pushService", true)
                    .putBoolean("pushConnection", true)
                    .apply();
        }
        ApplicationLoader.resetPushProvider();
        PushListenerController.reconcilePushRegistration();
        FileLog.d("moggogram: push service type switched from " + type + " to in-app keep-alive");
    }

    public static void enablePushConnection() {
        if (NaConfig.INSTANCE.getPushServiceType().Int() != 0) return;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager.getInstance(a).setPushConnectionEnabled(true);
            }
        }
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** Once: explain why background work is needed and open the system "don't optimize" prompt. */
    public static void maybeAskBatteryExemption(Activity activity) {
        if (activity == null || NaConfig.INSTANCE.getPushServiceType().Int() != 0) return;
        if (isIgnoringBatteryOptimizations(activity)) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_BATTERY_ASKED, false)) return;
        prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.MoggoPushTitle));
        builder.setMessage(LocaleController.getString(R.string.MoggoPushBatteryText));
        builder.setPositiveButton(LocaleController.getString(R.string.MoggoPushAllow), (d, w) -> requestBatteryExemption(activity));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        try {
            builder.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void requestBatteryExemption(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                FileLog.e(e2);
            }
        }
    }
}
