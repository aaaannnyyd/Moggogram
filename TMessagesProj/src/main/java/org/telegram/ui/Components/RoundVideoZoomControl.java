package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.telegram.messenger.AndroidUtilities;

import java.util.Locale;

/**
 * Moggogram: lens switcher for the round video camera.
 * Collapsed it is a pill of lens chips (".6  1×  2×"); dragging it sideways or holding it
 * turns it into a zoom ruler. In ruler-only mode the ruler is always shown.
 */
public class RoundVideoZoomControl extends View {

    public interface Listener {
        void onZoomRatioChanged(float ratio, boolean animated);
    }

    private static final int COLOR_PILL = 0x59000000;
    private static final int COLOR_CHIP = 0x40000000;
    private static final int COLOR_ACCENT = 0xFFFFD60A;
    private static final long COLLAPSE_DELAY_MS = 1500L;

    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint selectedTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int touchSlop;

    private Listener listener;
    private boolean sliderOnly;
    private float[] stops = {1f};
    private float minimum = 1f;
    private float maximum = 1f;
    private float ratio = 1f;

    private float expandProgress;
    private ValueAnimator expandAnimator;
    private ValueAnimator ratioAnimator;
    private boolean dragging;
    private boolean touchInside;
    private float downX;
    private float downY;
    private final Runnable collapseRunnable = () -> setExpanded(false);
    private final Runnable longPressRunnable = () -> {
        if (touchInside && !dragging) {
            dragging = true;
            setExpanded(true);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    };

    public RoundVideoZoomControl(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        pillPaint.setColor(COLOR_PILL);
        chipPaint.setColor(COLOR_CHIP);
        tickPaint.setColor(0xB3FFFFFF);
        tickPaint.setStrokeWidth(dp(1));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        accentPaint.setColor(COLOR_ACCENT);
        accentPaint.setStrokeWidth(dp(2));
        accentPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(dp(12));
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextAlign(Paint.Align.CENTER);
        selectedTextPaint.set(textPaint);
        selectedTextPaint.setColor(COLOR_ACCENT);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setSliderOnly(boolean sliderOnly) {
        this.sliderOnly = sliderOnly;
        expandProgress = sliderOnly ? 1f : 0f;
        invalidate();
    }

    public void setRange(float minimum, float maximum, float[] stops) {
        this.minimum = minimum;
        this.maximum = Math.max(minimum, maximum);
        this.stops = stops == null || stops.length == 0 ? new float[]{1f} : stops;
        ratio = clamp(ratio);
        invalidate();
    }

    public boolean hasZoom() {
        return maximum > minimum + 0.01f;
    }

    public float getRatio() {
        return ratio;
    }

    /** Updates the shown ratio without notifying the listener (pinch zoom, camera switch). */
    public void setRatio(float value) {
        if (ratioAnimator != null) ratioAnimator.cancel();
        ratio = clamp(value);
        invalidate();
    }

    /** Smallest lens stop that is not wider than the requested one. */
    public float stopFor(float requested) {
        float best = stops[0];
        for (float stop : stops) {
            if (stop <= requested + 0.01f) best = stop;
        }
        return best;
    }

    private float clamp(float value) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    // ---- geometry ----

    private float pillWidth() {
        float collapsed = stops.length * dp(38) + dp(6);
        float expanded = Math.min(getWidth() - dp(8), dp(272));
        return collapsed + (expanded - collapsed) * expandProgress;
    }

    private float pillHeight() {
        return dp(40) + dp(8) * expandProgress;
    }

    private float pillLeft() {
        return (getWidth() - pillWidth()) / 2f;
    }

    private float pillTop() {
        return (getHeight() - pillHeight()) / 2f;
    }

    private float rulerStart() {
        return pillLeft() + dp(22);
    }

    private float rulerEnd() {
        return pillLeft() + pillWidth() - dp(22);
    }

    private float ratioToPosition(float value) {
        if (!hasZoom()) return (rulerStart() + rulerEnd()) / 2f;
        double t = Math.log(value / minimum) / Math.log(maximum / minimum);
        return (float) (rulerStart() + (rulerEnd() - rulerStart()) * t);
    }

    private float positionToRatio(float x) {
        if (!hasZoom()) return ratio;
        float t = (x - rulerStart()) / Math.max(1f, rulerEnd() - rulerStart());
        t = Math.max(0f, Math.min(1f, t));
        return (float) (minimum * Math.pow(maximum / minimum, t));
    }

    private int chipAt(float x) {
        float left = pillLeft() + dp(3);
        int index = (int) ((x - left) / dp(38));
        return Math.max(0, Math.min(stops.length - 1, index));
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        if (stops.length <= 1 && !hasZoom()) return;
        float left = pillLeft();
        float top = pillTop();
        rect.set(left, top, left + pillWidth(), top + pillHeight());
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, pillPaint);

        float chipsAlpha = 1f - expandProgress;
        if (chipsAlpha > 0f) drawChips(canvas, chipsAlpha);
        if (expandProgress > 0f) drawRuler(canvas, expandProgress);
    }

    private void drawChips(Canvas canvas, float alpha) {
        float selectedStop = stopFor(ratio);
        float centerY = pillTop() + pillHeight() / 2f;
        float x = pillLeft() + dp(3) + dp(19);
        for (float stop : stops) {
            boolean selected = Math.abs(stop - selectedStop) < 0.01f;
            float radius = selected ? dp(16) : dp(13);
            chipPaint.setAlpha((int) (0x40 * alpha));
            canvas.drawCircle(x, centerY, radius, chipPaint);
            TextPaint paint = selected ? selectedTextPaint : textPaint;
            paint.setAlpha((int) (255 * alpha));
            paint.setTextSize(selected ? dp(12) : dp(11));
            String label = selected ? formatRatio(ratio, true) : formatRatio(stop, false);
            canvas.drawText(label, x, centerY - (paint.descent() + paint.ascent()) / 2f, paint);
            x += dp(38);
        }
        textPaint.setAlpha(255);
        selectedTextPaint.setAlpha(255);
    }

    private void drawRuler(Canvas canvas, float alpha) {
        float centerY = pillTop() + pillHeight() / 2f + dp(6);
        int ticks = 40;
        tickPaint.setAlpha((int) (0xB3 * alpha));
        for (int i = 0; i <= ticks; i++) {
            float x = rulerStart() + (rulerEnd() - rulerStart()) * i / ticks;
            float h = i % 5 == 0 ? dp(5) : dp(3);
            canvas.drawLine(x, centerY - h, x, centerY + h, tickPaint);
        }
        textPaint.setTextSize(dp(10));
        textPaint.setAlpha((int) (200 * alpha));
        for (float stop : stops) {
            float x = ratioToPosition(stop);
            canvas.drawText(formatRatio(stop, false), x, centerY - dp(9), textPaint);
        }
        float thumbX = ratioToPosition(ratio);
        accentPaint.setAlpha((int) (255 * alpha));
        canvas.drawLine(thumbX, centerY - dp(9), thumbX, centerY + dp(9), accentPaint);
        selectedTextPaint.setTextSize(dp(11));
        selectedTextPaint.setAlpha((int) (255 * alpha));
        float labelX = Math.max(rulerStart(), Math.min(rulerEnd(), thumbX));
        canvas.drawText(formatRatio(ratio, true), labelX, pillTop() + dp(13) - dp(4) * (1f - alpha), selectedTextPaint);
        textPaint.setTextSize(dp(12));
        textPaint.setAlpha(255);
        selectedTextPaint.setAlpha(255);
    }

    private static String formatRatio(float value, boolean withSign) {
        String text;
        if (value < 0.995f) {
            text = String.format(Locale.US, "%.1f", value);
            if (!withSign) text = text.substring(1); // ".6"
        } else if (Math.abs(value - Math.round(value)) < 0.05f) {
            text = String.valueOf(Math.round(value));
        } else {
            text = String.format(Locale.US, "%.1f", value);
        }
        return withSign ? text + "×" : text;
    }

    // ---- touch ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || getAlpha() < 0.5f || (stops.length <= 1 && !hasZoom())) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float left = pillLeft();
                float top = pillTop();
                if (event.getX() < left - dp(8) || event.getX() > left + pillWidth() + dp(8)
                        || event.getY() < top - dp(8) || event.getY() > top + pillHeight() + dp(8)) {
                    return false;
                }
                touchInside = true;
                dragging = sliderOnly || expandProgress > 0.5f;
                downX = event.getX();
                downY = event.getY();
                removeCallbacks(collapseRunnable);
                if (dragging) {
                    applyRatio(positionToRatio(event.getX()), false);
                } else {
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!touchInside) return false;
                if (!dragging && Math.abs(event.getX() - downX) > touchSlop) {
                    dragging = true;
                    removeCallbacks(longPressRunnable);
                    setExpanded(true);
                }
                if (dragging) applyRatio(positionToRatio(event.getX()), false);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPressRunnable);
                if (!touchInside) return false;
                touchInside = false;
                if (!dragging && event.getActionMasked() == MotionEvent.ACTION_UP
                        && Math.abs(event.getY() - downY) < touchSlop) {
                    float target = stops[chipAt(event.getX())];
                    animateRatio(target);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                dragging = false;
                if (!sliderOnly && expandProgress > 0f) postDelayed(collapseRunnable, COLLAPSE_DELAY_MS);
                return true;
            }
        }
        return false;
    }

    private void applyRatio(float value, boolean animated) {
        float clamped = clamp(value);
        float previousStop = stopFor(ratio);
        ratio = clamped;
        if (stopFor(ratio) != previousStop) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
        if (listener != null) listener.onZoomRatioChanged(ratio, animated);
    }

    public void animateRatio(float target) {
        if (ratioAnimator != null) ratioAnimator.cancel();
        ratioAnimator = ValueAnimator.ofFloat(ratio, clamp(target));
        ratioAnimator.setDuration(220L);
        ratioAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        ratioAnimator.addUpdateListener(a -> applyRatio((float) a.getAnimatedValue(), true));
        ratioAnimator.start();
    }

    private void setExpanded(boolean expanded) {
        if (sliderOnly) return;
        float target = expanded ? 1f : 0f;
        if (expandAnimator != null) expandAnimator.cancel();
        if (expandProgress == target) return;
        expandAnimator = ValueAnimator.ofFloat(expandProgress, target);
        expandAnimator.setDuration(220L);
        expandAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        expandAnimator.addUpdateListener(a -> {
            expandProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        expandAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(collapseRunnable);
        removeCallbacks(longPressRunnable);
        if (expandAnimator != null) expandAnimator.cancel();
        if (ratioAnimator != null) ratioAnimator.cancel();
    }
}
