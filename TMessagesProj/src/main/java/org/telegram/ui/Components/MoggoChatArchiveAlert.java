package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MoggoChatArchive;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCheckCell;

/** Moggogram: per-chat switch for the live archive. */
public class MoggoChatArchiveAlert {

    private static final int[] BACKFILL = {0, 1000, 10000};
    private static final int[] BACKFILL_TITLES = {R.string.MoggoArchiveOnlyNew, R.string.MoggoArchiveLast1000, R.string.MoggoArchiveLast10000};

    public static void show(BaseFragment fragment, long dialogId, String title) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        int account = fragment.getCurrentAccount();
        Theme.ResourcesProvider rp = fragment.getResourceProvider();
        boolean wasEnabled = MoggoChatArchive.isEnabled(account, dialogId);
        boolean[] enabled = {wasEnabled};
        boolean[] withMedia = {MoggoChatArchive.isMediaEnabled(account, dialogId)};
        int[] backfill = {1};

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView about = new TextView(activity);
        about.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        about.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        about.setPadding(dp(24), dp(4), dp(24), dp(8));
        about.setText(LocaleController.formatString(R.string.MoggoArchiveAbout, MoggoChatArchive.folder(account, dialogId).getAbsolutePath()));
        layout.addView(about);

        TextCheckCell toggle = new TextCheckCell(activity, rp);
        toggle.setTextAndCheck(getString(R.string.MoggoArchiveEnable), enabled[0], true);
        toggle.setBackground(Theme.getSelectorDrawable(false));
        layout.addView(toggle);

        CheckBoxCell mediaCell = new CheckBoxCell(activity, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, rp);
        mediaCell.setBackground(Theme.getSelectorDrawable(false));
        mediaCell.setText(getString(R.string.MoggoArchiveMedia), "", withMedia[0], false);
        mediaCell.setOnClickListener(v -> {
            withMedia[0] = !withMedia[0];
            mediaCell.setChecked(withMedia[0], true);
        });
        layout.addView(mediaCell);

        LinearLayout backfillBlock = new LinearLayout(activity);
        backfillBlock.setOrientation(LinearLayout.VERTICAL);
        HeaderCell header = new HeaderCell(activity, rp);
        header.setText(getString(R.string.MoggoArchiveBackfill));
        backfillBlock.addView(header);
        RadioColorCell[] radios = new RadioColorCell[BACKFILL.length];
        for (int i = 0; i < BACKFILL.length; i++) {
            RadioColorCell cell = new RadioColorCell(activity, rp);
            cell.setPadding(dp(4), 0, dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground, rp), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, rp));
            cell.setTextAndValue(getString(BACKFILL_TITLES[i]), i == backfill[0]);
            final int index = i;
            cell.setOnClickListener(v -> {
                backfill[0] = index;
                for (int j = 0; j < radios.length; j++) radios[j].setChecked(j == index, true);
            });
            radios[i] = cell;
            backfillBlock.addView(cell);
        }
        backfillBlock.setVisibility(!wasEnabled && enabled[0] ? android.view.View.VISIBLE : android.view.View.GONE);
        layout.addView(backfillBlock);

        toggle.setOnClickListener(v -> {
            enabled[0] = !enabled[0];
            toggle.setChecked(enabled[0]);
            backfillBlock.setVisibility(!wasEnabled && enabled[0] ? android.view.View.VISIBLE : android.view.View.GONE);
        });

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(layout);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle(getString(R.string.MoggoArchive));
        builder.setView(scroll);
        builder.setPositiveButton(getString(R.string.Save), (d, w) -> {
            int count = !wasEnabled && enabled[0] ? BACKFILL[backfill[0]] : 0;
            MoggoChatArchive.setEnabled(account, dialogId, title, enabled[0], withMedia[0], count);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check,
                    getString(enabled[0] ? R.string.MoggoArchiveOn : R.string.MoggoArchiveOff)).show();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }
}
