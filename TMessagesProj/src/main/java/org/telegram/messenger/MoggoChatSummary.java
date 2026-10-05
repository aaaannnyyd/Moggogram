package org.telegram.messenger;

import android.app.Activity;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * "What did I miss": the unread part of a chat (or the latest messages when nothing is unread)
 * retold by Gemini in a few lines. Uses the @ugar connection: Google account or API key.
 */
public final class MoggoChatSummary {

    private static final int MAX_MESSAGES = 400;
    private static final int RECENT_MESSAGES = 100;
    private static final int MAX_CHARS = 40000;

    private MoggoChatSummary() {
    }

    public static void show(ChatActivity fragment, long dialogId, String title, int readInboxMaxId, int unreadCount) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        int account = fragment.getCurrentAccount();
        boolean[] cancelled = {false};
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        progress.setOnCancelListener(d -> cancelled[0] = true);
        progress.show();
        new Thread(() -> {
            String result;
            String error = null;
            boolean unread = unreadCount > 0 && readInboxMaxId > 0;
            try {
                ArrayList<String> lines = collect(account, dialogId, unread ? readInboxMaxId : 0);
                if (lines.isEmpty()) {
                    result = null;
                    error = LocaleController.getString(R.string.MoggoSummaryEmpty);
                } else {
                    result = MoggoUgar.askText(prompt(account, title, unread, lines)).replace("**", "");
                }
            } catch (MoggoGeminiWeb.UserError e) {
                result = null;
                error = e.getMessage();
            } catch (Exception e) {
                FileLog.e(e);
                result = null;
                error = LocaleController.getString(R.string.MoggoUgarNetwork);
            }
            final String text = result, failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (cancelled[0] || fragment.getParentActivity() == null) return;
                AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
                b.setTitle(LocaleController.getString(unread ? R.string.MoggoSummaryTitle : R.string.MoggoSummaryRecentTitle));
                b.setMessage(text != null ? text : failure);
                b.setPositiveButton(LocaleController.getString(R.string.OK), null);
                if (text != null) {
                    b.setNegativeButton(LocaleController.getString(R.string.Copy), (d, w) -> {
                        AndroidUtilities.addToClipboard(text);
                        BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                    });
                }
                fragment.showDialog(b.create());
            });
        }, "moggo-summary").start();
    }

    private static String prompt(int account, String title, boolean unread, ArrayList<String> lines) {
        TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        String myName = me == null ? "" : UserObject.getUserName(me);
        StringBuilder sb = new StringBuilder();
        sb.append(unread
                ? "Ниже сообщения из Telegram-чата «" + title + "», которые я пропустил."
                : "Ниже последние сообщения из Telegram-чата «" + title + "».");
        sb.append(" Я — ").append(myName).append(". Перескажи по-русски коротко: главное в 3–7 пунктах (кто что сказал, предложил, решил),")
                .append(" отдельной строкой — что касается лично меня или ждёт моего ответа, если такое есть.")
                .append(" Без вступлений и без markdown-заголовков.\n\n");
        int start = 0, total = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            total += lines.get(i).length() + 1;
            if (total > MAX_CHARS) {
                start = i + 1;
                break;
            }
        }
        for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        return sb.toString();
    }

    /** Messages newer than readMaxId (or the latest ones when it is 0), oldest first, as "[time] Name: text". */
    private static ArrayList<String> collect(int account, long dialogId, int readMaxId) throws Exception {
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.InputPeer peer = controller.getInputPeer(dialogId);
        int limit = readMaxId > 0 ? MAX_MESSAGES : RECENT_MESSAGES;
        ArrayList<String> lines = new ArrayList<>();
        HashMap<Long, String> names = new HashMap<>();
        SimpleDateFormat time = new SimpleDateFormat("dd.MM HH:mm", Locale.ROOT);
        int offsetId = 0;
        boolean done = false;
        while (!done && lines.size() < limit) {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peer;
            req.offset_id = offsetId;
            req.limit = 100;
            TLObject[] answer = new TLObject[1];
            CountDownLatch latch = new CountDownLatch(1);
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                answer[0] = response;
                latch.countDown();
            });
            if (!latch.await(30, TimeUnit.SECONDS) || !(answer[0] instanceof TLRPC.messages_Messages)) {
                if (lines.isEmpty()) throw new MoggoGeminiWeb.UserError(LocaleController.getString(R.string.MoggoSummaryLoadFailed));
                break;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) answer[0];
            for (TLRPC.User u : res.users) names.put(u.id, UserObject.getUserName(u));
            for (TLRPC.Chat c : res.chats) names.put(-c.id, c.title);
            if (res.messages.isEmpty()) break;
            for (TLRPC.Message m : res.messages) {
                offsetId = m.id;
                if (readMaxId > 0 && m.id <= readMaxId) {
                    done = true;
                    break;
                }
                if (m instanceof TLRPC.TL_messageService) continue;
                String text = describe(m);
                if (TextUtils.isEmpty(text)) continue;
                long from = MessageObject.getPeerId(m.from_id != null ? m.from_id : m.peer_id);
                String name = names.get(from);
                if (name == null) name = "?";
                lines.add("[" + time.format(new Date(m.date * 1000L)) + "] " + name + ": " + text.replace('\n', ' '));
                if (lines.size() >= limit) break;
            }
            if (res.messages.size() < 100) break;
        }
        Collections.reverse(lines);
        return lines;
    }

    private static String describe(TLRPC.Message m) {
        String text = m.message == null ? "" : m.message.trim();
        String media = null;
        if (m.media instanceof TLRPC.TL_messageMediaPhoto) media = "[фото]";
        else if (m.media instanceof TLRPC.TL_messageMediaGeo || m.media instanceof TLRPC.TL_messageMediaVenue) media = "[место]";
        else if (m.media instanceof TLRPC.TL_messageMediaPoll) media = "[опрос]";
        else if (m.media instanceof TLRPC.TL_messageMediaDocument && m.media.document != null) {
            TLRPC.Document d = m.media.document;
            if (MessageObject.isStickerDocument(d) || MessageObject.isAnimatedStickerDocument(d, true)) {
                String alt = null;
                for (TLRPC.DocumentAttribute a : d.attributes) if (a instanceof TLRPC.TL_documentAttributeSticker) alt = a.alt;
                media = "[стикер " + (alt == null ? "" : alt) + "]";
            } else if (MessageObject.isVoiceDocument(d)) media = "[голосовое]";
            else if (MessageObject.isRoundVideoDocument(d)) media = "[кружок]";
            else if (MessageObject.isVideoDocument(d)) media = "[видео]";
            else if (MessageObject.isGifDocument(d)) media = "[гиф]";
            else media = "[файл]";
        }
        if (media == null) return text;
        return text.isEmpty() ? media : media + " " + text;
    }
}
