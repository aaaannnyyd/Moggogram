package org.telegram.messenger;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import xyz.nextalone.nagram.NaConfig;

/** Moggogram: test notification and crash reports, both through the push gateway. */
public final class MoggoDiagnostics {

    private static final int TEST_NOTIFICATION_ID = 0x4D6F6767;

    private MoggoDiagnostics() {
    }

    // ---- test notification ----

    public static void sendTestNotification(Activity activity) {
        if (activity == null) return;
        int type = NaConfig.INSTANCE.getPushServiceType().Int();
        String token = MoggoPushGateway.savedFcmToken();
        if (MoggoPushGateway.isActive() && !TextUtils.isEmpty(token)) {
            Toast.makeText(activity, LocaleController.getString(R.string.MoggoTestSent), Toast.LENGTH_LONG).show();
            Utilities.globalQueue.postRunnable(() -> {
                boolean ok = false;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(MoggoPushGateway.base() + "test/" + URLEncoder.encode(token, "UTF-8")).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    ok = c.getResponseCode() == 201;
                    c.disconnect();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (!ok) {
                    AndroidUtilities.runOnUIThread(() -> showInfo(activity, LocaleController.getString(R.string.MoggoTestGatewayFailed)));
                }
            });
            return;
        }
        String mode;
        if (type == 2) {
            mode = LocaleController.getString(R.string.MoggoTestModeUnified);
        } else if (type == 0) {
            mode = LocaleController.getString(R.string.MoggoTestModeKeepAlive);
        } else {
            mode = LocaleController.getString(R.string.MoggoTestModeNoToken);
        }
        showTestNotification(LocaleController.getString(R.string.MoggoTestLocal));
        showInfo(activity, mode);
    }

    private static void showInfo(Activity activity, String text) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.MoggoTestTitle));
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        try {
            builder.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Shown when the gateway's test push arrives through FCM. */
    public static void onTestPushReceived() {
        showTestNotification(LocaleController.getString(R.string.MoggoTestReceived));
    }

    private static void showTestNotification(String text) {
        try {
            NotificationsController.checkOtherNotificationsChannel();
            Context context = ApplicationLoader.applicationContext;
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle("Moggogram")
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            manager.notify(TEST_NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- crash reports ----

    private static File crashDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "moggo_crashes");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                if (NaConfig.INSTANCE.getMoggoCrashReports().Bool()) saveCrash(thread, throwable);
            } catch (Throwable ignore) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    private static void saveCrash(Thread thread, Throwable throwable) throws Exception {
        StringWriter stack = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stack));
        JSONObject report = new JSONObject();
        report.put("app", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        report.put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        report.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        report.put("thread", thread.getName());
        report.put("time", System.currentTimeMillis());
        String trace = stack.toString();
        report.put("stack", trace.length() > 48000 ? trace.substring(0, 48000) : trace);
        File file = new File(crashDir(), System.currentTimeMillis() + ".json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(report.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Upload crash reports saved by earlier runs (the crashing process can't do network). */
    public static void uploadPendingCrashes() {
        if (!MoggoPushGateway.isConfigured()) return;
        Utilities.globalQueue.postRunnable(() -> {
            File[] files = crashDir().listFiles();
            if (files == null) return;
            for (File file : files) {
                if (!NaConfig.INSTANCE.getMoggoCrashReports().Bool()) {
                    file.delete();
                    continue;
                }
                try {
                    byte[] body = Files.readAllBytes(file.toPath());
                    HttpURLConnection c = (HttpURLConnection) new URL(MoggoPushGateway.base() + "crash").openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(body);
                    }
                    int code = c.getResponseCode();
                    c.disconnect();
                    if (code == 201 || code == 400 || code == 413) file.delete();
                } catch (Exception e) {
                    FileLog.e(e);
                    return; // offline: try next start
                }
            }
        }, 15000);
    }
}
