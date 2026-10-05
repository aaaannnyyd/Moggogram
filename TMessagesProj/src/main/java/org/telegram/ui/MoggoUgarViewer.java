package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MoggoUgar;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.ArrayList;

/** Full screen look at the "@ugar" variants: swipe between them, send the one you like. */
public final class MoggoUgarViewer {

    private MoggoUgarViewer() {
    }

    public static void show(ChatActivity fragment, ArrayList<File> files, int index) {
        Activity activity = fragment.getParentActivity();
        if (activity == null || files.isEmpty()) return;
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window window = dialog.getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xff000000);

        ViewPager pager = new ViewPager(activity);
        pager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return files.size();
            }

            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                ImageView image = new ImageView(activity);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(decode(files.get(position)));
                image.setOnClickListener(v -> dialog.dismiss());
                container.addView(image, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                return image;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }
        });
        root.addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        TextView counter = new TextView(activity);
        counter.setTextColor(0xffffffff);
        counter.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        counter.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(counter, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.TOP | Gravity.LEFT, 20, 24, 0, 0));

        ImageView close = new ImageView(activity);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setBackground(Theme.createSelectorDrawable(0x40ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(close, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.RIGHT, 0, 24, 8, 0));

        TextView send = new TextView(activity);
        send.setText(LocaleController.getString(R.string.Send));
        send.setTextColor(0xffffffff);
        send.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        send.setTypeface(AndroidUtilities.bold());
        send.setGravity(Gravity.CENTER);
        send.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24), Theme.getColor(Theme.key_featuredStickers_addButton), 0x30ffffff));
        send.setOnClickListener(v -> {
            dialog.dismiss();
            MoggoUgar.send(fragment, files.get(pager.getCurrentItem()));
        });
        root.addView(send, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 24, 0, 24, 32));

        Runnable updateCounter = () -> counter.setText(files.size() > 1
                ? LocaleController.formatString(R.string.MoggoUgarViewerCounter, pager.getCurrentItem() + 1, files.size()) : "");
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updateCounter.run();
            }
        });
        pager.setCurrentItem(Math.max(0, Math.min(index, files.size() - 1)), false);
        updateCounter.run();

        dialog.setContentView(root);
        dialog.show();
    }

    private static Bitmap decode(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int screen = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        int side = Math.max(bounds.outWidth, bounds.outHeight);
        while (side / (opts.inSampleSize * 2) >= screen) opts.inSampleSize *= 2;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }
}
