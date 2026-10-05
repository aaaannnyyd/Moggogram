package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Moggogram: chat statistics computed on the phone. Results are cached per chat; reopening shows
 * the cache at once and reads only new messages (or continues an interrupted scan).
 */
public class MoggoChatStatsActivity extends BaseFragment {

    private static final int PAGE = 100;
    private static final int MAX_MESSAGES = 20000;
    private static final int MAX_CACHED_WORDS = 3000;

    private final long dialogId;
    private Stats stats;
    private boolean cancelled;
    private boolean loadingNew;
    private int target;
    private int scannedThisRun;
    private long startedAt;

    private LinearLayout content;
    private LinearLayout progressCard;
    private TextView progressTitle;
    private TextView progressText;
    private ProgressBarView progressBar;
    private LinearLayout resultsView;

    public MoggoChatStatsActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    // ---------------------------------------------------------------- data

    private static final class Stats {
        HashMap<Long, Integer> bySender = new HashMap<>();
        HashMap<Long, Integer> matBySender = new HashMap<>();
        HashMap<String, Integer> words = new HashMap<>();
        HashMap<String, Integer> matWords = new HashMap<>();
        int[] byHour = new int[24];
        int[] byWeekday = new int[7];
        int total, photos, videos, voices, rounds, stickers, files, links, chars, textMessages, matMessages, matTotal;
        int firstDate, lastDate;
        int newestId, oldestId;
        boolean complete;
        long savedAt;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("bySender", mapL(bySender));
            o.put("matBySender", mapL(matBySender));
            o.put("words", mapS(trim(words, MAX_CACHED_WORDS)));
            o.put("matWords", mapS(matWords));
            o.put("byHour", new org.json.JSONArray(byHour));
            o.put("byWeekday", new org.json.JSONArray(byWeekday));
            int[] ints = {total, photos, videos, voices, rounds, stickers, files, links, chars, textMessages, matMessages, matTotal, firstDate, lastDate, newestId, oldestId};
            o.put("ints", new org.json.JSONArray(ints));
            o.put("complete", complete);
            o.put("savedAt", savedAt);
            return o;
        }

        static Stats fromJson(JSONObject o) throws Exception {
            Stats s = new Stats();
            readL(o.getJSONObject("bySender"), s.bySender);
            readL(o.getJSONObject("matBySender"), s.matBySender);
            readS(o.getJSONObject("words"), s.words);
            readS(o.getJSONObject("matWords"), s.matWords);
            for (int i = 0; i < 24; i++) s.byHour[i] = o.getJSONArray("byHour").getInt(i);
            for (int i = 0; i < 7; i++) s.byWeekday[i] = o.getJSONArray("byWeekday").getInt(i);
            org.json.JSONArray a = o.getJSONArray("ints");
            s.total = a.getInt(0); s.photos = a.getInt(1); s.videos = a.getInt(2); s.voices = a.getInt(3);
            s.rounds = a.getInt(4); s.stickers = a.getInt(5); s.files = a.getInt(6); s.links = a.getInt(7);
            s.chars = a.getInt(8); s.textMessages = a.getInt(9); s.matMessages = a.getInt(10); s.matTotal = a.getInt(11);
            s.firstDate = a.getInt(12); s.lastDate = a.getInt(13); s.newestId = a.getInt(14); s.oldestId = a.getInt(15);
            s.complete = o.getBoolean("complete");
            s.savedAt = o.getLong("savedAt");
            return s;
        }

        private static JSONObject mapL(HashMap<Long, Integer> m) throws Exception {
            JSONObject o = new JSONObject();
            for (Map.Entry<Long, Integer> e : m.entrySet()) o.put(String.valueOf(e.getKey()), e.getValue());
            return o;
        }

