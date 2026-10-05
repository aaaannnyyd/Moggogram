package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/** Moggogram: chat statistics computed on the phone from the chat history. */
public class MoggoChatStatsActivity extends BaseFragment {

    private static final int PAGE = 100;
    private static final int MAX_MESSAGES = 20000;
    private static final HashSet<String> STOP_WORDS = new HashSet<>(java.util.Arrays.asList(
            "это", "что", "как", "так", "вот", "уже", "там", "тут", "где", "его", "она", "они", "оно", "мне", "меня", "тебя",
            "тебе", "вас", "вам", "нас", "нам", "был", "была", "было", "были", "будет", "есть", "нет", "для", "при", "про",
            "под", "над", "без", "или", "если", "чтобы", "только", "ещё", "еще", "все", "всё", "всех", "кто", "когда", "тоже",
            "даже", "очень", "можно", "надо", "чем", "мой", "моя", "твой", "этот", "эта", "эти", "того", "этого", "просто",
            "the", "and", "you", "that", "this", "for", "are", "with", "have", "not", "was", "but", "what", "just", "your"));

    private final long dialogId;
    private final HashMap<Long, Integer> bySender = new HashMap<>();
    private final HashMap<String, Integer> words = new HashMap<>();
    private final int[] byHour = new int[24];
    private final int[] byWeekday = new int[7];
    private int total, photos, videos, voices, rounds, stickers, files, links, chars;
    private int firstDate, lastDate;
    private boolean cancelled;

    private LinearLayout content;
    private TextView progressView;

