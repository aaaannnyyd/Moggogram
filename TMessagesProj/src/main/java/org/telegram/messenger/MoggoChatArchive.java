package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.radolyn.ayugram.AyuConstants;

/**
 * Moggogram "live archive": for chosen chats every message, edit, deletion and media file is
 * written as it arrives to Documents/Moggogram/Архив/<chat>/ — messages.jsonl (one record per
 * line), media/ and chat.html (readable in any browser, rebuilt from the jsonl). The archive
 * lives outside the app, so it survives "delete for everyone", logout and uninstall.
 *
 * Records: meta {t,title,self,dialog} · m {id,date,from,name,text,reply,fwd,mt,mn} ·
 * f {id,path} · e {id,date,text} · d {ids,at}
 */
public final class MoggoChatArchive implements NotificationCenter.NotificationCenterDelegate {

    private static final long MAX_MEDIA_BYTES = 200L * 1024 * 1024;
    private static final long HTML_DELAY_MS = 4000;

    private static final MoggoChatArchive instance = new MoggoChatArchive();
    private static final HashSet<Integer> observedAccounts = new HashSet<>();

    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final ExecutorService media = Executors.newSingleThreadExecutor();
    private final HashMap<String, ChatState> states = new HashMap<>();
    private final HashSet<String> htmlScheduled = new HashSet<>();

