package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.nextalone.nagram.NaConfig;

/**
 * Moggogram "@ugar" through the user's own Google account: talks to gemini.google.com the way the
 * website does, with the cookies of a Google sign-in made in a WebView. Image generation is then
 * covered by the account's Gemini subscription instead of a paid API key. Unofficial: Google may
 * change the protocol at any time. Requests go straight from the phone, so Gemini has to be
 * reachable from it (a VPN in Russia).
 */
public final class MoggoGeminiWeb {

    private static final String APP = "https://gemini.google.com/app";
    private static final String GENERATE = "https://gemini.google.com/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate";
    private static final String UPLOAD = "https://content-push.googleapis.com/upload";
    private static final String ROTATE = "https://accounts.google.com/RotateCookies";
    private static final String DEFAULT_PUSH_ID = "feeds/mcudyrk2a4khkz";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36";

    /** Error a user can read; shown as is. */
    public static final class UserError extends Exception {
        public UserError(String message) {
            super(message);
        }
    }

    private MoggoGeminiWeb() {
    }

    // ---------------------------------------------------------------- session

    public static boolean isSignedIn() {
        return cookies().containsKey("__Secure-1PSID");
    }

    public static void signOut() {
        NaConfig.INSTANCE.getMoggoGeminiCookies().setConfigString("");
    }

    /** Saves the cookie header of a Google sign-in; false if it has no Google session in it. */
    public static boolean saveCookies(String cookieHeader) {
        Map<String, String> map = parseCookieHeader(cookieHeader);
        if (!map.containsKey("__Secure-1PSID")) return false;
        storeCookies(map);
        return true;
    }

    private static Map<String, String> cookies() {
        return parseCookieHeader(NaConfig.INSTANCE.getMoggoGeminiCookies().String());
    }

    private static void storeCookies(Map<String, String> map) {
        NaConfig.INSTANCE.getMoggoGeminiCookies().setConfigString(cookieHeader(map));
    }

