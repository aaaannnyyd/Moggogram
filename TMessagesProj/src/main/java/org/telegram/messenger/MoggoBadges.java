package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import xyz.nextalone.nagram.NaConfig;

/**
 * Moggogram badge next to the names of other Moggogram users.
 * Registry lives in the push gateway (push-gateway/src/index.js): a client proves its account by
 * sending "/start moggogram h_<sha256 of a secret made on the phone>" to the badge bot from it. Lookups are
 * batched, cached for a day and refresh names through updateInterfaces.
 */
public final class MoggoBadges {

    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final long CHECK_DELAY_MS = 400;
    private static final long REGISTER_RETRY_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_BATCH = 200;

    private static final HashMap<Long, Boolean> results = new HashMap<>();
    private static final HashMap<Long, Long> checkedAt = new HashMap<>();
    private static final HashSet<Long> pending = new HashSet<>();
    private static final HashSet<Integer> pendingAccounts = new HashSet<>();
    private static boolean checkScheduled;
    private static final HashSet<Integer> registering = new HashSet<>();

    private MoggoBadges() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("moggo_badges", Context.MODE_PRIVATE);
    }

    private static boolean available() {
        return MoggoPushGateway.isConfigured() && !TextUtils.isEmpty(BuildConfig.MOGGO_BADGE_BOT);
    }

    // ---- lookups (UI thread) ----

    /** Whether to draw the badge for this user. Unknown users are queued and refreshed later. */
    public static boolean has(int account, long userId) {
        if (userId <= 0 || !available() || !NaConfig.INSTANCE.getMoggoShowBadges().Bool()) return false;
        if (userId == UserConfig.getInstance(account).getClientUserId()) {
            return NaConfig.INSTANCE.getMoggoShowMyBadge().Bool() && !TextUtils.isEmpty(secret(userId));
        }
        Boolean cached = results.get(userId);
        Long time = checkedAt.get(userId);
        if (time == null || SystemClock.elapsedRealtime() - time > CACHE_TTL_MS) {
            queue(account, userId);
        }
        return cached != null && cached;
    }

    public static boolean has(int account, TLRPC.User user) {
        return user != null && !user.bot && !user.verified && has(account, user.id);
    }

    private static void queue(int account, long userId) {
        if (pending.contains(userId)) return;
        pending.add(userId);
        pendingAccounts.add(account);
        if (!checkScheduled) {
            checkScheduled = true;
            AndroidUtilities.runOnUIThread(MoggoBadges::flush, CHECK_DELAY_MS);
        }
    }

    private static void flush() {
        checkScheduled = false;
        if (pending.isEmpty()) return;
        ArrayList<Long> ids = new ArrayList<>(pending);
        if (ids.size() > MAX_BATCH) ids = new ArrayList<>(ids.subList(0, MAX_BATCH));
        final ArrayList<Long> batch = ids;
        final ArrayList<Integer> accounts = new ArrayList<>(pendingAccounts);
        pending.removeAll(batch);
        if (pending.isEmpty()) pendingAccounts.clear();
        Utilities.globalQueue.postRunnable(() -> {
            HashSet<Long> found = null;
            try {
                JSONArray array = new JSONArray();
                for (long id : batch) array.put(id);
                JSONObject response = new JSONObject(post("badge/check", new JSONObject().put("ids", array).toString()));
                JSONArray hits = response.getJSONArray("ids");
                found = new HashSet<>();
                for (int i = 0; i < hits.length(); i++) found.add(hits.getLong(i));
            } catch (Exception e) {
                FileLog.e("moggo badges: " + e);
            }
            final HashSet<Long> result = found;
            AndroidUtilities.runOnUIThread(() -> {
                if (result == null) return; // network error: ask again later
                long now = SystemClock.elapsedRealtime();
                boolean changed = false;
                for (long id : batch) {
                    boolean value = result.contains(id);
                    Boolean old = results.put(id, value);
                    checkedAt.put(id, now);
                    if (old == null ? value : old != value) changed = true;
                }
                if (changed) {
                    for (int account : accounts) {
                        NotificationCenter.getInstance(account).postNotificationName(
                                NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
                    }
                }
                if (!pending.isEmpty() && !checkScheduled) {
                    checkScheduled = true;
                    AndroidUtilities.runOnUIThread(MoggoBadges::flush, CHECK_DELAY_MS);
                }
            });
        });
    }

    // ---- own badge ----

    private static String secret(long userId) {
        return prefs().getString("secret_" + userId, null);
    }

    /** Called after the account is ready; registers or removes the own badge per the setting. */
    public static void syncOwnBadge(int account) {
        if (!available()) return;
        UserConfig userConfig = UserConfig.getInstance(account);
        if (!userConfig.isClientActivated()) return;
        long userId = userConfig.getClientUserId();
        boolean want = NaConfig.INSTANCE.getMoggoShowMyBadge().Bool();
        String secret = secret(userId);
        if (want && secret == null) {
            long lastTry = prefs().getLong("try_" + userId, 0);
            if (System.currentTimeMillis() - lastTry < REGISTER_RETRY_MS) return;
            register(account, userId);
        } else if (!want && secret != null) {
            unregister(userId, secret);
        }
    }

    private static void register(int account, long userId) {
        if (!registering.add(account)) return;
        prefs().edit().putLong("try_" + userId, System.currentTimeMillis()).apply();
        MessagesController controller = MessagesController.getInstance(account);
        controller.getUserNameResolver().resolve(BuildConfig.MOGGO_BADGE_BOT, botId -> {
            if (botId == null || botId <= 0) {
                registering.remove(account);
                return;
            }
            // The secret never leaves the phone: the bot only sees its SHA-256 (the gateway stores it
            // from the message Telegram vouches for), so nobody can intercept it on the way back.
            final String secret = randomHex(24);
            TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
            req.peer = controller.getInputPeer(botId);
            req.message = "/start moggogram h_" + sha256Hex(secret);
            req.random_id = Utilities.random.nextLong();
            req.silent = true;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (response instanceof TLRPC.Updates) {
                    controller.processUpdates((TLRPC.Updates) response, false);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    controller.deleteDialog(botId, 0, true);
                    registering.remove(account);
                    if (error != null) return;
                    prefs().edit().putString("secret_" + userId, secret).apply();
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
                }, 1500);
            });
        });
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        Utilities.random.nextBytes(buffer);
        return Utilities.bytesToHex(buffer).toLowerCase(java.util.Locale.ROOT);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return Utilities.bytesToHex(digest).toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void unregister(long userId, String secret) {
        prefs().edit().remove("secret_" + userId).remove("try_" + userId).apply();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                post("badge/remove", new JSONObject().put("id", userId).put("secret", secret).toString());
            } catch (Exception e) {
                FileLog.e("moggo badge remove: " + e);
            }
        });
    }

    // ---- http ----

    private static String post(String path, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(MoggoPushGateway.base() + path).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new IllegalStateException("http " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    // ---- drawable ----

    private static Bitmap bitmap;

    public static Drawable drawable(int size) {
        if (bitmap == null) {
            bitmap = BitmapFactory.decodeResource(ApplicationLoader.applicationContext.getResources(), R.drawable.moggo_badge);
        }
        return new BadgeDrawable(bitmap, size);
    }

    private static final class BadgeDrawable extends Drawable {
        private final Bitmap bitmap;
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect src;

        BadgeDrawable(Bitmap bitmap, int size) {
            this.bitmap = bitmap;
            this.size = size;
            src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        private final Rect dst = new Rect();

        // The glyph is wide (about 1.5:1): fit it to the slot width and centre it vertically, so it
        // sits on the middle of the capitals instead of floating like a square icon would.
        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            int w = b.width();
            int h = Math.round(w * bitmap.getHeight() / (float) bitmap.getWidth());
            int top = b.top + (b.height() - h) / 2;
            dst.set(b.left, top, b.right, top + h);
            canvas.drawBitmap(bitmap, src, dst, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }
    }
}
