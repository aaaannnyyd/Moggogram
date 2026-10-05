package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MoggoUgar;
import org.telegram.messenger.MoggoUgarHistory;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;

/** Grid of everything drawn with "@ugar": send again to any chat, copy the prompt, delete. */
public class MoggoUgarHistoryActivity extends BaseFragment {

    private final LruCache<File, Bitmap> thumbs = new LruCache<File, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(File key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private ArrayList<MoggoUgarHistory.Item> items = new ArrayList<>();
    private RecyclerListView listView;
    private TextView emptyView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MoggoUgarHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new GridLayoutManager(context, 3));
        listView.setPadding(AndroidUtilities.dp(2), AndroidUtilities.dp(2), AndroidUtilities.dp(2), AndroidUtilities.dp(2));
        listView.setClipToPadding(false);
        listView.setAdapter(new Adapter());
        listView.setOnItemClickListener((view, position) -> openItem(items.get(position)));
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.MoggoUgarHistoryEmpty));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 32, 0, 32, 0));

        reload();
        fragmentView = frame;
        return frame;
    }

    private void reload() {
        items = MoggoUgarHistory.list();
        if (listView != null && listView.getAdapter() != null) listView.getAdapter().notifyDataSetChanged();
        if (emptyView != null) emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openItem(MoggoUgarHistory.Item item) {
        Context context = getParentActivity();
        if (context == null) return;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        ImageView image = new ImageView(context);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(BitmapFactory.decodeFile(item.file.getAbsolutePath()));
        layout.addView(image, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));
        if (!TextUtils.isEmpty(item.prompt)) {
            TextView caption = new TextView(context);
            caption.setText(item.prompt);
            caption.setTextIsSelectable(true);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            caption.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            caption.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), 0);
            layout.addView(caption);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.Send), (d, w) -> sendTo(item));
        b.setNegativeButton(LocaleController.getString(R.string.MoggoUgarCopyPrompt), (d, w) -> {
            AndroidUtilities.addToClipboard("@ugar " + item.prompt);
            BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        });
        b.setNeutralButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            MoggoUgarHistory.remove(item);
            thumbs.remove(item.file);
            reload();
        });
        showDialog(b.create());
    }

    private void sendTo(MoggoUgarHistory.Item item) {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            for (int i = 0; i < dids.size(); i++) {
                MoggoUgar.sendToDialog(currentAccount, dids.get(i).dialogId, item.file, item.sticker, notify, scheduleDate);
            }
            fragment.finishFragment();
            return true;
        });
        presentFragment(picker);
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ImageView view = new ImageView(parent.getContext()) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, widthMeasureSpec);
                }
            };
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setPadding(AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1));
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ImageView view = (ImageView) holder.itemView;
            File file = items.get(position).file;
            view.setTag(file);
            Bitmap cached = thumbs.get(file);
            view.setImageBitmap(cached);
            if (cached != null) return;
            Utilities.globalQueue.postRunnable(() -> {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                if (bitmap == null) return;
                AndroidUtilities.runOnUIThread(() -> {
                    thumbs.put(file, bitmap);
                    if (file.equals(view.getTag())) view.setImageBitmap(bitmap);
                });
            });
        }
    }
}