        private static JSONObject mapS(HashMap<String, Integer> m) throws Exception {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Integer> e : m.entrySet()) o.put(e.getKey(), e.getValue());
            return o;
        }

        private static void readL(JSONObject o, HashMap<Long, Integer> m) throws Exception {
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(Long.parseLong(k), o.getInt(k));
            }
        }

        private static void readS(JSONObject o, HashMap<String, Integer> m) throws Exception {
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, o.getInt(k));
            }
        }

        private static HashMap<String, Integer> trim(HashMap<String, Integer> m, int max) {
            if (m.size() <= max) return m;
            ArrayList<Map.Entry<String, Integer>> list = new ArrayList<>(m.entrySet());
            Collections.sort(list, (x, y) -> y.getValue() - x.getValue());
            HashMap<String, Integer> r = new HashMap<>();
            for (int i = 0; i < max; i++) r.put(list.get(i).getKey(), list.get(i).getValue());
            return r;
        }
    }

    private File cacheFile() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "moggo_stats");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, currentAccount + "_" + dialogId + ".json");
    }

    private Stats loadCache() {
        try {
            File f = cacheFile();
            if (!f.exists()) return null;
            return Stats.fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private void saveCache() {
        final Stats s = stats;
        if (s == null || s.total == 0) return;
        s.savedAt = System.currentTimeMillis();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Files.write(cacheFile().toPath(), s.toJson().toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    // ---------------------------------------------------------------- view

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

        progressCard = new LinearLayout(context);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressCard.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        progressCard.setPadding(dp(21), dp(16), dp(21), dp(16));
        progressTitle = text(context, getString(R.string.MoggoChatStatsReading), 15, true);
        progressTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        progressCard.addView(progressTitle);
        progressBar = new ProgressBarView(context);
        progressCard.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 10, 0, 12, 0, 8));
        progressText = text(context, getString(R.string.Loading), 13, false);
        progressText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        progressCard.addView(progressText);
        content.addView(progressCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        resultsView = new LinearLayout(context);
        resultsView.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultsView);

        fragmentView = frame;
        start();
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        cancelled = true;
        saveCache(); // keep a partial scan: next time it continues where it stopped
        super.onFragmentDestroy();
    }

    private void start() {
        Stats cached = loadCache();
        startedAt = SystemClock.elapsedRealtime();
        if (cached != null) {
            stats = cached;
            showResults();
            loadingNew = true;
            progressTitle.setText(getString(R.string.MoggoChatStatsUpdating));
            progressText.setText(LocaleController.formatString(R.string.MoggoChatStatsCached,
                    LocaleController.formatDateAudio(cached.savedAt / 1000, false)));
            progressBar.setIndeterminate(true);
            loadNewer(0);
        } else {
            stats = new Stats();
            loadOlder(0);
        }
    }

    /** New messages since the cache: newest first, stop at the cached newest id. */
    private void loadNewer(int offsetId) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = getMessagesController().getInputPeer(dialogId);
        req.offset_id = offsetId;
        req.min_id = stats.newestId;
        req.limit = PAGE;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (cancelled) return;
            if (!(response instanceof TLRPC.messages_Messages)) {
                finish();
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            putPeers(res);
            int lastId = 0, maxId = stats.newestId;
            int added = 0;
            for (TLRPC.Message m : res.messages) {
                lastId = m.id;
                if (m.id <= stats.newestId) continue;
                maxId = Math.max(maxId, m.id);
                count(m);
                added++;
            }
            scannedThisRun += added;
            progressText.setText(LocaleController.formatString(R.string.MoggoChatStatsNew, scannedThisRun));
            if (res.messages.size() == PAGE && lastId > stats.newestId) {
                pendingNewest = Math.max(pendingNewest, maxId);
                loadNewer(lastId);
            } else {
                stats.newestId = Math.max(Math.max(pendingNewest, maxId), stats.newestId);
                loadingNew = false;
                if (!stats.complete) {
                    progressBar.setIndeterminate(false);
                    loadOlder(stats.oldestId);
                } else {
                    finish();
                }
            }
        }));
    }

    private int pendingNewest;

    /** Older history (first scan or continuing an interrupted one), newest to oldest. */
    private void loadOlder(int offsetId) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = getMessagesController().getInputPeer(dialogId);
        req.offset_id = offsetId;
        req.limit = PAGE;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (cancelled) return;
            if (!(response instanceof TLRPC.messages_Messages)) {
                finish();
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            putPeers(res);
            if (target == 0) {
                int count = res.count > 0 ? res.count : res.messages.size();
                target = Math.min(count, MAX_MESSAGES);
            }
            int lastId = 0;
            for (TLRPC.Message m : res.messages) {
                lastId = m.id;
                if (stats.newestId == 0 || m.id > stats.newestId) stats.newestId = Math.max(stats.newestId, m.id);
                count(m);
                scannedThisRun++;
            }
            if (lastId != 0) stats.oldestId = lastId;
            updateProgress();
            boolean end = res.messages.size() < PAGE || lastId == 0 || stats.total >= MAX_MESSAGES;
            if (end) {
                stats.complete = true;
                finish();
            } else {
                loadOlder(lastId);
            }
        }));
    }

    private void putPeers(TLRPC.messages_Messages res) {
        getMessagesController().putUsers(res.users, false);
        getMessagesController().putChats(res.chats, false);
    }

    private void updateProgress() {
        int done = Math.min(stats.total, target);
        float fraction = target == 0 ? 0 : done / (float) target;
        progressBar.setProgress(fraction);
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        String eta = "";
        if (scannedThisRun > PAGE && fraction < 1f) {
            long left = (long) (elapsed / (float) scannedThisRun * (target - done));
            eta = " · " + LocaleController.formatString(R.string.MoggoChatStatsEta, Math.max(1, left / 1000));
        }
        progressText.setText(LocaleController.formatString(R.string.MoggoChatStatsProgress,
                formatNumber(done), formatNumber(target)) + eta);
    }

    private void finish() {
        saveCache();
        progressCard.setVisibility(View.GONE);
        showResults();
    }

    // ---------------------------------------------------------------- counting

    private void count(TLRPC.Message message) {
        if (message instanceof TLRPC.TL_messageService) return;
        Stats s = stats;
        s.total++;
        long from = MessageObject.getFromChatId(message);
        if (from == 0) from = dialogId;
        inc(s.bySender, from, 1);
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(message.date * 1000L);
        s.byHour[calendar.get(Calendar.HOUR_OF_DAY)]++;
        s.byWeekday[(calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7]++; // Monday first
        if (s.firstDate == 0 || message.date < s.firstDate) s.firstDate = message.date;
        if (message.date > s.lastDate) s.lastDate = message.date;

        TLRPC.MessageMedia media = message.media;
        if (media instanceof TLRPC.TL_messageMediaPhoto) s.photos++;
        else if (media instanceof TLRPC.TL_messageMediaWebPage) s.links++;
        else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            TLRPC.Document doc = media.document;
            if (MessageObject.isRoundVideoDocument(doc)) s.rounds++;
            else if (MessageObject.isVoiceDocument(doc)) s.voices++;
            else if (MessageObject.isStickerDocument(doc) || MessageObject.isAnimatedStickerDocument(doc, true)) s.stickers++;
            else if (MessageObject.isVideoDocument(doc)) s.videos++;
            else s.files++;
        }
        String text = message.message;
        if (TextUtils.isEmpty(text)) return;
        s.textMessages++;
        s.chars += text.length();
        int matHere = 0;
        for (String raw : text.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() < 2 || raw.startsWith("http")) continue;
            String word = Words.normalizeLatin(raw);
            if (Words.isMat(word)) {
                matHere++;
                inc(s.matWords, word, 1);
                continue;
            }
            if (word.length() < 3 || Words.isStop(word) || word.matches("\\d+")) continue;
            inc(s.words, word, 1);
        }
        if (matHere > 0) {
            s.matMessages++;
            s.matTotal += matHere;
            inc(s.matBySender, from, matHere);
        }
    }

    private static <K> void inc(HashMap<K, Integer> map, K key, int by) {
        Integer v = map.get(key);
        map.put(key, v == null ? by : v + by);
    }

    // ---------------------------------------------------------------- results

    private void showResults() {
        Context context = getContext();
        if (context == null) return;
        Stats s = stats;
        resultsView.removeAllViews();
        if (s.total == 0) {
            TextView empty = text(context, getString(R.string.MoggoChatStatsEmpty), 16, false);
            empty.setPadding(dp(24), dp(48), dp(24), dp(24));
            empty.setGravity(Gravity.CENTER);
            resultsView.addView(empty);
            return;
        }
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        int days = Math.max(1, (s.lastDate - s.firstDate) / 86400 + 1);

        section(context, getString(R.string.MoggoChatStatsOverview));
        line(context, LocaleController.formatString(R.string.MoggoChatStatsTotal, s.total)
                + (s.total >= MAX_MESSAGES ? " " + getString(R.string.MoggoChatStatsLimited) : ""));
        line(context, LocaleController.formatString(R.string.MoggoChatStatsPeriod,
                df.format(new java.util.Date(s.firstDate * 1000L)), df.format(new java.util.Date(s.lastDate * 1000L)), days));
        line(context, LocaleController.formatString(R.string.MoggoChatStatsPerDay, String.format(Locale.US, "%.1f", s.total / (float) days)));
        line(context, LocaleController.formatString(R.string.MoggoChatStatsAvgLength, s.chars / Math.max(1, s.textMessages)));

        section(context, getString(R.string.MoggoChatStatsSenders));
        ArrayList<Map.Entry<Long, Integer>> senders = sorted(s.bySender);
        int maxSender = senders.isEmpty() ? 1 : senders.get(0).getValue();
        for (int i = 0; i < Math.min(15, senders.size()); i++) {
            Map.Entry<Long, Integer> e = senders.get(i);
            bar(context, senderName(e.getKey()), e.getValue(), maxSender, e.getValue() * 100 / s.total + "%");
        }

        if (s.matTotal > 0) {
            section(context, getString(R.string.MoggoChatStatsMat));
            line(context, LocaleController.formatString(R.string.MoggoChatStatsMatTotal, s.matTotal,
                    s.matMessages * 100 / Math.max(1, s.textMessages)));
            ArrayList<Map.Entry<Long, Integer>> matSenders = sorted(s.matBySender);
            int maxMat = matSenders.get(0).getValue();
            for (int i = 0; i < Math.min(10, matSenders.size()); i++) {
                Map.Entry<Long, Integer> e = matSenders.get(i);
                Integer msgs = s.bySender.get(e.getKey());
                String per100 = String.format(Locale.US, "%.1f", e.getValue() * 100f / Math.max(1, msgs == null ? 1 : msgs));
                bar(context, (i == 0 ? "👑 " : "") + senderName(e.getKey()), e.getValue(), maxMat,
                        e.getValue() + " · " + LocaleController.formatString(R.string.MoggoChatStatsPer100, per100));
            }
            subheader(context, getString(R.string.MoggoChatStatsMatTop));
            ArrayList<Map.Entry<String, Integer>> matWords = sorted(s.matWords);
            int maxWord = matWords.get(0).getValue();
            for (int i = 0; i < Math.min(10, matWords.size()); i++) {
                bar(context, matWords.get(i).getKey(), matWords.get(i).getValue(), maxWord, String.valueOf(matWords.get(i).getValue()));
            }
        }

        section(context, getString(R.string.MoggoChatStatsMedia));
        int[] mediaCounts = {s.photos, s.videos, s.voices, s.rounds, s.stickers, s.files, s.links};
        int[] mediaNames = {R.string.MoggoTypePhotos, R.string.MoggoTypeVideos, R.string.MoggoChatStatsVoices,
                R.string.MoggoChatStatsRounds, R.string.MoggoChatStatsStickers, R.string.MoggoTypeFiles, R.string.MoggoChatStatsLinks};
        int maxMedia = 1;
        for (int c : mediaCounts) maxMedia = Math.max(maxMedia, c);
        for (int i = 0; i < mediaCounts.length; i++) {
            if (mediaCounts[i] > 0) bar(context, getString(mediaNames[i]), mediaCounts[i], maxMedia, String.valueOf(mediaCounts[i]));
        }

        section(context, getString(R.string.MoggoChatStatsHours));
        int maxHour = 1;
        for (int c : s.byHour) maxHour = Math.max(maxHour, c);
        for (int h = 0; h < 24; h++) bar(context, String.format(Locale.US, "%02d:00", h), s.byHour[h], maxHour, String.valueOf(s.byHour[h]));

        section(context, getString(R.string.MoggoChatStatsWeekdays));
        String[] dayNames = new java.text.DateFormatSymbols().getShortWeekdays(); // 1 = Sunday
        int maxDay = 1;
        for (int c : s.byWeekday) maxDay = Math.max(maxDay, c);
        for (int d = 0; d < 7; d++) bar(context, dayNames[(d + 1) % 7 + 1], s.byWeekday[d], maxDay, String.valueOf(s.byWeekday[d]));

        if (!s.words.isEmpty()) {
            section(context, getString(R.string.MoggoChatStatsWords));
            ArrayList<Map.Entry<String, Integer>> top = sorted(s.words);
            int maxWord = top.get(0).getValue();
            for (int i = 0; i < Math.min(25, top.size()); i++) {
                bar(context, top.get(i).getKey(), top.get(i).getValue(), maxWord, String.valueOf(top.get(i).getValue()));
            }
        }
    }

    private static <K> ArrayList<Map.Entry<K, Integer>> sorted(HashMap<K, Integer> map) {
        ArrayList<Map.Entry<K, Integer>> list = new ArrayList<>(map.entrySet());
        Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
        return list;
    }

    private String senderName(long id) {
        if (id > 0) {
            TLRPC.User user = getMessagesController().getUser(id);
            return user != null ? UserObject.getUserName(user) : String.valueOf(id);
        }
        TLRPC.Chat chat = getMessagesController().getChat(-id);
        return chat != null ? chat.title : String.valueOf(id);
    }

    private static String formatNumber(int n) {
        return String.format(Locale.getDefault(), "%,d", n).replace(',', ' ');
    }

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
        resultsView.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
    }

    private void subheader(Context context, String title) {
        TextView header = text(context, title, 13, true);
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        header.setPadding(dp(21), dp(10), dp(21), dp(4));
        card.addView(header);
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
            float valueWidth = textPaint.measureText(value) + dp(8);
            float y = getHeight() / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            String shown = TextUtils.ellipsize(label, new android.text.TextPaint(textPaint), labelWidth - dp(29), TextUtils.TruncateAt.END).toString();
            canvas.drawText(shown, dp(21), y, textPaint);
            float barMax = getWidth() - labelWidth - valueWidth - dp(21);
            rect.set(labelWidth, getHeight() / 2f - dp(5), labelWidth + Math.max(dp(3), barMax * fraction), getHeight() / 2f + dp(5));
            barPaint.setAlpha(fraction > 0 ? 255 : 60);
            canvas.drawRoundRect(rect, dp(5), dp(5), barPaint);
            canvas.drawText(value, rect.right + dp(8), y, textPaint);
        }
    }

    /** Rounded gradient progress bar; animates to the new value, or a moving highlight when indeterminate. */
    private static class ProgressBarView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float progress;
        private boolean indeterminate;
        private ValueAnimator animator;

        ProgressBarView(Context context) {
            super(context);
            int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
            track.setColor(blue);
            track.setAlpha(40);
        }

        void setProgress(float value) {
            indeterminate = false;
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(progress, Math.max(0f, Math.min(1f, value)));
            animator.setDuration(300);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        void setIndeterminate(boolean value) {
            indeterminate = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, track);
            fill.setShader(new LinearGradient(0, 0, getWidth(), 0, 0xFF5B2EFF, 0xFF2AA4FF, Shader.TileMode.CLAMP));
            if (indeterminate) {
                float t = (SystemClock.uptimeMillis() % 1400) / 1400f;
                float w = getWidth() * 0.3f;
                float x = -w + (getWidth() + w) * t;
                rect.set(Math.max(0, x), 0, Math.min(getWidth(), x + w), getHeight());
                canvas.drawRoundRect(rect, r, r, fill);
                invalidate();
            } else if (progress > 0) {
                rect.set(0, 0, Math.max(getHeight(), getWidth() * progress), getHeight());
                canvas.drawRoundRect(rect, r, r, fill);
            }
        }
    }

    // ---------------------------------------------------------------- words

    private static final class Words {
        // Russian mat roots (with common prefixes) — whole-word start match keeps "хлебать", "учебник" etc. out
        private static final Pattern MAT = Pattern.compile(
                "^(на|по|за|от|у|вы|при|до|с|раз|рас|об|ни|под|пере|недо|о)?"
                        + "(ху[йеиюяё]|пизд|еба[нлт]|еб[ауилн]|бля|сук[аиоу]|суч|муд[аоие][кл]|залуп|гандон|пид[оа]р|шлюх|хер|жоп|говн|дерьм|сран|срат|манд[аи])");
        private static final java.util.HashSet<String> MAT_EXACT = new java.util.HashSet<>(java.util.Arrays.asList(
                "бля", "блядь", "блять", "сука", "хер", "нахер", "похер", "пздц", "нах"));
        private static final java.util.HashSet<String> NOT_MAT = new java.util.HashSet<>(java.util.Arrays.asList(
                "блин", "хрен", "нахрен", "сукно", "херсон", "себе", "себя", "вебинар", "ребенок", "ребята", "хуже", "худо"));

        static boolean isMat(String w) {
            if (NOT_MAT.contains(w)) return false;
            return MAT_EXACT.contains(w) || MAT.matcher(w).find();
        }

        private static final Map<String, String> LATIN = new HashMap<>();

        static {
            String[][] pairs = {{"her", "хер"}, {"xer", "хер"}, {"hui", "хуй"}, {"xyi", "хуй"}, {"huy", "хуй"},
                    {"bla", "бля"}, {"blya", "бля"}, {"blyat", "блять"}, {"pizda", "пизда"}, {"suka", "сука"},
                    {"nahui", "нахуй"}, {"nahuy", "нахуй"}, {"ebat", "ебать"}, {"eblan", "еблан"}, {"pzdc", "пздц"}};
            for (String[] p : pairs) LATIN.put(p[0], p[1]);
        }

        static String normalizeLatin(String w) {
            String mapped = LATIN.get(w);
            return mapped != null ? mapped : w;
        }

        private static final java.util.HashSet<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList((
                "это эта этот эти этого этой этим этих этом тот та те того той тем том тех то там тут здесь вот вон " +
                "что чтоб чтобы чего чем чему как так такой такая такое такие таким такого какой какая какое какие каким " +
                "какого кто кого кому кем ком где куда откуда когда тогда сейчас щас щя теперь потом затем уже ещё еще " +
                "опять снова всегда никогда иногда часто давно скоро сразу пока ли же бы да нет не ни но и а или либо " +
                "если хотя потому поэтому почему зачем ведь вроде типо типа короче вообще кстати просто прям прямо " +
                "очень слишком совсем почти только лишь даже уж тоже также тож всё все всех всем всеми весь вся всего всей " +
                "сам сама само сами самый самая свой своя свое свои своего своей моё мое мой моя мои моего моей твой твоя " +
                "твое твои твоего его её ее их им ими ему ей ним ней них нем нём нас нам нами вас вам вами меня мне мной " +
                "тебя тебе тобой себя себе собой они она оно мы вы ты он я для при про под над без из от до по на за с со " +
                "во об обо через между перед после около возле среди ради кроме вместо вне сквозь много мало больше меньше " +
                "лучше хуже больно нормально норм хорошо плохо ладно окей ок ага угу неа ну ой ах эх ох хм мм лол кек " +
                "был была было были быть будет будут буду будем будешь есть нету нет надо нужно можно нельзя хочу хочешь " +
                "хочет хотел хотела могу может можешь мог могла могут знаю знаешь знает знал думаю думаешь сказал сказала " +
                "говорю говоришь говорит сделать делать делаю делаешь сделал понял поняла понятно понимаю ясно видел видела " +
                "смотри смотрю иди идет идёт пошли пойду пойдем давай давайте дай дайте ща сегодня завтра вчера утром вечером " +
                "ночью день дня дней раз разу года год лет время времени минут часа часов неделю потому-что чтото что-то " +
                "кто-то как-то где-то какой-то нибудь либо сколько столько несколько каждый каждая любой другой другая другие " +
                "другого один одна одно одни два две три первый второй мол дескать вроде бы ибо дабы пусть пускай " +
                "работает работа работаю будто словно точно наверное наверно конечно возможно кажется вообщем " +
                "the and you that this for are with have not was but what just your they them then than from will " +
                "would can could there their about like get got its it's also yes all one out who how why when where")
                .split("\\s+")));

        static boolean isStop(String w) {
            return STOP.contains(w);
        }
    }
}
