package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;

/** Builds chat.html of a live archive from messages.jsonl (see {@link MoggoChatArchive}). */
final class MoggoChatArchiveHtml {

    private MoggoChatArchiveHtml() {
    }

    private static final String STYLE =
            ":root{--bg:#e7ebf0;--in:#fff;--out:#e1ffc7;--text:#111;--meta:#8a8f96;--name:#3a6fb0;--del:#d93a3a}"
            + "@media (prefers-color-scheme:dark){:root{--bg:#0f1419;--in:#1e2630;--out:#2b5278;--text:#eef1f4;--meta:#8d99a6;--name:#7fb3ec}}"
            + "body{margin:0;background:var(--bg);color:var(--text);font:15px/1.4 -apple-system,Segoe UI,Roboto,sans-serif}"
            + "header{position:sticky;top:0;background:var(--in);padding:12px 16px;font-weight:600;box-shadow:0 1px 3px #0002}"
            + "header small{display:block;font-weight:400;color:var(--meta)}"
            + "main{max-width:760px;margin:0 auto;padding:12px}"
            + ".day{text-align:center;color:var(--meta);font-size:13px;margin:14px 0 6px}"
            + ".m{max-width:78%;margin:4px 0;padding:6px 10px 4px;border-radius:12px;background:var(--in);word-wrap:break-word;box-shadow:0 1px 1px #0001}"
            + ".o{margin-left:auto;background:var(--out)}"
            + ".n{color:var(--name);font-weight:600;font-size:13px}"
            + ".q{border-left:3px solid var(--name);padding-left:6px;color:var(--meta);font-size:13px;margin:2px 0}"
            + ".t{white-space:pre-wrap}"
            + ".s{color:var(--meta);font-size:12px;text-align:right;margin-top:2px}"
            + ".del{outline:2px solid var(--del)}.del .s{color:var(--del)}"
            + "img,video{max-width:100%;border-radius:8px;display:block;margin:4px 0}"
            + "video.round{width:240px;height:240px;object-fit:cover;border-radius:50%}"
            + "audio{width:100%;margin:4px 0}"
            + "details{font-size:13px;color:var(--meta)}a{color:var(--name)}";

