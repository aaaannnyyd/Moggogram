package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MoggoUgar;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.io.File;
import java.util.HashMap;

/**
 * "@ugar" results above the message field, like inline bot results: a spinner with a timer while
 * Gemini draws, then the pictures. Tap sends, long press shows it big. The chat stays usable, and
 * the job lives on in MoggoUgar when the chat is closed.
 */
public class MoggoUgarPanel extends FrameLayout implements MoggoUgar.Listener {

    private static final int THUMB = 84;

    private final ChatActivity fragment;
    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView title;
    private final LinearLayout row;
    private final LinearLayout styles;
    private final HashMap<File, Bitmap> thumbs = new HashMap<>();
    private final Runnable ticker = this::tick;
    private TextView timerView;
    private Runnable onVisibilityChanged;

    public MoggoUgarPanel(Context context, ChatActivity fragment, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.fragment = fragment;
        this.resourcesProvider = resourcesProvider;
        setVisibility(GONE);
        setClickable(true);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36));

        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 14, 0, 4, 0));

        header.addView(iconButton(context, R.drawable.msg_retry, v -> MoggoUgar.again(MoggoUgar.jobKey(fragment))), LayoutHelper.createLinear(36, 36));
        header.addView(iconButton(context, R.drawable.ic_close_white, v -> MoggoUgar.dismiss(MoggoUgar.jobKey(fragment))), LayoutHelper.createLinear(36, 36, 0, 0, 6, 0));

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, THUMB, 0, 0, 0, 8));

        HorizontalScrollView stylesScroll = new HorizontalScrollView(context);
        stylesScroll.setHorizontalScrollBarEnabled(false);
        styles = new LinearLayout(context);
        styles.setOrientation(LinearLayout.HORIZONTAL);
        styles.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        stylesScroll.addView(styles, new HorizontalScrollView.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        content.addView(stylesScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 30, 0, 0, 0, 10));
        for (int i = 0; i < MoggoUgar.STYLE_NAMES.length; i++) {
            final int style = i;
            TextView chip = new TextView(context);
            chip.setText(LocaleController.getString(MoggoUgar.STYLE_NAMES[i]));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
            chip.setOnClickListener(v -> MoggoUgar.setStyle(MoggoUgar.jobKey(fragment), style));
            LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30);
            lp.rightMargin = AndroidUtilities.dp(6);
            styles.addView(chip, lp);
        }
    }

    private void updateStyles(int selected) {
        for (int i = 0; i < styles.getChildCount(); i++) {
            TextView chip = (TextView) styles.getChildAt(i);
            boolean on = i == selected;
            int accent = color(Theme.key_featuredStickers_addButton);
            chip.setTextColor(on ? 0xffffffff : color(Theme.key_windowBackgroundWhiteBlackText));
            chip.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(15), on ? accent : Theme.multAlpha(color(Theme.key_windowBackgroundWhiteGrayText), 0.12f)));
        }
    }

    public void setOnVisibilityChanged(Runnable r) {
        onVisibilityChanged = r;
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private ImageView iconButton(Context context, int icon, View.OnClickListener click) {
        ImageView button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(icon);
        button.setColorFilter(color(Theme.key_chat_messagePanelIcons));
        button.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        button.setOnClickListener(click);
        return button;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        MoggoUgar.addListener(this);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        MoggoUgar.removeListener(this);
        AndroidUtilities.cancelRunOnUIThread(ticker);
    }

    @Override
    public void onUgarJobChanged(String key) {
        if (key.equals(MoggoUgar.jobKey(fragment))) update();
    }

    public void update() {
        MoggoUgar.Job job = MoggoUgar.getJob(MoggoUgar.jobKey(fragment));
        boolean show = job != null;
        if ((getVisibility() == VISIBLE) != show) {
            setVisibility(show ? VISIBLE : GONE);
            if (onVisibilityChanged != null) onVisibilityChanged.run();
        }
        AndroidUtilities.cancelRunOnUIThread(ticker);
        row.removeAllViews();
        timerView = null;
        if (!show) {
            thumbs.clear();
            return;
        }
        updateStyles(job.style);
        String prompt = (job.sticker ? "@ugar стикер " : "@ugar ") + (job.prompt == null ? "" : job.prompt);
        boolean hint = !job.images.isEmpty() && !job.isRunning();
        title.setText(hint ? LocaleController.getString(R.string.MoggoUgarPanelHint) : prompt);

        for (File file : job.images) row.addView(imageTile(file), tileParams());
        for (String error : job.errors) row.addView(errorTile(error), tileParams());
        if (job.isRunning()) {
            row.addView(loadingTile(), tileParams());
            tick();
        }
    }

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams lp = LayoutHelper.createLinear(THUMB, THUMB);
        lp.rightMargin = AndroidUtilities.dp(6);
        return lp;
    }

    private void rounded(View view) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), AndroidUtilities.dp(10));
            }
        });
        view.setClipToOutline(true);
    }

    private View imageTile(File file) {
        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bitmap = thumbs.get(file);
        if (bitmap == null) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 4;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap != null) thumbs.put(file, bitmap);
        }
        image.setImageBitmap(bitmap);
        rounded(image);
        image.setOnClickListener(v -> MoggoUgar.send(fragment, file));
        image.setOnLongClickListener(v -> {
            MoggoUgar.showPreview(fragment, file);
            return true;
        });
        return image;
    }

    private View errorTile(String error) {
        TextView text = new TextView(getContext());
        text.setText(LocaleController.getString(R.string.MoggoUgarFailed));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        text.setGravity(Gravity.CENTER);
        text.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
        text.setTextColor(color(Theme.key_text_RedRegular));
        text.setBackgroundColor(Theme.multAlpha(color(Theme.key_text_RedRegular), 0.08f));
        rounded(text);
        text.setOnClickListener(v -> MoggoUgar.showError(fragment, error));
        return text;
    }

    private View loadingTile() {
        FrameLayout tile = new FrameLayout(getContext());
        tile.setBackgroundColor(Theme.multAlpha(color(Theme.key_windowBackgroundWhiteGrayText), 0.08f));
        rounded(tile);
        RadialProgressView progress = new RadialProgressView(getContext(), resourcesProvider);
        progress.setSize(AndroidUtilities.dp(24));
        progress.setProgressColor(color(Theme.key_featuredStickers_addButton));
        tile.addView(progress, LayoutHelper.createFrame(32, 32, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 16, 0, 0));
        timerView = new TextView(getContext());
        timerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        timerView.setGravity(Gravity.CENTER);
        timerView.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        tile.addView(timerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 0, 0, 0, 12));
        return tile;
    }

    private void tick() {
        MoggoUgar.Job job = MoggoUgar.getJob(MoggoUgar.jobKey(fragment));
        if (job == null || !job.isRunning() || timerView == null) return;
        int seconds = (int) ((SystemClock.elapsedRealtime() - job.startedAt) / 1000);
        timerView.setText(LocaleController.formatString(R.string.MoggoUgarDrawing, seconds));
        AndroidUtilities.runOnUIThread(ticker, 1000);
    }
}
