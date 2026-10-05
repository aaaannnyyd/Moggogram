package org.telegram.messenger;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import tw.nekomimi.nekogram.SaveToDownloadReceiver;

/**
 * Moggogram: saves every photo / video / file / voice / music message of a chat sent after a
 * given date. Search goes through messages.search (server side, date bounded), downloads are
 * sequential and reported in one notification with a cancel action.
 */
public class MoggoMediaDownloader implements NotificationCenter.NotificationCenterDelegate {

    public static final int TYPE_PHOTO = 1;
    public static final int TYPE_VIDEO = 2;
    public static final int TYPE_FILE = 4;
    public static final int TYPE_VOICE = 8;
    public static final int TYPE_MUSIC = 16;

    public static final int MAX_FILES = 3000;
    private static final int PAGE_SIZE = 100;
    private static final long FILE_TIMEOUT_MINUTES = 15;

    public interface SearchCallback {
        void onResult(ArrayList<MessageObject> messages, long totalBytes, boolean truncated);
    }

    // ---- search ----

    public static void search(int account, long dialogId, int minDate, int types, SearchCallback callback) {
        ArrayList<TLRPC.MessagesFilter> filters = new ArrayList<>();
        if ((types & TYPE_PHOTO) != 0) filters.add(new TLRPC.TL_inputMessagesFilterPhotos());
        if ((types & TYPE_VIDEO) != 0) filters.add(new TLRPC.TL_inputMessagesFilterVideo());
        if ((types & TYPE_FILE) != 0) filters.add(new TLRPC.TL_inputMessagesFilterDocument());
        if ((types & TYPE_VOICE) != 0) filters.add(new TLRPC.TL_inputMessagesFilterRoundVoice());
        if ((types & TYPE_MUSIC) != 0) filters.add(new TLRPC.TL_inputMessagesFilterMusic());
        Search search = new Search(account, dialogId, minDate, types, filters, callback);
        search.next(0);
    }

    private static class Search {
        final int account;
        final long dialogId;
        final int minDate;
        final int types;
        final ArrayList<TLRPC.MessagesFilter> filters;
        final SearchCallback callback;
        final ArrayList<MessageObject> result = new ArrayList<>();
        final HashSet<Integer> seen = new HashSet<>();
        int filterIndex;
        long totalBytes;

        Search(int account, long dialogId, int minDate, int types, ArrayList<TLRPC.MessagesFilter> filters, SearchCallback callback) {
            this.account = account;
            this.dialogId = dialogId;
            this.minDate = minDate;
            this.types = types;
            this.filters = filters;
            this.callback = callback;
        }