    private static Map<String, String> parseCookieHeader(String header) {
        Map<String, String> map = new LinkedHashMap<>();
        if (TextUtils.isEmpty(header)) return map;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) map.put(name, value);
        }
        return map;
    }

    private static String cookieHeader(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- http

    private static final class Response {
        int code;
        byte[] body;

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static Response request(String method, String url, Map<String, String> headers, byte[] body) throws Exception {
        Map<String, String> jar = cookies();
        String current = url;
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(180000);
                c.setRequestMethod(method);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Cookie", cookieHeader(jar));
                if (headers != null) {
                    for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                }
                if (body != null) {
                    c.setDoOutput(true);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(body);
                    }
                }
                int code = c.getResponseCode();
                List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
                if (setCookies != null && mergeSetCookies(jar, setCookies)) storeCookies(jar);
                String location = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && location != null && redirects < 8) {
                    current = new URL(new URL(current), location).toString();
                    method = "GET";
                    body = null;
                    continue;
                }
                Response r = new Response();
                r.code = code;
                r.body = readBytes(code >= 400 ? c.getErrorStream() : c.getInputStream());
                return r;
            } finally {
                c.disconnect();
            }
        }
    }

    private static boolean mergeSetCookies(Map<String, String> jar, List<String> setCookies) {
        boolean changed = false;
        for (String header : setCookies) {
            if (header == null) continue;
            String first = header.split(";", 2)[0];
            int eq = first.indexOf('=');
            if (eq <= 0) continue;
            String name = first.substring(0, eq).trim();
            String value = first.substring(eq + 1).trim();
            // deletions and empty values never replace a working session cookie
            if (value.isEmpty() || header.toLowerCase().contains("max-age=0")) continue;
            if (!value.equals(jar.get(name))) {
                jar.put(name, value);
                changed = true;
            }
        }
        return changed;
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- protocol

    private static final class Init {
        String accessToken;
        String buildLabel;
        String sessionId;
        String pushId;
    }

    private static String find(String html, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\\s*\"(.*?)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static Init init() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            Response r = request("GET", APP, null, null);
            String html = r.text();
            Init init = new Init();
            init.accessToken = find(html, "SNlM0e");
            init.buildLabel = find(html, "cfb2h");
            init.sessionId = find(html, "FdrFJe");
            init.pushId = find(html, "qKIAYe");
            if (r.code == 200 && !TextUtils.isEmpty(init.accessToken)) return init;
            if (attempt == 0) {
                // __Secure-1PSIDTS expires often; ask Google for a fresh one and try once more
                Map<String, String> h = new LinkedHashMap<>();
                h.put("Content-Type", "application/json");
                h.put("Origin", "https://accounts.google.com");
                Response rotate = request("POST", ROTATE, h, "[000,\"-0000000000000000000\"]".getBytes(StandardCharsets.UTF_8));
                if (rotate.code == 401) break;
            }
        }
        throw new UserError(LocaleController.getString(R.string.MoggoUgarSessionExpired));
    }

    private static String upload(Init init, byte[] jpeg, String filename) throws Exception {
        String boundary = "----moggo" + Long.toHexString(new Random().nextLong());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(jpeg);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        h.put("Origin", "https://gemini.google.com");
        h.put("Referer", "https://gemini.google.com/");
        h.put("X-Tenant-Id", "bard-storage");
        h.put("Push-ID", TextUtils.isEmpty(init.pushId) ? DEFAULT_PUSH_ID : init.pushId);
        Response r = request("POST", UPLOAD, h, body.toByteArray());
        if (r.code != 200) throw new UserError("Gemini upload " + r.code);
        return r.text().trim();
    }

    /** Generates an image; refs are JPEG bytes. Returns the saved file. */
    public static File generate(String prompt, List<byte[]> refs) throws Exception {
        if (!isSignedIn()) throw new UserError(LocaleController.getString(R.string.MoggoUgarSessionExpired));
        Init init = init();

        JSONArray files = null;
        if (refs != null && !refs.isEmpty()) {
            files = new JSONArray();
            for (int i = 0; i < refs.size(); i++) {
                String name = "ref" + (i + 1) + ".jpg";
                String id = upload(init, refs.get(i), name);
                files.put(new JSONArray().put(new JSONArray().put(id)).put(name));
            }
        }

        String text;
        boolean hasRefs = files != null;
        if (TextUtils.isEmpty(prompt)) {
            text = "Improve this image and generate a new version of it.";
        } else if (hasRefs) {
            text = "Generate an image using the attached images as references: " + prompt;
        } else {
            text = "Generate an image: " + prompt;
        }

        Parsed parsed = stream(init, text, files);
        if (parsed.imageUrl == null) {
            throwFor(parsed);
            throw new UserError(LocaleController.formatString(R.string.MoggoUgarNoImage, parsed.text == null ? "" : parsed.text));
        }

        String imageUrl = parsed.imageUrl;
        if (imageUrl.contains("=s1024-rj")) imageUrl = imageUrl.replace("=s1024-rj", "=s2048-rj");
        else if (!imageUrl.contains("=s2048-rj")) imageUrl += "=s2048-rj";
        Map<String, String> ih = new LinkedHashMap<>();
        ih.put("Referer", "https://gemini.google.com/");
        Response image = request("GET", imageUrl, ih, null);
        if (image.code != 200 || image.body.length == 0) throw new UserError("Gemini image " + image.code);
        File file = new File(AndroidUtilities.getCacheDir(), "ugar_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(image.body);
        }
        return file;
    }

    /** Text answer of Gemini (chat summaries). */
    public static String ask(String prompt) throws Exception {
        if (!isSignedIn()) throw new UserError(LocaleController.getString(R.string.MoggoUgarSessionExpired));
        Parsed parsed = stream(init(), prompt, null);
        if (TextUtils.isEmpty(parsed.text)) {
            throwFor(parsed);
            throw new UserError(LocaleController.formatString(R.string.MoggoUgarNoImage, ""));
        }
        return parsed.text;
    }

    private static void throwFor(Parsed parsed) throws UserError {
        if (parsed.errorCode == 1037) throw new UserError(LocaleController.getString(R.string.MoggoUgarWebLimit));
        if (parsed.errorCode == 1060) throw new UserError(LocaleController.getString(R.string.MoggoUgarWebBlocked));
        if (parsed.errorCode != 0) throw new UserError("Gemini error " + parsed.errorCode);
    }

    private static Parsed stream(Init init, String text, JSONArray files) throws Exception {
        JSONArray message = new JSONArray().put(text).put(0).put(JSONObject.NULL).put(files == null ? JSONObject.NULL : files)
                .put(JSONObject.NULL).put(JSONObject.NULL).put(0);
        String uuid = UUID.randomUUID().toString().toUpperCase();
        Object[] inner = new Object[81];
        inner[0] = message;
        inner[1] = new JSONArray().put("ru");
        inner[2] = new JSONArray().put("").put("").put("").put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL)
                .put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL).put("");
        inner[6] = new JSONArray().put(1);
        inner[7] = 1;
        inner[10] = 1;
        inner[11] = 0;
        inner[17] = new JSONArray().put(new JSONArray().put(0));
        inner[18] = 0;
        inner[27] = 1;
        inner[30] = new JSONArray().put(4);
        inner[41] = new JSONArray().put(1);
        inner[53] = 0;
        inner[59] = uuid;
        inner[61] = new JSONArray();
        inner[68] = 1;
        inner[79] = 1;
        inner[80] = 1;
        JSONArray innerJson = new JSONArray();
        for (Object o : inner) innerJson.put(o == null ? JSONObject.NULL : o);
        String fReq = new JSONArray().put(JSONObject.NULL).put(innerJson.toString()).toString();

        StringBuilder url = new StringBuilder(GENERATE).append("?hl=ru&rt=c&_reqid=").append(10000 + new Random().nextInt(90000));
        if (!TextUtils.isEmpty(init.buildLabel)) url.append("&bl=").append(URLEncoder.encode(init.buildLabel, "UTF-8"));
        if (!TextUtils.isEmpty(init.sessionId)) url.append("&f.sid=").append(URLEncoder.encode(init.sessionId, "UTF-8"));
        String form = "at=" + URLEncoder.encode(init.accessToken, "UTF-8") + "&f.req=" + URLEncoder.encode(fReq, "UTF-8");

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
        h.put("Origin", "https://gemini.google.com");
        h.put("Referer", "https://gemini.google.com/");
        h.put("X-Same-Domain", "1");
        h.put("x-goog-ext-525005358-jspb", "[\"" + uuid + "\",1]");
        Response r = request("POST", url.toString(), h, form.getBytes(StandardCharsets.UTF_8));
        if (r.code == 401 || r.code == 403) throw new UserError(LocaleController.getString(R.string.MoggoUgarSessionExpired));
        if (r.code != 200) throw new UserError("Gemini " + r.code);
        return parse(r.text());
    }

    // ---------------------------------------------------------------- response parsing

    private static final class Parsed {
        String imageUrl;
        String text;
        int errorCode;
    }

    /** StreamGenerate answers with length-prefixed frames, one JSON array per line. */
    private static Parsed parse(String response) {
        Parsed parsed = new Parsed();
        for (String line : response.split("\n")) {
            line = line.trim();
            if (!line.startsWith("[")) continue;
            JSONArray frame;
            try {
                frame = new JSONArray(line);
            } catch (Exception e) {
                continue;
            }
            for (int i = 0; i < frame.length(); i++) {
                JSONArray part = frame.optJSONArray(i);
                if (part == null) continue;
                Object code = path(part, 5, 2, 0, 1, 0);
                if (code instanceof Number) parsed.errorCode = ((Number) code).intValue();
                String inner = part.optString(2, null);
                if (inner == null || !inner.startsWith("[")) continue;
                JSONArray body;
                try {
                    body = new JSONArray(inner);
                } catch (Exception e) {
                    continue;
                }
                JSONArray candidates = body.optJSONArray(4);
                if (candidates == null) continue;
                for (int c = 0; c < candidates.length(); c++) {
                    JSONArray candidate = candidates.optJSONArray(c);
                    if (candidate == null) continue;
                    Object text = path(candidate, 1, 0);
                    if (text instanceof String) parsed.text = cleanText((String) text);
                    Object images = path(field(candidate.optJSONArray(12), 7), 0);
                    if (images instanceof JSONArray && ((JSONArray) images).length() > 0) {
                        Object url = path((JSONArray) images, 0, 0, 3, 3);
                        if (url instanceof String) parsed.imageUrl = (String) url;
                    }
                }
            }
        }
        return parsed;
    }

    private static String cleanText(String text) {
        return text.replaceAll("https?://googleusercontent\\.com/(?:\\w+/)+\\d+\\n*", "").trim();
    }

    /** JSPB field: positional slot, or the sparse dict in the last slot keyed by field number + 1. */
    private static JSONArray field(JSONArray container, int index) {
        if (container == null || container.length() == 0) return null;
        Object value = index < container.length() ? container.opt(index) : null;
        if (value instanceof JSONArray && ((JSONArray) value).length() > 0) return (JSONArray) value;
        Object last = container.opt(container.length() - 1);
        if (last instanceof JSONObject) return ((JSONObject) last).optJSONArray(String.valueOf(index + 1));
        return null;
    }

    private static Object path(JSONArray root, int... indexes) {
        Object current = root;
        for (int index : indexes) {
            if (!(current instanceof JSONArray)) return null;
            JSONArray array = (JSONArray) current;
            if (index >= array.length()) return null;
            current = array.opt(index);
        }
        return current == JSONObject.NULL ? null : current;
    }
}