    static void write(File dir) {
        File jsonl = new File(dir, "messages.jsonl");
        if (!jsonl.exists()) return;
        String title = "";
        long self = 0;
        TreeMap<Integer, JSONObject> messages = new TreeMap<>();
        HashMap<Integer, String> files = new HashMap<>();
        HashMap<Integer, ArrayList<JSONObject>> edits = new HashMap<>();
        HashMap<Integer, Integer> deleted = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(jsonl))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject r;
                try {
                    r = new JSONObject(line);
                } catch (Exception e) {
                    continue;
                }
                switch (r.optString("t")) {
                    case "meta":
                        title = r.optString("title");
                        self = r.optLong("self");
                        break;
                    case "m":
                        messages.put(r.optInt("id"), r);
                        break;
                    case "f":
                        files.put(r.optInt("id"), r.optString("path"));
                        break;
                    case "e": {
                        ArrayList<JSONObject> list = edits.get(r.optInt("id"));
                        if (list == null) edits.put(r.optInt("id"), list = new ArrayList<>());
                        list.add(r);
                        break;
                    }
                    case "d": {
                        JSONArray ids = r.optJSONArray("ids");
                        if (ids != null) for (int i = 0; i < ids.length(); i++) deleted.put(ids.optInt(i), r.optInt("at"));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }

        StringBuilder html = new StringBuilder(64 * 1024 + messages.size() * 300);
        html.append("<!doctype html><html lang=ru><head><meta charset=utf-8>")
                .append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(esc(title)).append("</title><style>").append(STYLE).append("</style></head><body>")
                .append("<header>").append(esc(title)).append("<small>Живой архив Moggogram · сообщений: ").append(messages.size())
                .append(" · обновлено ").append(MoggoChatArchive.formatDate(System.currentTimeMillis() / 1000)).append("</small></header><main>");
        String lastDay = "";
        for (JSONObject m : messages.values()) {
            int id = m.optInt("id");
            String when = MoggoChatArchive.formatDate(m.optLong("date"));
            String day = when.substring(0, 10);
            if (!day.equals(lastDay)) {
                html.append("<div class=day>").append(day).append("</div>");
                lastDay = day;
            }
            boolean out = self != 0 && m.optLong("from") == self;
            Integer deletedAt = deleted.get(id);
            html.append("<div id=m").append(id).append(" class=\"m").append(out ? " o" : "").append(deletedAt != null ? " del" : "").append("\">");
            if (!out) html.append("<div class=n>").append(esc(m.optString("name"))).append("</div>");
            if (m.has("fwd")) html.append("<div class=q>Переслано от ").append(esc(m.optString("fwd"))).append("</div>");
            if (m.has("reply")) {
                JSONObject replied = messages.get(m.optInt("reply"));
                String preview = replied == null ? "сообщение" : replied.optString("text", "[" + replied.optString("mt", "медиа") + "]");
                if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
                html.append("<a class=q href=#m").append(m.optInt("reply")).append(">").append(esc(preview)).append("</a>");
            }
            appendMedia(html, m, files.get(id));
            ArrayList<JSONObject> history = edits.get(id);
            String text = history == null ? m.optString("text", "") : history.get(history.size() - 1).optString("text", "");
            if (!text.isEmpty()) html.append("<div class=t>").append(esc(text)).append("</div>");
            if (m.has("link")) html.append("<a href=\"").append(esc(m.optString("link"))).append("\">").append(esc(m.optString("link"))).append("</a>");
            if (history != null) {
                html.append("<details><summary>изменено ").append(history.size()).append(" раз</summary><div class=t>Исходный текст: ")
                        .append(esc(m.optString("text", ""))).append("</div>");
                for (int i = 0; i < history.size() - 1; i++) {
                    html.append("<div class=t>").append(MoggoChatArchive.formatDate(history.get(i).optLong("date"))).append(": ")
                            .append(esc(history.get(i).optString("text", ""))).append("</div>");
                }
                html.append("</details>");
            }
            html.append("<div class=s>");
            if (deletedAt != null) html.append("удалено ").append(MoggoChatArchive.formatDate(deletedAt)).append(" · ");
            html.append(when.substring(11)).append("</div></div>");
        }
        html.append("</main></body></html>");
        File tmp = new File(dir, "chat.html.tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(html.toString());
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        File target = new File(dir, "chat.html");
        if (!tmp.renameTo(target)) {
            target.delete();
            tmp.renameTo(target);
        }
    }

    private static void appendMedia(StringBuilder html, JSONObject m, String path) {
        String type = m.optString("mt", null);
        if (type == null) return;
        if (path == null) {
            html.append("<div class=q>[").append(label(type)).append(m.has("mn") ? ": " + esc(m.optString("mn")) : "").append("]</div>");
            return;
        }
        String src = esc(path);
        switch (type) {
            case "photo":
                html.append("<a href=\"").append(src).append("\"><img loading=lazy src=\"").append(src).append("\"></a>");
                break;
            case "sticker":
                if (path.endsWith(".webp") || path.endsWith(".png")) {
                    html.append("<img loading=lazy style=\"width:160px\" src=\"").append(src).append("\">");
                } else {
                    html.append("<div class=q>[стикер]</div>");
                }
                break;
            case "video":
                html.append("<video controls preload=none src=\"").append(src).append("\"></video>");
                break;
            case "round":
                html.append("<video class=round controls preload=none src=\"").append(src).append("\"></video>");
                break;
            case "voice":
            case "audio":
                html.append("<audio controls preload=none src=\"").append(src).append("\"></audio>");
                break;
            default:
                html.append("<a href=\"").append(src).append("\">📎 ").append(esc(m.optString("mn", path))).append("</a>");
        }
    }

    private static String label(String type) {
        switch (type) {
            case "photo": return "фото";
            case "video": return "видео";
            case "round": return "кружок";
            case "voice": return "голосовое";
            case "audio": return "музыка";
            case "sticker": return "стикер";
            case "location": return "геопозиция";
            case "contact": return "контакт";
            case "poll": return "опрос";
            case "file": return "файл";
            default: return "медиа";
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '"': b.append("&quot;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }
}