        void next(int offsetId) {
            if (filterIndex >= filters.size() || result.size() >= MAX_FILES) {
                boolean truncated = result.size() >= MAX_FILES;
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result, totalBytes, truncated));
                return;
            }
            TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
            req.q = "";
            req.filter = filters.get(filterIndex);
            req.min_date = minDate;
            req.max_date = 0;
            req.offset_id = offsetId;
            req.limit = PAGE_SIZE;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.messages_Messages)) {
                    filterIndex++;
                    next(0);
                    return;
                }
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                int lastId = 0;
                boolean older = false;
                for (TLRPC.Message message : res.messages) {
                    lastId = message.id;
                    if (minDate > 0 && message.date < minDate) {
                        older = true;
                        continue;
                    }
                    if (!seen.add(message.id)) continue;
                    MessageObject messageObject = new MessageObject(account, message, false, false);
                    if (!accepts(messageObject, types)) continue;
                    result.add(messageObject);
                    totalBytes += messageObject.getSize();
                    if (result.size() >= MAX_FILES) break;
                }
                if (res.messages.size() < PAGE_SIZE || older || lastId == 0) {
                    filterIndex++;
                    next(0);
                } else {
                    next(lastId);
                }
            }));
        }
    }

    private static boolean accepts(MessageObject messageObject, int types) {
        if (messageObject.isSticker() || messageObject.isAnimatedSticker() || messageObject.isGif()) return false;
        if (messageObject.isVoice() || messageObject.isRoundVideo()) return (types & TYPE_VOICE) != 0;
        if (messageObject.isMusic()) return (types & TYPE_MUSIC) != 0;
        if (messageObject.isVideo()) return (types & TYPE_VIDEO) != 0;
        if (messageObject.isPhoto()) return (types & TYPE_PHOTO) != 0;
        return messageObject.getDocument() != null && (types & TYPE_FILE) != 0;
    }

    // ---- download ----

    public static void download(Context context, int account, ArrayList<MessageObject> messages) {
        download(context, account, messages, null);
    }

    /** @param zipName non-null: pack everything into one ZIP in Downloads instead of separate files */
    public static void download(Context context, int account, ArrayList<MessageObject> messages, String zipName) {
        MoggoMediaDownloader downloader = new MoggoMediaDownloader(context, account, messages);
        downloader.zipName = zipName;
        downloader.start();
    }

    private String zipName;
    private ZipOutputStream zip;
    private File zipFile;

    private final Context context;
    private final int account;
    private final ArrayList<MessageObject> messages;
    private final int notificationId = SaveToDownloadReceiver.createNotificationId();
    private volatile boolean cancelled;
    private volatile String waitingFileName;
    private volatile CountDownLatch waitingLatch;

    private MoggoMediaDownloader(Context context, int account, ArrayList<MessageObject> messages) {
        this.context = context.getApplicationContext();
        this.account = account;
        this.messages = messages;
    }

    private void start() {
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.fileLoaded);
        center.addObserver(this, NotificationCenter.fileLoadFailed);
        SaveToDownloadReceiver.showNotification(context, notificationId, messages.size(), () -> {
            cancelled = true;
            CountDownLatch latch = waitingLatch;
            if (latch != null) latch.countDown();
        });
        new Thread(this::run, "MoggoMediaDownloader").start();
    }

    private void run() {
        int saved = 0;
        int failed = 0;
        if (zipName != null) {
            try {
                zipFile = new File(AndroidUtilities.getCacheDir(), "moggo_" + System.currentTimeMillis() + ".zip");
                zip = new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(zipFile)));
            } catch (Exception e) {
                FileLog.e(e);
                zip = null;
            }
        }
        for (int i = 0; i < messages.size() && !cancelled; i++) {
            MessageObject messageObject = messages.get(i);
            try {
                File file = ensureLoaded(messageObject);
                if (file != null && !cancelled && (zip != null ? addToZip(messageObject, file) : save(messageObject, file))) {
                    saved++;
                } else if (!cancelled) {
                    failed++;
                }
            } catch (Exception e) {
                FileLog.e(e);
                failed++;
            }
            final int progress = (i + 1) * 100 / messages.size();
            AndroidUtilities.runOnUIThread(() -> SaveToDownloadReceiver.updateNotification(notificationId, progress));
        }
        if (zip != null) {
            try {
                zip.close();
                if (cancelled || saved == 0 || !saveZip()) {
                    if (!cancelled && saved > 0) failed += saved;
                    if (!cancelled && saved > 0) saved = 0;
                }
            } catch (Exception e) {
                FileLog.e(e);
                failed += saved;
                saved = 0;
            }
            zipFile.delete();
        }
        final int savedCount = saved;
        final int failedCount = failed;
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.removeObserver(this, NotificationCenter.fileLoaded);
            center.removeObserver(this, NotificationCenter.fileLoadFailed);
            SaveToDownloadReceiver.cancelNotification(notificationId);
            String text = LocaleController.formatString(R.string.MoggoDownloadDone, savedCount);
            if (failedCount > 0) text += " " + LocaleController.formatString(R.string.MoggoDownloadFailed, failedCount);
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        });
    }

    private File existingFile(MessageObject messageObject) {
        FileLoader loader = FileLoader.getInstance(account);
        String attachPath = messageObject.messageOwner.attachPath;
        if (!TextUtils.isEmpty(attachPath)) {
            File f = new File(attachPath);
            if (f.exists()) return f;
        }
        File f = loader.getPathToMessage(messageObject.messageOwner);
        if (f != null && f.exists()) return f;
        f = loader.getPathToMessage(messageObject.messageOwner, true, false);
        if (f != null && f.exists()) return f;
        return null;
    }

    private File ensureLoaded(MessageObject messageObject) throws InterruptedException {
        File file = existingFile(messageObject);
        if (file != null) return file;
        String fileName = messageObject.getFileName();
        if (TextUtils.isEmpty(fileName)) return null;
        CountDownLatch latch = new CountDownLatch(1);
        waitingFileName = fileName;
        waitingLatch = latch;
        AndroidUtilities.runOnUIThread(() -> {
            FileLoader loader = FileLoader.getInstance(account);
            TLRPC.Document document = messageObject.getDocument();
            if (document != null) {
                loader.loadFile(document, messageObject, FileLoader.PRIORITY_NORMAL, 0);
            } else if (messageObject.isPhoto()) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(
                        messageObject.photoThumbs, AndroidUtilities.getPhotoSize());
                if (size != null) {
                    loader.loadFile(ImageLocation.getForObject(size, messageObject.photoThumbsObject),
                            messageObject, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                } else {
                    latch.countDown();
                }
            } else {
                latch.countDown();
            }
        });
        latch.await(FILE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        waitingLatch = null;
        waitingFileName = null;
        return cancelled ? null : existingFile(messageObject);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.fileLoaded && id != NotificationCenter.fileLoadFailed) return;
        String name = (String) args[0];
        CountDownLatch latch = waitingLatch;
        if (latch != null && name != null && name.equals(waitingFileName)) latch.countDown();
    }

    private final java.util.HashSet<String> zipNames = new java.util.HashSet<>();

    // "2026-10-05_14-03-22_Anna_12345.ogg" — sortable by time, readable sender
    private boolean addToZip(MessageObject messageObject, File source) throws Exception {
        String ext = FileLoader.getFileExtension(source);
        if (TextUtils.isEmpty(ext)) ext = messageObject.isPhoto() ? "jpg" : "bin";
        String sender = "";
        long fromId = messageObject.getFromChatId();
        MessagesController controller = MessagesController.getInstance(account);
        if (fromId > 0) {
            TLRPC.User user = controller.getUser(fromId);
            if (user != null) sender = UserObject.getUserName(user);
        } else if (fromId < 0) {
            TLRPC.Chat chat = controller.getChat(-fromId);
            if (chat != null) sender = chat.title;
        }
        sender = sender.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (sender.length() > 32) sender = sender.substring(0, 32);
        String time = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
                .format(new java.util.Date(messageObject.messageOwner.date * 1000L));
        String name = time + (sender.isEmpty() ? "" : "_" + sender) + "_" + messageObject.getId() + "." + ext;
        if (!zipNames.add(name)) name = time + "_" + messageObject.getId() + "_" + zipNames.size() + "." + ext;
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                if (cancelled) return false;
                zip.write(buffer, 0, n);
            }
        }
        zip.closeEntry();
        return true;
    }

    private boolean saveZip() throws Exception {
        String name = zipName.replaceAll("[\\\\/:*?\"<>|]+", "_") + ".zip";
        if (Build.VERSION.SDK_INT >= 29) {
            return MediaController.saveFileInternal(2, zipFile, name, null) != null;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Moggogram");
        if (!dir.exists() && !dir.mkdirs()) return false;
        File dest = new File(dir, name);
        try (FileInputStream in = new FileInputStream(zipFile); FileOutputStream out = new FileOutputStream(dest)) {
            AndroidUtilities.copyFile(in, out);
        }
        AndroidUtilities.addMediaToGallery(dest.getAbsolutePath());
        return true;
    }

    // 0 = picture, 1 = video, 2 = downloads, 3 = music — same as MediaController.saveFile
    private static int saveType(MessageObject messageObject) {
        if (messageObject.isPhoto()) return 0;
        if (messageObject.isVideo() || messageObject.isRoundVideo()) return 1;
        if (messageObject.isMusic()) return 3;
        return 2;
    }

    private boolean save(MessageObject messageObject, File source) throws Exception {
        int type = saveType(messageObject);
        String name = null;
        TLRPC.Document document = messageObject.getDocument();
        if (document != null && type >= 2) {
            name = FileLoader.getDocumentFileName(document);
            if (TextUtils.isEmpty(name)) name = source.getName();
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return MediaController.saveFileInternal(type, source, name, messageObject) != null;
        }
        String directory = type == 0 ? Environment.DIRECTORY_PICTURES
                : type == 1 ? Environment.DIRECTORY_MOVIES
                : type == 3 ? Environment.DIRECTORY_MUSIC
                : Environment.DIRECTORY_DOWNLOADS;
        File dir = new File(Environment.getExternalStoragePublicDirectory(directory), "Moggogram");
        if (!dir.exists() && !dir.mkdirs()) return false;
        File dest = new File(dir, name != null ? name : source.getName());
        for (int i = 1; dest.exists() && i < 1000; i++) {
            String base = name != null ? name : source.getName();
            int dot = base.lastIndexOf('.');
            dest = new File(dir, dot > 0 ? base.substring(0, dot) + "(" + i + ")" + base.substring(dot) : base + "(" + i + ")");
        }
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(dest)) {
            AndroidUtilities.copyFile(in, out);
        }
        AndroidUtilities.addMediaToGallery(dest.getAbsolutePath());
        return true;
    }
}