    public MoggoChatStatsActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.MoggoChatStats));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));
        scroll.addView(content);
        frame.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        progressView = text(context, getString(R.string.Loading), 16, false);
        progressView.setGravity(Gravity.CENTER);
        progressView.setPadding(dp(24), dp(48), dp(24), dp(24));
        content.addView(progressView);
        fragmentView = frame;
        loadPage(0);
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        cancelled = true;
        super.onFragmentDestroy();
    }

    private void loadPage(int offsetId) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = getMessagesController().getInputPeer(dialogId);
        req.offset_id = offsetId;
        req.limit = PAGE;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (cancelled) return;
            if (!(response instanceof TLRPC.messages_Messages)) {
                showResults();
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            getMessagesController().putUsers(res.users, false);
            getMessagesController().putChats(res.chats, false);
            int lastId = 0;
            for (TLRPC.Message message : res.messages) {
                lastId = message.id;
                count(message);
            }
            progressView.setText(LocaleController.formatString(R.string.MoggoChatStatsLoading, total));
            if (res.messages.size() < PAGE || lastId == 0 || total >= MAX_MESSAGES) {
                showResults();
            } else {
                loadPage(lastId);
            }
        }));
    }

    private void count(TLRPC.Message message) {
        if (message instanceof TLRPC.TL_messageService) return;
        total++;
        long from = MessageObject.getFromChatId(message);
        if (from == 0) from = dialogId;
        Integer n = bySender.get(from);
        bySender.put(from, n == null ? 1 : n + 1);
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(message.date * 1000L);
        byHour[calendar.get(Calendar.HOUR_OF_DAY)]++;
        byWeekday[(calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7]++; // Monday first
        if (firstDate == 0 || message.date < firstDate) firstDate = message.date;
        if (message.date > lastDate) lastDate = message.date;

        TLRPC.MessageMedia media = message.media;
        if (media instanceof TLRPC.TL_messageMediaPhoto) photos++;
        else if (media instanceof TLRPC.TL_messageMediaWebPage) links++;
        else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            TLRPC.Document doc = media.document;
            if (MessageObject.isRoundVideoDocument(doc)) rounds++;
            else if (MessageObject.isVoiceDocument(doc)) voices++;
            else if (MessageObject.isStickerDocument(doc) || MessageObject.isAnimatedStickerDocument(doc, true)) stickers++;
            else if (MessageObject.isVideoDocument(doc)) videos++;
            else files++;
        }
        String text = message.message;
        if (!TextUtils.isEmpty(text)) {
            chars += text.length();
            for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (raw.length() < 3 || STOP_WORDS.contains(raw) || raw.startsWith("http")) continue;
                Integer c = words.get(raw);
                words.put(raw, c == null ? 1 : c + 1);
            }
        }
    }

    private void showResults() {
        Context context = getContext();
        if (context == null) return;
        content.removeAllViews();
        if (total == 0) {
            TextView empty = text(context, getString(R.string.MoggoChatStatsEmpty), 16, false);
            empty.setPadding(dp(24), dp(48), dp(24), dp(24));
            empty.setGravity(Gravity.CENTER);
            content.addView(empty);
            return;
        }
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        int days = Math.max(1, (lastDate - firstDate) / 86400 + 1);
        section(context, getString(R.string.MoggoChatStatsOverview));
        line(context, LocaleController.formatString(R.string.MoggoChatStatsTotal, total)
                + (total >= MAX_MESSAGES ? " " + getString(R.string.MoggoChatStatsLimited) : ""));
        line(context, LocaleController.formatString(R.string.MoggoChatStatsPeriod,
                df.format(new java.util.Date(firstDate * 1000L)), df.format(new java.util.Date(lastDate * 1000L)), days));
        line(context, LocaleController.formatString(R.string.MoggoChatStatsPerDay, String.format(Locale.US, "%.1f", total / (float) days)));
        int textCount = Math.max(1, total - photos - videos - voices - rounds - stickers - files);
        line(context, LocaleController.formatString(R.string.MoggoChatStatsAvgLength, chars / textCount));

        section(context, getString(R.string.MoggoChatStatsSenders));
        ArrayList<Map.Entry<Long, Integer>> senders = new ArrayList<>(bySender.entrySet());
        Collections.sort(senders, (a, b) -> b.getValue() - a.getValue());
        int maxSender = senders.isEmpty() ? 1 : senders.get(0).getValue();
        for (int i = 0; i < Math.min(15, senders.size()); i++) {
            Map.Entry<Long, Integer> e = senders.get(i);
            bar(context, senderName(e.getKey()), e.getValue(), maxSender, e.getValue() * 100 / total + "%");
        }

        section(context, getString(R.string.MoggoChatStatsMedia));
        int[] mediaCounts = {photos, videos, voices, rounds, stickers, files, links};
        int[] mediaNames = {R.string.MoggoTypePhotos, R.string.MoggoTypeVideos, R.string.MoggoChatStatsVoices,
                R.string.MoggoChatStatsRounds, R.string.MoggoChatStatsStickers, R.string.MoggoTypeFiles, R.string.MoggoChatStatsLinks};
        int maxMedia = 1;
        for (int c : mediaCounts) maxMedia = Math.max(maxMedia, c);
        for (int i = 0; i < mediaCounts.length; i++) {
            if (mediaCounts[i] > 0) bar(context, getString(mediaNames[i]), mediaCounts[i], maxMedia, String.valueOf(mediaCounts[i]));
        }

        section(context, getString(R.string.MoggoChatStatsHours));
        int maxHour = 1;
        for (int c : byHour) maxHour = Math.max(maxHour, c);
        for (int h = 0; h < 24; h++) bar(context, String.format(Locale.US, "%02d:00", h), byHour[h], maxHour, String.valueOf(byHour[h]));

        section(context, getString(R.string.MoggoChatStatsWeekdays));
        String[] dayNames = new java.text.DateFormatSymbols().getShortWeekdays(); // 1 = Sunday
        int maxDay = 1;
        for (int c : byWeekday) maxDay = Math.max(maxDay, c);
        for (int d = 0; d < 7; d++) {
            String name = dayNames[(d + 1) % 7 + 1];
            bar(context, name, byWeekday[d], maxDay, String.valueOf(byWeekday[d]));
        }

        if (!words.isEmpty()) {
            section(context, getString(R.string.MoggoChatStatsWords));
            ArrayList<Map.Entry<String, Integer>> top = new ArrayList<>(words.entrySet());
            Collections.sort(top, (a, b) -> b.getValue() - a.getValue());
            int maxWord = top.get(0).getValue();
            for (int i = 0; i < Math.min(20, top.size()); i++) {
                bar(context, top.get(i).getKey(), top.get(i).getValue(), maxWord, String.valueOf(top.get(i).getValue()));
            }
        }
    }

    private String senderName(long id) {
        if (id > 0) {
            TLRPC.User user = getMessagesController().getUser(id);
            return user != null ? UserObject.getUserName(user) : String.valueOf(id);
        }
        TLRPC.Chat chat = getMessagesController().getChat(-id);
        return chat != null ? chat.title : String.valueOf(id);
    }

    // ---- views ----

    private TextView text(Context context, String value, int sp, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private LinearLayout card;

    private void section(Context context, String title) {
        TextView header = text(context, title, 15, true);
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        header.setPadding(dp(21), dp(16), dp(21), dp(8));
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(0, 0, 0, dp(10));
        card.addView(header);
        LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0);
        content.addView(card, lp);
    }

    private void line(Context context, String value) {
        TextView view = text(context, value, 15, false);
        view.setPadding(dp(21), dp(4), dp(21), dp(4));
        card.addView(view);
    }

    private void bar(Context context, String label, int value, int max, String valueText) {
        card.addView(new BarView(context, label, value / (float) Math.max(1, max), valueText),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 30));
    }

    private static class BarView extends View {
        private final String label;
        private final String value;
        private final float fraction;
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        BarView(Context context, String label, float fraction, String value) {
            super(context);
            this.label = label;
            this.value = value;
            this.fraction = fraction;
            textPaint.setTextSize(dp(13));
            textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            barPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float labelWidth = getWidth() * 0.34f;
            float valueWidth = dp(56);
            float y = getHeight() / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            String shown = TextUtils.ellipsize(label, new android.text.TextPaint(textPaint), labelWidth - dp(29), TextUtils.TruncateAt.END).toString();
            canvas.drawText(shown, dp(21), y, textPaint);
            float barLeft = labelWidth;
            float barMax = getWidth() - barLeft - valueWidth - dp(12);
            rect.set(barLeft, getHeight() / 2f - dp(5), barLeft + Math.max(dp(3), barMax * fraction), getHeight() / 2f + dp(5));
            barPaint.setAlpha(fraction > 0 ? 255 : 60);
            canvas.drawRoundRect(rect, dp(5), dp(5), barPaint);
            canvas.drawText(value, rect.right + dp(8), y, textPaint);
        }
    }
}
