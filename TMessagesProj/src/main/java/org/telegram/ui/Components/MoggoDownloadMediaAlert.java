package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MoggoMediaDownloader;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioColorCell;

import java.util.ArrayList;

/** Moggogram: "Download media" dialog — period + media types, then a summary before saving. */
public class MoggoDownloadMediaAlert {

    private static final int[] PERIOD_DAYS = {1, 7, 30, 365, 0};
    private static final int[] PERIOD_TITLES = {
            R.string.MoggoPeriodDay,
            R.string.MoggoPeriodWeek,
            R.string.MoggoPeriodMonth,
            R.string.MoggoPeriodYear,
            R.string.MoggoPeriodAll
    };
    private static final int[] TYPES = {
            MoggoMediaDownloader.TYPE_PHOTO,
            MoggoMediaDownloader.TYPE_VIDEO,
            MoggoMediaDownloader.TYPE_FILE,
            MoggoMediaDownloader.TYPE_VOICE,
            MoggoMediaDownloader.TYPE_MUSIC
    };
    private static final int[] TYPE_TITLES = {
            R.string.MoggoTypePhotos,
            R.string.MoggoTypeVideos,
            R.string.MoggoTypeFiles,
            R.string.MoggoTypeVoice,
            R.string.MoggoTypeMusic
    };

    private static int selectedPeriod = 1;
    private static int selectedTypes = MoggoMediaDownloader.TYPE_PHOTO | MoggoMediaDownloader.TYPE_VIDEO;
    private static boolean asZip;

    public static void show(BaseFragment fragment, long dialogId) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        HeaderCell periodHeader = new HeaderCell(activity, resourcesProvider);
        periodHeader.setText(getString(R.string.MoggoDownloadPeriod));
        layout.addView(periodHeader);
        RadioColorCell[] periodCells = new RadioColorCell[PERIOD_DAYS.length];
        for (int i = 0; i < PERIOD_DAYS.length; i++) {
            RadioColorCell cell = new RadioColorCell(activity, resourcesProvider);
            cell.setPadding(dp(4), 0, dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground, resourcesProvider),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
            cell.setTextAndValue(getString(PERIOD_TITLES[i]), i == selectedPeriod);
            final int index = i;
            cell.setOnClickListener(v -> {
                selectedPeriod = index;
                for (int j = 0; j < periodCells.length; j++) periodCells[j].setChecked(j == index, true);
            });
            periodCells[i] = cell;
            layout.addView(cell);
        }

        HeaderCell typesHeader = new HeaderCell(activity, resourcesProvider);
        typesHeader.setText(getString(R.string.MoggoDownloadTypes));
        layout.addView(typesHeader);
        for (int i = 0; i < TYPES.length; i++) {
            CheckBoxCell cell = new CheckBoxCell(activity, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, resourcesProvider);
            cell.setBackground(Theme.getSelectorDrawable(false));
            final int type = TYPES[i];
            cell.setText(getString(TYPE_TITLES[i]), "", (selectedTypes & type) != 0, false);
            cell.setOnClickListener(v -> {
                selectedTypes ^= type;
                cell.setChecked((selectedTypes & type) != 0, true);
            });
            layout.addView(cell);
        }

        HeaderCell zipHeader = new HeaderCell(activity, resourcesProvider);
        zipHeader.setText(getString(R.string.MoggoDownloadHow));
        layout.addView(zipHeader);
        CheckBoxCell zipCell = new CheckBoxCell(activity, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, resourcesProvider);
        zipCell.setBackground(Theme.getSelectorDrawable(false));
        zipCell.setText(getString(R.string.MoggoDownloadZip), "", asZip, false);
        zipCell.setOnClickListener(v -> {
            asZip = !asZip;
            zipCell.setChecked(asZip, true);
        });
        layout.addView(zipCell);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(layout);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(getString(R.string.MoggoDownloadMedia));
        builder.setView(scrollView);
        builder.setPositiveButton(getString(R.string.MoggoDownloadFind), (dialog, which) -> {
            if (selectedTypes == 0) return;
            find(fragment, dialogId, selectedPeriod, selectedTypes);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static void find(BaseFragment fragment, long dialogId, int period, int types) {
        Context context = fragment.getParentActivity();
        if (context == null) return;
        int account = fragment.getCurrentAccount();
        int days = PERIOD_DAYS[period];
        int minDate = days == 0 ? 0 : ConnectionsManager.getInstance(account).getCurrentTime() - days * 86400;

        AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        MoggoMediaDownloader.search(account, dialogId, minDate, types, (messages, totalBytes, truncated) -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            confirm(fragment, account, messages, totalBytes, truncated);
        });
    }

    private static String zipName(BaseFragment fragment, int account) {
        String title = "chat";
        if (fragment instanceof org.telegram.ui.ChatActivity) {
            org.telegram.ui.ChatActivity chat = (org.telegram.ui.ChatActivity) fragment;
            if (chat.getCurrentUser() != null) title = org.telegram.messenger.UserObject.getUserName(chat.getCurrentUser());
            else if (chat.getCurrentChat() != null) title = chat.getCurrentChat().title;
        }
        String date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        return "Moggogram_" + title + "_" + date;
    }

    private static void confirm(BaseFragment fragment, int account, ArrayList<MessageObject> messages, long totalBytes, boolean truncated) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle(getString(R.string.MoggoDownloadMedia));
        if (messages.isEmpty()) {
            builder.setMessage(getString(R.string.MoggoDownloadNothing));
            builder.setPositiveButton(getString(R.string.OK), null);
        } else {
            String text = LocaleController.formatString(asZip ? R.string.MoggoDownloadFoundZip : R.string.MoggoDownloadFound,
                    messages.size(), AndroidUtilities.formatFileSize(totalBytes));
            if (truncated) {
                text += "\n\n" + LocaleController.formatString(R.string.MoggoDownloadLimit, MoggoMediaDownloader.MAX_FILES);
            }
            builder.setMessage(text);
            String zipName = asZip ? zipName(fragment, account) : null;
            builder.setPositiveButton(getString(R.string.MoggoDownloadStart),
                    (dialog, which) -> MoggoMediaDownloader.download(activity, account, messages, zipName));
            builder.setNegativeButton(getString(R.string.Cancel), null);
        }
        fragment.showDialog(builder.create());
    }
}
