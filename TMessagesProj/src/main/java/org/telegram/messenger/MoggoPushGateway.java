package org.telegram.messenger;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import tw.nekomimi.nekogram.utils.WebPushDecryptor;
import xyz.nextalone.nagram.NaConfig;

/**
 * Moggogram push path without a persistent connection, the same UX as official Telegram:
 * Telegram sends Web Push (token_type 10) and simple-push wake-ups (token_type 4) to our
 * Cloudflare gateway (push-gateway/), which relays them through our Firebase project. The
 * payload stays end-to-end encrypted: only this device has the Web Push private key.
 */
public final class MoggoPushGateway {

    private MoggoPushGateway() {
    }

    public static String base() {
        String url = BuildConfig.MOGGO_PUSH_GATEWAY;
        if (TextUtils.isEmpty(url)) return null;
        return url.endsWith("/") ? url : url + "/";
    }

    public static boolean isConfigured() {
        return base() != null;
    }

    /** Google push type selected and a gateway compiled in. */
    public static boolean isActive() {
        int type = NaConfig.INSTANCE.getPushServiceType().Int();
        return isConfigured() && (type == 1 || type == 3);
    }

    public static String savedFcmToken() {
        return ApplicationLoader.applicationContext.getSharedPreferences("moggo_push", android.content.Context.MODE_PRIVATE)
                .getString("fcm_token", null);
    }

    public static void registerToken(String fcmToken) {
        String base = base();
        if (base == null || TextUtils.isEmpty(fcmToken)) return;
        ApplicationLoader.applicationContext.getSharedPreferences("moggo_push", android.content.Context.MODE_PRIVATE)
                .edit().putString("fcm_token", fcmToken).apply();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                byte[][] keys = UnifiedPushService.getOrCreateWebPushKeys();
                if (keys == null) {
                    PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null);
                    return;
                }
                String token = URLEncoder.encode(fcmToken, "UTF-8");
                JSONObject keyObject = new JSONObject();
                keyObject.put("p256dh", Base64.encodeToString(keys[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP));
                keyObject.put("auth", Base64.encodeToString(keys[2], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP));
                JSONObject tokenObject = new JSONObject();
                tokenObject.put("endpoint", base + "wp/" + token);
                tokenObject.put("keys", keyObject);
                PushListenerController.sendWebPushRegistrationToServer(tokenObject.toString(), base + "sp/" + token);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** @return true if the FCM message came from our gateway and was handled. */
    public static boolean handleMessage(Map<String, String> data, long time) {
        String type = data == null ? null : data.get("t");
        if (type == null) return false;
        try {
            if ("test".equals(type)) {
                MoggoDiagnostics.onTestPushReceived();
            } else if ("sp".equals(type)) {
                wakeUp();
            } else if ("wp".equals(type)) {
                processWebPush(Base64.decode(data.get("d"), Base64.DEFAULT), time);
            } else if ("wpk".equals(type)) {
                String packet = fetchBlob(data.get("k"));
                if (packet != null) {
                    processWebPush(Base64.decode(packet, Base64.DEFAULT), time);
                } else {
                    wakeUp();
                }
            }
        } catch (Exception e) {
            FileLog.e("moggo push: " + e);
            wakeUp();
        }
        return true;
    }

    private static void processWebPush(byte[] packet, long time) throws Exception {
        byte[][] keys = UnifiedPushService.getOrCreateWebPushKeys();
        if (keys == null) throw new IllegalStateException("no web push keys");
        byte[] plaintext = WebPushDecryptor.decrypt(packet, keys[0], keys[1], keys[2]);
        String encoded = new JSONObject(new String(plaintext, StandardCharsets.UTF_8)).getString("p");
        PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_WEB, encoded, time);
    }

    private static String fetchBlob(String id) {
        String base = base();
        if (base == null || TextUtils.isEmpty(id)) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + "k/" + id).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void wakeUp() {
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            Utilities.stageQueue.postRunnable(() -> {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        ConnectionsManager.onInternalPushReceived(a);
                        ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                    }
                }
            });
        });
    }
}
