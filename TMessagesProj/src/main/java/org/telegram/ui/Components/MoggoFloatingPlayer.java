package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.util.Locale;

import xyz.nextalone.nagram.NaConfig;

/**
 * Moggogram: when the app goes to the background while a voice or round message is playing,
 * keeps a small draggable player on top of other apps — tap to pause, speed 1x..3x, close.
 */
public class MoggoFloatingPlayer implements NotificationCenter.NotificationCenterDelegate {

    private static final float[] SPEEDS = {1f, 1.5f, 2f, 2.5f, 3f};
    private static final int CIRCLE_DP = 112;
    private static final int RING_DP = 4;
    private static final int WIDTH_DP = CIRCLE_DP + RING_DP * 2 + 8;
    private static final int HEIGHT_DP = WIDTH_DP + 40;

    private static MoggoFloatingPlayer instance;

    public static void requestPermissionIfNeeded(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(activity)) return;
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void onAppBackground() {
        try {
            if (!NaConfig.INSTANCE.getMoggoFloatingPlayer().Bool()) return;
            Context context = ApplicationLoader.applicationContext;
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) return;
            MediaController controller = MediaController.getInstance();
            MessageObject playing = controller.getPlayingMessageObject();
            if (!isSupported(playing) || controller.isMessagePaused()) return;
            if (instance == null) instance = new MoggoFloatingPlayer(context);
            instance.show(playing);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void onAppForeground() {
        if (instance != null) instance.hide();
    }

    private static boolean isSupported(MessageObject messageObject) {
        return messageObject != null && (messageObject.isVoice() || messageObject.isRoundVideo());
    }

    private final Context context;
    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
    private final SharedPreferences preferences;
    private final FrameLayout root;
    private final FrameLayout circle;
    private final BackupImageView avatarView;
    private final TextView speedView;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private final RectF ringRect = new RectF();
    private final int touchSlop;

    private TextureView textureView;
    private MessageObject messageObject;
    private int account = -1;
    private boolean added;

    private MoggoFloatingPlayer(Context context) {
        this.context = context;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        preferences = context.getSharedPreferences("moggo_floating_player", Context.MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3));
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setColor(0xFFFFFFFF);
        ringBackgroundPaint.setStyle(Paint.Style.STROKE);
        ringBackgroundPaint.setStrokeWidth(dp(3));
        ringBackgroundPaint.setColor(0x40FFFFFF);
        dimPaint.setColor(0x66000000);
        iconPaint.setColor(0xFFFFFFFF);

        root = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                float cx = getWidth() / 2f;
                float cy = dp(WIDTH_DP) / 2f;
                float radius = dp(CIRCLE_DP) / 2f + dp(RING_DP) - dp(1.5f);
                ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawOval(ringRect, ringBackgroundPaint);
                float progress = messageObject == null ? 0f : messageObject.audioProgress;
                canvas.drawArc(ringRect, -90, 360 * Math.max(0f, Math.min(1f, progress)), false, ringPaint);
                super.dispatchDraw(canvas);
                if (MediaController.getInstance().isMessagePaused()) {
                    canvas.drawCircle(cx, cy, dp(CIRCLE_DP) / 2f, dimPaint);
                    float s = dp(16);
                    iconPath.reset();
                    iconPath.moveTo(cx - s * 0.6f, cy - s);
                    iconPath.lineTo(cx + s, cy);
                    iconPath.lineTo(cx - s * 0.6f, cy + s);
                    iconPath.close();
                    canvas.drawPath(iconPath, iconPaint);
                }
            }
        };
        root.setWillNotDraw(false);

        circle = new FrameLayout(context);
        circle.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        circle.setClipToOutline(true);
        circle.setBackgroundColor(0xFF1C1C1E);
        root.addView(circle, LayoutHelper.createFrame(CIRCLE_DP, CIRCLE_DP, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, RING_DP + 4, 0, 0));

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(CIRCLE_DP / 2));
        circle.addView(avatarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xE61C1C1E);
        pill.setCornerRadius(dp(16));
        controls.setBackground(pill);
        root.addView(controls, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        speedView = new TextView(context);
        speedView.setTextColor(0xFFFFFFFF);
        speedView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        speedView.setTypeface(AndroidUtilities.bold());
        speedView.setGravity(Gravity.CENTER);
        speedView.setPadding(dp(12), 0, dp(8), 0);
        speedView.setOnClickListener(v -> cycleSpeed());
        controls.addView(speedView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));

        ImageView closeView = new ImageView(context);
        closeView.setImageResource(R.drawable.ic_close_white);
        closeView.setScaleType(ImageView.ScaleType.CENTER);
        closeView.setOnClickListener(v -> close());
        controls.addView(closeView, LayoutHelper.createLinear(32, 32, 0, 0, 4, 0));

        circle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) dragging = true;
                        if (dragging && added) {
                            params.x = (int) (startX + dx);
                            params.y = (int) (startY + dy);
                            try {
                                windowManager.updateViewLayout(root, params);
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            preferences.edit().putInt("x", params.x).putInt("y", params.y).apply();
                        } else {
                            togglePlay();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });

        params.width = dp(WIDTH_DP);
        params.height = dp(HEIGHT_DP);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.format = PixelFormat.TRANSLUCENT;
        params.type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    }

    private void show(MessageObject playing) {
        if (!added) {
            params.x = preferences.getInt("x", AndroidUtilities.displaySize.x - dp(WIDTH_DP) - dp(12));
            params.y = preferences.getInt("y", AndroidUtilities.displaySize.y / 3);
            try {
                windowManager.addView(root, params);
                added = true;
            } catch (Exception e) {
                FileLog.e(e);
                return;
            }
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingSpeedChanged);
        }
        bind(playing);
    }

    private void bind(MessageObject playing) {
        if (account != playing.currentAccount) {
            if (account >= 0) removeAccountObservers();
            account = playing.currentAccount;
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            center.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            center.addObserver(this, NotificationCenter.messagePlayingDidReset);
            center.addObserver(this, NotificationCenter.messagePlayingDidStart);
        }
        messageObject = playing;
        if (playing.isRoundVideo()) {
            avatarView.setVisibility(View.GONE);
            if (textureView == null) {
                textureView = new TextureView(context);
                circle.addView(textureView, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
            if (!MediaController.getInstance().attachFloatingTextureView(textureView)) {
                avatarView.setVisibility(View.VISIBLE);
            }
        } else {
            releaseTexture();
            avatarView.setVisibility(View.VISIBLE);
        }
        if (avatarView.getVisibility() == View.VISIBLE) {
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            long fromId = playing.getFromChatId();
            MessagesController messagesController = MessagesController.getInstance(account);
            if (fromId > 0) {
                TLRPC.User user = messagesController.getUser(fromId);
                avatarDrawable.setInfo(account, user);
                avatarView.setForUserOrChat(user, avatarDrawable);
            } else {
                TLRPC.Chat chat = messagesController.getChat(-fromId);
                avatarDrawable.setInfo(account, chat);
                avatarView.setForUserOrChat(chat, avatarDrawable);
            }
        }
        updateSpeed();
        root.invalidate();
    }

    private void releaseTexture() {
        if (textureView != null) {
            MediaController.getInstance().detachFloatingTextureView(textureView);
            circle.removeView(textureView);
            textureView = null;
        }
    }

    private void removeAccountObservers() {
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        center.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        center.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        center.removeObserver(this, NotificationCenter.messagePlayingDidStart);
    }

    private void hide() {
        if (!added) return;
        releaseTexture();
        if (account >= 0) removeAccountObservers();
        account = -1;
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingSpeedChanged);
        try {
            windowManager.removeView(root);
        } catch (Exception e) {
            FileLog.e(e);
        }
        added = false;
        messageObject = null;
    }

    private void togglePlay() {
        MediaController controller = MediaController.getInstance();
        MessageObject playing = controller.getPlayingMessageObject();
        if (playing == null) {
            hide();
            return;
        }
        if (controller.isMessagePaused()) {
            controller.playMessage(playing);
        } else {
            controller.pauseMessage(playing);
        }
        root.invalidate();
    }

    private void close() {
        MediaController controller = MediaController.getInstance();
        MessageObject playing = controller.getPlayingMessageObject();
        if (playing != null && !controller.isMessagePaused()) controller.pauseMessage(playing);
        hide();
    }

    private void cycleSpeed() {
        MediaController controller = MediaController.getInstance();
        float current = controller.getPlaybackSpeed(false);
        float next = SPEEDS[0];
        for (float speed : SPEEDS) {
            if (speed > current + 0.05f) {
                next = speed;
                break;
            }
        }
        controller.setPlaybackSpeed(false, next);
        updateSpeed();
    }

    private void updateSpeed() {
        float speed = MediaController.getInstance().getPlaybackSpeed(false);
        String text = Math.abs(speed - Math.round(speed)) < 0.05f
                ? String.valueOf(Math.round(speed))
                : String.format(Locale.US, "%.1f", speed);
        speedView.setText(text + "×");
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (!added) return;
        if (id == NotificationCenter.messagePlayingProgressDidChanged
                || id == NotificationCenter.messagePlayingPlayStateChanged) {
            root.invalidate();
        } else if (id == NotificationCenter.messagePlayingSpeedChanged) {
            updateSpeed();
        } else if (id == NotificationCenter.messagePlayingDidStart) {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (isSupported(playing)) {
                bind(playing);
            } else {
                hide();
            }
        } else if (id == NotificationCenter.messagePlayingDidReset) {
            if (MediaController.getInstance().getPlayingMessageObject() == null) hide();
        }
    }
}