    private MoggoChatArchive() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("moggo_archive", Context.MODE_PRIVATE);
    }

    private static String key(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    public static boolean isEnabled(int account, long dialogId) {
        return prefs().getBoolean("on_" + key(account, dialogId), false);
    }

    public static boolean isMediaEnabled(int account, long dialogId) {
        return prefs().getBoolean("media_" + key(account, dialogId), true);
    }

    /** Start listening for all logged-in accounts (idempotent, UI thread). */
    public static void init() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated() || observedAccounts.contains(a)) continue;
            if (!hasAnyArchive(a)) continue;
            observedAccounts.add(a);
            NotificationCenter center = NotificationCenter.getInstance(a);
            center.addObserver(instance, NotificationCenter.didReceiveNewMessages);
            center.addObserver(instance, NotificationCenter.messageReceivedByServer);
            center.addObserver(instance, NotificationCenter.replaceMessagesObjects);
            center.addObserver(instance, NotificationCenter.messagesDeleted);
            center.addObserver(instance, AyuConstants.MESSAGES_DELETED_NOTIFICATION);
        }
    }

    private static boolean hasAnyArchive(int account) {
        String prefix = "on_" + account + ":";
        for (String k : prefs().getAll().keySet()) {
            if (k.startsWith(prefix) && prefs().getBoolean(k, false)) return true;
        }
        return false;
    }

    /** Turn archiving on/off; on enable optionally back-fill the latest {@code backfill} messages. */
    public static void setEnabled(int account, long dialogId, String title, boolean enabled, boolean withMedia, int backfill) {
        prefs().edit()
                .putBoolean("on_" + key(account, dialogId), enabled)
                .putBoolean("media_" + key(account, dialogId), withMedia)
                .putString("title_" + key(account, dialogId), title)
                .apply();
        if (!enabled) return;
        init();
        instance.writer.execute(() -> instance.ensureMeta(account, dialogId));
        if (backfill > 0) instance.backfill(account, dialogId, backfill, 0, new ArrayList<>());
    }

    public static File folder(int account, long dialogId) {
        String title = prefs().getString("title_" + key(account, dialogId), "chat");
        String safe = title.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]+", "_").trim();
        if (safe.length() > 40) safe = safe.substring(0, 40);
        String name = (safe.isEmpty() ? "chat" : safe) + "_" + Math.abs(dialogId);
        File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Moggogram/Архив");
        if (!root.exists() && !root.mkdirs()) {
            File fallback = ApplicationLoader.applicationContext.getExternalFilesDir("Архив");
            if (fallback != null) root = fallback;
        }
        hideFromGallery(root);
        return new File(root, name);
    }

    /** Archived photos/videos/voice must not show up in the gallery or get picked up by media backups. */
    private static void hideFromGallery(File root) {
        File marker = new File(root, ".nomedia");
        if (marker.exists()) return;
        try {
            if (root.exists() || root.mkdirs()) marker.createNewFile();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // Media keeps its extension only if it is a plain media/document type; anything a browser could
    // run next to chat.html (html, svg, js...) is stored as .bin.
    private static final java.util.Set<String> SAFE_EXTENSIONS = new java.util.HashSet<>(java.util.Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "mp4", "mov", "webm", "mkv", "3gp", "ogg", "oga", "opus",
            "mp3", "m4a", "aac", "wav", "flac", "pdf", "txt", "zip", "rar", "7z", "doc", "docx", "xls", "xlsx", "ppt",
            "pptx", "apk", "tgs", "webm"));

    static String safeExtension(String ext) {
        String e = ext == null ? "" : ext.toLowerCase(java.util.Locale.ROOT);
        return SAFE_EXTENSIONS.contains(e) ? e : "bin";
    }

    // ---- events (UI thread) ----

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {
            long dialogId = (Long) args[0];
            if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) return; // scheduled
            if (!isEnabled(account, dialogId)) return;
            ArrayList<MessageObject> list = (ArrayList<MessageObject>) args[1];
            ArrayList<TLRPC.Message> messages = new ArrayList<>();
            for (MessageObject mo : list) if (mo.getId() > 0) messages.add(mo.messageOwner);
            record(account, dialogId, messages);
        } else if (id == NotificationCenter.messageReceivedByServer) {
            if (args.length > 6 && args[6] instanceof Boolean && (Boolean) args[6]) return; // scheduled
            TLRPC.Message message = (TLRPC.Message) args[2];
            if (message == null) return;
            long dialogId = MessageObject.getDialogId(message);
            if (!isEnabled(account, dialogId)) return;
            ArrayList<TLRPC.Message> messages = new ArrayList<>();
            messages.add(message);
            record(account, dialogId, messages);
        } else if (id == NotificationCenter.replaceMessagesObjects) {
            long dialogId = (Long) args[0];
            if (!isEnabled(account, dialogId)) return;
            ArrayList<MessageObject> list = (ArrayList<MessageObject>) args[1];
            ArrayList<TLRPC.Message> edited = new ArrayList<>();
            for (MessageObject mo : list) if (mo.getId() > 0) edited.add(mo.messageOwner);
            writer.execute(() -> writeEdits(account, dialogId, edited));
        } else if (id == AyuConstants.MESSAGES_DELETED_NOTIFICATION) {
            long dialogId = (Long) args[0];
            if (!isEnabled(account, dialogId)) return;
            ArrayList<Integer> ids = new ArrayList<>((ArrayList<Integer>) args[1]);
            writer.execute(() -> writeDeletes(account, dialogId, ids));
        } else if (id == NotificationCenter.messagesDeleted) {
            ArrayList<Integer> ids = new ArrayList<>((ArrayList<Integer>) args[0]);
            long channelId = (Long) args[1];
            if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) return;
            if (channelId != 0) {
                long dialogId = -channelId;
                if (isEnabled(account, dialogId)) writer.execute(() -> writeDeletes(account, dialogId, ids));
            } else {
                // private chats and basic groups: the event has no dialog, match ids we archived
                for (String k : prefs().getAll().keySet()) {
                    if (!k.startsWith("on_" + account + ":") || !prefs().getBoolean(k, false)) continue;
                    long dialogId = Long.parseLong(k.substring(("on_" + account + ":").length()));
                    if (dialogId < 0 && ChatObject.isChannel(MessagesController.getInstance(account).getChat(-dialogId))) continue;
                    writer.execute(() -> writeDeletes(account, dialogId, ids));
                }
            }
        }
    }

    private void record(int account, long dialogId, ArrayList<TLRPC.Message> messages) {
        if (messages.isEmpty()) return;
        MessagesController controller = MessagesController.getInstance(account);
        ArrayList<JSONObject> records = new ArrayList<>();
        for (TLRPC.Message message : messages) {
            try {
                records.add(messageRecord(controller, message));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        writer.execute(() -> {
            ChatState state = state(account, dialogId);
            for (JSONObject record : records) {
                int mid = record.optInt("id");
                if (state.ids.contains(mid)) continue;
                state.ids.add(mid);
                state.texts.put(mid, record.optString("text", ""));
                append(account, dialogId, record);
            }
        });
        if (isMediaEnabled(account, dialogId)) {
            for (TLRPC.Message message : messages) {
                if (message.media != null && !(message.media instanceof TLRPC.TL_messageMediaEmpty)
                        && !(message.media instanceof TLRPC.TL_messageMediaWebPage)) {
                    MessageObject mo = new MessageObject(account, message, false, false);
                    media.execute(() -> saveMedia(account, dialogId, mo));
                }
            }
        }
    }

    private static JSONObject messageRecord(MessagesController controller, TLRPC.Message message) throws Exception {
        JSONObject r = new JSONObject();
        r.put("t", "m");
        r.put("id", message.id);
        r.put("date", message.date);
        long from = MessageObject.getFromChatId(message);
        r.put("from", from);
        r.put("name", peerName(controller, from));
        if (!TextUtils.isEmpty(message.message)) r.put("text", message.message);
        if (message.reply_to != null && message.reply_to.reply_to_msg_id != 0) r.put("reply", message.reply_to.reply_to_msg_id);
        if (message.fwd_from != null) {
            String fwd = message.fwd_from.from_name;
            if (fwd == null && message.fwd_from.from_id != null) fwd = peerName(controller, MessageObject.getPeerId(message.fwd_from.from_id));
            r.put("fwd", fwd == null ? "" : fwd);
        }
        String type = mediaType(message);
        if (type != null) {
            r.put("mt", type);
            if (message.media != null && message.media.document != null) {
                String name = FileLoader.getDocumentFileName(message.media.document);
                if (!TextUtils.isEmpty(name)) r.put("mn", name);
            }
        }
        if (message.media instanceof TLRPC.TL_messageMediaWebPage && message.media.webpage != null && message.media.webpage.url != null) {
            r.put("link", message.media.webpage.url);
        }
        return r;
    }

    private static String peerName(MessagesController controller, long id) {
        if (id > 0) {
            TLRPC.User user = controller.getUser(id);
            return user != null ? UserObject.getUserName(user) : String.valueOf(id);
        } else if (id < 0) {
            TLRPC.Chat chat = controller.getChat(-id);
            return chat != null ? chat.title : String.valueOf(id);
        }
        return "";
    }

    private static String mediaType(TLRPC.Message message) {
        TLRPC.MessageMedia m = message.media;
        if (m == null || m instanceof TLRPC.TL_messageMediaEmpty || m instanceof TLRPC.TL_messageMediaWebPage) return null;
        if (m instanceof TLRPC.TL_messageMediaPhoto) return "photo";
        if (m instanceof TLRPC.TL_messageMediaDocument && m.document != null) {
            TLRPC.Document d = m.document;
            if (MessageObject.isRoundVideoDocument(d)) return "round";
            if (MessageObject.isVoiceDocument(d)) return "voice";
            if (MessageObject.isStickerDocument(d) || MessageObject.isAnimatedStickerDocument(d, true)) return "sticker";
            if (MessageObject.isVideoDocument(d) || MessageObject.isGifDocument(d)) return "video";
            if (MessageObject.isMusicDocument(d)) return "audio";
            return "file";
        }
        if (m instanceof TLRPC.TL_messageMediaGeo || m instanceof TLRPC.TL_messageMediaVenue) return "location";
        if (m instanceof TLRPC.TL_messageMediaContact) return "contact";
        if (m instanceof TLRPC.TL_messageMediaPoll) return "poll";
        return "other";
    }

    // ---- writer thread ----

    private static final class ChatState {
        final HashSet<Integer> ids = new HashSet<>();
        final HashMap<Integer, String> texts = new HashMap<>();
    }

    private ChatState state(int account, long dialogId) {
        String k = key(account, dialogId);
        ChatState state = states.get(k);
        if (state != null) return state;
        state = new ChatState();
        File jsonl = new File(folder(account, dialogId), "messages.jsonl");
        if (jsonl.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(jsonl))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject r = new JSONObject(line);
                        String t = r.optString("t");
                        if ("m".equals(t)) {
                            state.ids.add(r.optInt("id"));
                            state.texts.put(r.optInt("id"), r.optString("text", ""));
                        } else if ("e".equals(t)) {
                            state.texts.put(r.optInt("id"), r.optString("text", ""));
                        }
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        states.put(k, state);
        return state;
    }

    private void ensureMeta(int account, long dialogId) {
        File jsonl = new File(folder(account, dialogId), "messages.jsonl");
        if (jsonl.exists()) return;
        try {
            JSONObject meta = new JSONObject();
            meta.put("t", "meta");
            meta.put("title", prefs().getString("title_" + key(account, dialogId), ""));
            meta.put("self", UserConfig.getInstance(account).getClientUserId());
            meta.put("dialog", dialogId);
            append(account, dialogId, meta);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void writeEdits(int account, long dialogId, ArrayList<TLRPC.Message> messages) {
        ChatState state = state(account, dialogId);
        for (TLRPC.Message message : messages) {
            if (!state.ids.contains(message.id)) continue;
            String text = message.message == null ? "" : message.message;
            String old = state.texts.get(message.id);
            if (text.equals(old)) continue; // reactions, views and other non-text updates
            state.texts.put(message.id, text);
            try {
                JSONObject r = new JSONObject();
                r.put("t", "e");
                r.put("id", message.id);
                r.put("date", message.edit_date != 0 ? message.edit_date : (int) (System.currentTimeMillis() / 1000));
                r.put("text", text);
                append(account, dialogId, r);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private void writeDeletes(int account, long dialogId, ArrayList<Integer> ids) {
        ChatState state = state(account, dialogId);
        JSONArray known = new JSONArray();
        for (int mid : ids) if (state.ids.contains(mid)) known.put(mid);
        if (known.length() == 0) return;
        try {
            JSONObject r = new JSONObject();
            r.put("t", "d");
            r.put("ids", known);
            r.put("at", (int) (System.currentTimeMillis() / 1000));
            append(account, dialogId, r);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void append(int account, long dialogId, JSONObject record) {
        File dir = folder(account, dialogId);
        if (!dir.exists() && !dir.mkdirs()) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "messages.jsonl"), true), StandardCharsets.UTF_8)) {
            w.write(record.toString());
            w.write('\n');
        } catch (Exception e) {
            FileLog.e(e);
        }
        scheduleHtml(account, dialogId);
    }

    private void scheduleHtml(int account, long dialogId) {
        String k = key(account, dialogId);
        if (!htmlScheduled.add(k)) return;
        AndroidUtilities.runOnUIThread(() -> writer.execute(() -> {
            htmlScheduled.remove(k);
            MoggoChatArchiveHtml.write(folder(account, dialogId));
        }), HTML_DELAY_MS);
    }

    // ---- media thread ----

    private void saveMedia(int account, long dialogId, MessageObject mo) {
        try {
            if (mo.getSize() > MAX_MEDIA_BYTES) return;
            File source = load(account, mo);
            if (source == null) return;
            String ext = FileLoader.getFileExtension(source);
            if (TextUtils.isEmpty(ext)) ext = mo.isPhoto() ? "jpg" : "bin";
            ext = safeExtension(ext);
            File dir = new File(folder(account, dialogId), "media");
            if (!dir.exists() && !dir.mkdirs()) return;
            File dest = new File(dir, mo.getId() + "." + ext);
            if (!dest.exists()) {
                try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(dest)) {
                    AndroidUtilities.copyFile(in, out);
                }
            }
            JSONObject r = new JSONObject();
            r.put("t", "f");
            r.put("id", mo.getId());
            r.put("path", "media/" + dest.getName());
            writer.execute(() -> append(account, dialogId, r));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static File existing(int account, MessageObject mo) {
        FileLoader loader = FileLoader.getInstance(account);
        File f = loader.getPathToMessage(mo.messageOwner);
        if (f != null && f.exists()) return f;
        f = loader.getPathToMessage(mo.messageOwner, true, false);
        return f != null && f.exists() ? f : null;
    }

    private static File load(int account, MessageObject mo) throws InterruptedException {
        File file = existing(account, mo);
        if (file != null) return file;
        String fileName = mo.getFileName();
        if (TextUtils.isEmpty(fileName)) return null;
        CountDownLatch latch = new CountDownLatch(1);
        NotificationCenter.NotificationCenterDelegate waiter = (id, acc, args) -> {
            if (args.length > 0 && fileName.equals(args[0])) latch.countDown();
        };
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(waiter, NotificationCenter.fileLoaded);
            center.addObserver(waiter, NotificationCenter.fileLoadFailed);
            FileLoader loader = FileLoader.getInstance(account);
            TLRPC.Document document = mo.getDocument();
            if (document != null) {
                loader.loadFile(document, mo, FileLoader.PRIORITY_LOW, 0);
            } else if (mo.isPhoto()) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(mo.photoThumbs, AndroidUtilities.getPhotoSize());
                if (size != null) {
                    loader.loadFile(ImageLocation.getForObject(size, mo.photoThumbsObject), mo, "jpg", FileLoader.PRIORITY_LOW, 0);
                } else {
                    latch.countDown();
                }
            } else {
                latch.countDown();
            }
        });
        latch.await(15, TimeUnit.MINUTES);
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.removeObserver(waiter, NotificationCenter.fileLoaded);
            center.removeObserver(waiter, NotificationCenter.fileLoadFailed);
        });
        return existing(account, mo);
    }

    // ---- back-fill ----

    private void backfill(int account, long dialogId, int limit, int offsetId, ArrayList<TLRPC.Message> collected) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        req.offset_id = offsetId;
        req.limit = 100;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            int lastId = 0;
            boolean end = true;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                for (TLRPC.Message m : res.messages) {
                    lastId = m.id;
                    if (!(m instanceof TLRPC.TL_messageService)) collected.add(m);
                }
                end = res.messages.size() < 100 || lastId == 0;
            }
            if (!end && collected.size() < limit) {
                backfill(account, dialogId, limit, lastId, collected);
                return;
            }
            Collections.sort(collected, (a, b) -> Integer.compare(a.id, b.id)); // oldest first
            record(account, dialogId, collected);
        }));
    }

    static String formatDate(long unix) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(unix * 1000L));
    }
}
