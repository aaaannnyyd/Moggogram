package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Every "@ugar" picture, kept in the app's files (not the cache Telegram cleans) with its prompt.
 * Newest first, capped so it never eats the phone.
 */
public final class MoggoUgarHistory {

    private static final int MAX_ITEMS = 300;

    public static final class Item {
        public File file;
        public String prompt;
        public boolean sticker;
        public long date;
    }

    private static ArrayList<Item> items;

    private MoggoUgarHistory() {
    }

    private static File dir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "ugar");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File index() {
        return new File(dir(), "index.json");
    }

    public static synchronized ArrayList<Item> list() {
        load();
        return new ArrayList<>(items);
    }

    private static void load() {
        if (items != null) return;
        items = new ArrayList<>();
        File index = index();
        if (!index.exists()) return;
        try (InputStream in = new FileInputStream(index)) {
            byte[] data = new byte[(int) index.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONArray array = new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Item item = new Item();
                item.file = new File(dir(), o.getString("f"));
                item.prompt = o.optString("p");
                item.sticker = o.optBoolean("s");
                item.date = o.optLong("d");
                if (item.file.exists()) items.add(item);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void save() {
        try {
            JSONArray array = new JSONArray();
            for (Item item : items) {
                array.put(new JSONObject().put("f", item.file.getName()).put("p", item.prompt).put("s", item.sticker).put("d", item.date));
            }
            try (OutputStream out = new FileOutputStream(index())) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Moves a fresh picture into the history and returns its new place. */
    public static synchronized File add(File source, String prompt, boolean sticker) {
        load();
        String name = source.getName();
        File target = new File(dir(), name);
        if (!source.renameTo(target)) {
            try {
                AndroidUtilities.copyFile(source, target);
                source.delete();
            } catch (Exception e) {
                FileLog.e(e);
                return source;
            }
        }
        Item item = new Item();
        item.file = target;
        item.prompt = prompt == null ? "" : prompt;
        item.sticker = sticker;
        item.date = System.currentTimeMillis();
        items.add(0, item);
        while (items.size() > MAX_ITEMS) {
            Item old = items.remove(items.size() - 1);
            old.file.delete();
        }
        save();
        return target;
    }

    public static synchronized void remove(Item item) {
        load();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).file.equals(item.file)) {
                items.remove(i).file.delete();
                break;
            }
        }
        save();
    }
}
