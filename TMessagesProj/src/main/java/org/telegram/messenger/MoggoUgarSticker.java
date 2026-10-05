package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Turns a picture drawn on a plain background into a Telegram sticker: the background connected
 * to the edges becomes transparent (flood fill from the border), the subject is cropped and fitted
 * into 512 px, saved as WebP. Telegram sends a WebP of that size as a sticker.
 */
public final class MoggoUgarSticker {

    private static final int WORK_SIDE = 1024;
    private static final int STICKER_SIDE = 512;
    private static final int HARD = 38;  // colour distance that surely is background
    private static final int SOFT = 80;  // up to this the edge is half transparent

    private MoggoUgarSticker() {
    }

    public static File make(File source) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeFile(source.getAbsolutePath(), opts);
            if (decoded == null) return null;
            int max = Math.max(decoded.getWidth(), decoded.getHeight());
            Bitmap bitmap = decoded;
            if (max > WORK_SIDE) {
                float k = WORK_SIDE / (float) max;
                bitmap = Bitmap.createScaledBitmap(decoded, Math.round(decoded.getWidth() * k), Math.round(decoded.getHeight() * k), true);
                decoded.recycle();
            }
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            int[] px = new int[w * h];
            bitmap.getPixels(px, 0, w, 0, 0, w, h);
            bitmap.recycle();

            int bg = background(px, w, h);
            removeBackground(px, w, h, bg);

            int minX = w, minY = h, maxX = -1, maxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((px[y * w + x] >>> 24) > 16) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < 0) return null;
            Bitmap cut = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            int cw = maxX - minX + 1, ch = maxY - minY + 1;
            int pad = Math.max(cw, ch) / 25;
            int side = Math.max(cw, ch) + pad * 2;
            float scale = STICKER_SIDE / (float) side;
            int outW = Math.max(1, Math.round((cw + pad * 2) * scale));
            int outH = Math.max(1, Math.round((ch + pad * 2) * scale));
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.scale(scale, scale);
            canvas.drawBitmap(cut, pad - minX, pad - minY, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            cut.recycle();

            File file = new File(AndroidUtilities.getCacheDir(), "ugar_sticker_" + System.currentTimeMillis() + ".webp");
            try (FileOutputStream stream = new FileOutputStream(file)) {
                Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP;
                out.compress(format, 100, stream);
            }
            out.recycle();
            return file;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** The most common colour of the border, which is what Gemini painted as the backdrop. */
    private static int background(int[] px, int w, int h) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int x = 0; x < w; x += 4) {
            for (int y : new int[]{0, h - 1}) {
                int c = px[y * w + x];
                r += Color.red(c);
                g += Color.green(c);
                b += Color.blue(c);
                n++;
            }
        }
        for (int y = 0; y < h; y += 4) {
            for (int x : new int[]{0, w - 1}) {
                int c = px[y * w + x];
                r += Color.red(c);
                g += Color.green(c);
                b += Color.blue(c);
                n++;
            }
        }
        return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    private static int distance(int c, int bg) {
        int dr = Color.red(c) - Color.red(bg), dg = Color.green(c) - Color.green(bg), db = Color.blue(c) - Color.blue(bg);
        return (int) Math.sqrt((dr * dr + dg * dg + db * db) / 3.0);
    }

    private static void removeBackground(int[] px, int w, int h, int bg) {
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            tail = push(px, seen, queue, tail, x, bg);
            tail = push(px, seen, queue, tail, (h - 1) * w + x, bg);
        }
        for (int y = 0; y < h; y++) {
            tail = push(px, seen, queue, tail, y * w, bg);
            tail = push(px, seen, queue, tail, y * w + w - 1, bg);
        }
        while (head < tail) {
            int i = queue[head++];
            int x = i % w, y = i / w;
            px[i] = 0;
            if (x > 0) tail = push(px, seen, queue, tail, i - 1, bg);
            if (x < w - 1) tail = push(px, seen, queue, tail, i + 1, bg);
            if (y > 0) tail = push(px, seen, queue, tail, i - w, bg);
            if (y < h - 1) tail = push(px, seen, queue, tail, i + w, bg);
        }
        // soften the edge: pixels touching the removed area fade by how close they are to the backdrop
        int[] copy = px.clone();
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int c = copy[i];
                if (c == 0) continue;
                if (copy[i - 1] != 0 && copy[i + 1] != 0 && copy[i - w] != 0 && copy[i + w] != 0) continue;
                int d = distance(c, bg);
                if (d >= SOFT) continue;
                int alpha = Math.max(0, Math.min(255, (d - HARD) * 255 / (SOFT - HARD)));
                px[i] = (alpha << 24) | (c & 0xffffff);
            }
        }
    }

    private static int push(int[] px, boolean[] seen, int[] queue, int tail, int i, int bg) {
        if (seen[i]) return tail;
        seen[i] = true;
        if (distance(px[i], bg) > HARD) return tail;
        queue[tail++] = i;
        return tail;
    }
}
