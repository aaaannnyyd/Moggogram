package org.telegram.messenger;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import xyz.nextalone.nagram.NaConfig;

/**
 * Moggogram "@ugar": image generation with the user's own Gemini key, right from the message field.
 * "@ugar prompt" as a message, or as the caption of attached photos (they become references), or as
 * a reply to a photo (also a reference). Requests go through the push gateway because Google
 * blocks the Gemini API for Russian IPs; the key is passed through, never stored there.
 */
public final class MoggoUgar {

    public static final String COMMAND = "@ugar";
    public static final String DEFAULT_MODEL = "gemini-3.1-flash-image";
    private static final String[] FALLBACK_MODELS = {"gemini-3.1-flash-image", "gemini-3-pro-image", "gemini-3.1-flash-lite-image", "gemini-2.5-flash-image"};
    private static final int MAX_REF_SIDE = 1536;

    private MoggoUgar() {
    }

    /** Prompt after the command, or null if the text is not an @ugar command. */
    public static String parse(CharSequence text) {
        if (text == null) return null;
        String s = text.toString().trim();
        if (s.length() < COMMAND.length() || !s.substring(0, COMMAND.length()).equalsIgnoreCase(COMMAND)) return null;
        if (s.length() > COMMAND.length() && !Character.isWhitespace(s.charAt(COMMAND.length()))) return null;
        return s.substring(COMMAND.length()).trim();
    }

    private static String key() {
        return NaConfig.INSTANCE.getMoggoGeminiKey().String().trim();
    }

    private static String model() {
        String m = NaConfig.INSTANCE.getMoggoGeminiModel().String().trim();
        return m.isEmpty() ? DEFAULT_MODEL : m;
    }

    // ---------------------------------------------------------------- generation

    public static void start(ChatActivity fragment, String prompt, ArrayList<String> refPaths, MessageObject reply) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        if (key().isEmpty()) {
            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle(LocaleController.getString(R.string.MoggoUgarTitle));
            b.setMessage(LocaleController.getString(R.string.MoggoUgarNoKey));
            b.setPositiveButton(LocaleController.getString(R.string.MoggoUgarSettings), (d, w) -> showSettings(activity));
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            fragment.showDialog(b.create());
            return;
        }
        if (TextUtils.isEmpty(prompt) && (refPaths == null || refPaths.isEmpty()) && !isPhoto(reply)) {
            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle(LocaleController.getString(R.string.MoggoUgarTitle));
            b.setMessage(LocaleController.getString(R.string.MoggoUgarHelp));
            b.setPositiveButton(LocaleController.getString(R.string.OK), null);
            fragment.showDialog(b.create());
            return;
        }
        run(fragment, prompt, refPaths == null ? new ArrayList<>() : new ArrayList<>(refPaths), reply);
    }

    private static boolean isPhoto(MessageObject m) {
        return m != null && (m.isPhoto() || m.isSticker() && !m.isAnimatedSticker() && !m.isVideoSticker());
    }

    private static void run(ChatActivity fragment, String prompt, ArrayList<String> refs, MessageObject reply) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        boolean[] cancelled = {false};
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        progress.setOnCancelListener(d -> cancelled[0] = true);
        progress.show();
        int account = fragment.getCurrentAccount();
        Utilities.globalQueue.postRunnable(() -> {
            Result result;
            try {
                ArrayList<String> allRefs = new ArrayList<>(refs);
                if (isPhoto(reply)) {
                    File f = loadBlocking(account, reply);
                    if (f != null) allRefs.add(0, f.getAbsolutePath());
                }
                result = generate(prompt, allRefs);
            } catch (Exception e) {
                FileLog.e(e);
                result = Result.error(LocaleController.getString(R.string.MoggoUgarNetwork));
            }
            final Result r = result;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (cancelled[0] || fragment.getParentActivity() == null) return;
                if (r.file == null) {
                    AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
                    b.setTitle(LocaleController.getString(R.string.MoggoUgarTitle));
                    b.setMessage(r.message);
                    b.setPositiveButton(LocaleController.getString(R.string.OK), null);
                    fragment.showDialog(b.create());
                } else {
                    showPreview(fragment, r.file, prompt, refs, reply);
                }
            });
        });
    }

    private static void showPreview(ChatActivity fragment, File file, String prompt, ArrayList<String> refs, MessageObject reply) {
        Activity activity = fragment.getParentActivity();
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        ImageView image = new ImageView(activity);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(bitmap);
        layout.addView(image, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));
        if (!TextUtils.isEmpty(prompt)) {
            TextView caption = new TextView(activity);
            caption.setText(prompt);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            caption.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            caption.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), 0);
            layout.addView(caption);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.MoggoUgarTitle));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.Send), (d, w) -> fragment.moggoSendGenerated(file.getAbsolutePath(), reply));
        b.setNeutralButton(LocaleController.getString(R.string.MoggoUgarAgain), (d, w) -> run(fragment, prompt, refs, reply));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(b.create());
    }

    private static final class Result {
        File file;
        String message;

        static Result error(String message) {
            Result r = new Result();
            r.message = message;
            return r;
        }
    }

    private static Result generate(String prompt, ArrayList<String> refs) throws Exception {
        JSONArray parts = new JSONArray();
        String text = TextUtils.isEmpty(prompt) ? "Improve this image" : prompt;
        parts.put(new JSONObject().put("text", text));
        for (String path : refs) {
            String b64 = encodeReference(path);
            if (b64 == null) continue;
            parts.put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "image/jpeg").put("data", b64)));
        }
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", parts)))
                .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("IMAGE").put("TEXT")));

        String base = MoggoPushGateway.base();
        if (base == null) return Result.error(LocaleController.getString(R.string.MoggoUgarNetwork));
        HttpURLConnection c = (HttpURLConnection) new URL(base + "gemini/" + model()).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(180000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Gemini-Key", key());
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String response = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) return Result.error(explainError(code, response));
            JSONObject json = new JSONObject(response);
            JSONArray candidates = json.optJSONArray("candidates");
            StringBuilder said = new StringBuilder();
            if (candidates != null && candidates.length() > 0) {
                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                JSONArray outParts = content == null ? null : content.optJSONArray("parts");
                if (outParts != null) {
                    for (int i = 0; i < outParts.length(); i++) {
                        JSONObject p = outParts.getJSONObject(i);
                        JSONObject data = p.optJSONObject("inlineData");
                        if (data == null) data = p.optJSONObject("inline_data");
                        if (data != null && data.has("data")) {
                            byte[] bytes = Base64.decode(data.getString("data"), Base64.DEFAULT);
                            String mime = data.optString("mimeType", data.optString("mime_type", "image/png"));
                            File file = new File(AndroidUtilities.getCacheDir(), "ugar_" + System.currentTimeMillis() + (mime.contains("jpeg") ? ".jpg" : ".png"));
                            try (FileOutputStream out = new FileOutputStream(file)) {
                                out.write(bytes);
                            }
                            Result r = new Result();
                            r.file = file;
                            return r;
                        } else if (p.has("text")) {
                            said.append(p.getString("text")).append('\n');
                        }
                    }
                }
                String finish = candidates.getJSONObject(0).optString("finishReason", "");
                if (said.length() == 0 && !finish.isEmpty()) said.append(finish);
            }
            JSONObject feedback = json.optJSONObject("promptFeedback");
            if (said.length() == 0 && feedback != null) said.append(feedback.optString("blockReason"));
            return Result.error(LocaleController.formatString(R.string.MoggoUgarNoImage, said.toString().trim()));
        } finally {
            c.disconnect();
        }
    }

    private static String explainError(int code, String response) {
        String message = "";
        try {
            message = new JSONObject(response).getJSONObject("error").optString("message", "");
        } catch (Exception ignore) {
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("api key not valid") || lower.contains("api_key_invalid") || code == 401) {
            return LocaleController.getString(R.string.MoggoUgarBadKey);
        }
        if (code == 429 && lower.contains("free_tier") && lower.contains("limit: 0")) {
            return LocaleController.getString(R.string.MoggoUgarNeedBilling);
        }
        if (code == 429) return LocaleController.getString(R.string.MoggoUgarQuota);
        if (code == 404) return LocaleController.formatString(R.string.MoggoUgarNoModel, model());
        return "Gemini " + code + ": " + (message.isEmpty() ? response : message);
    }

    private static String encodeReference(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int side = Math.max(bounds.outWidth, bounds.outHeight);
            if (side <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (side / (opts.inSampleSize * 2) >= MAX_REF_SIDE) opts.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
            if (bitmap == null) return null;
            int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (max > MAX_REF_SIDE) {
                float k = MAX_REF_SIDE / (float) max;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * k), Math.round(bitmap.getHeight() * k), true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            bitmap.recycle();
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static File loadBlocking(int account, MessageObject mo) throws InterruptedException {
        FileLoader loader = FileLoader.getInstance(account);
        File f = loader.getPathToMessage(mo.messageOwner);
        if (f != null && f.exists()) return f;
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
            TLRPC.Document document = mo.getDocument();
            if (document != null) {
                loader.loadFile(document, mo, FileLoader.PRIORITY_HIGH, 0);
            } else {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(mo.photoThumbs, AndroidUtilities.getPhotoSize());
                if (size != null) {
                    loader.loadFile(ImageLocation.getForObject(size, mo.photoThumbsObject), mo, "jpg", FileLoader.PRIORITY_HIGH, 0);
                } else {
                    latch.countDown();
                }
            }
        });
        latch.await(60, TimeUnit.SECONDS);
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.removeObserver(waiter, NotificationCenter.fileLoaded);
            center.removeObserver(waiter, NotificationCenter.fileLoadFailed);
        });
        f = loader.getPathToMessage(mo.messageOwner);
        return f != null && f.exists() ? f : null;
    }

    // ---------------------------------------------------------------- settings

    public static void showSettings(Activity activity) {
        if (activity == null) return;
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);

        TextView about = new TextView(activity);
        about.setText(LocaleController.getString(R.string.MoggoUgarSettingsAbout));
        about.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        about.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        layout.addView(about);

        EditTextBoldCursor keyField = new EditTextBoldCursor(activity);
        keyField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        keyField.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        keyField.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        keyField.setHint(LocaleController.getString(R.string.MoggoUgarKeyHint));
        keyField.setSingleLine(true);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyField.setText(key());
        keyField.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        layout.addView(keyField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 12, 0, 0));

        TextView modelView = new TextView(activity);
        modelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        modelView.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));
        modelView.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(6));
        String[] selectedModel = {model()};
        modelView.setText(LocaleController.formatString(R.string.MoggoUgarModel, selectedModel[0]));
        modelView.setOnClickListener(v -> pickModel(activity, keyField.getText().toString().trim(), m -> {
            selectedModel[0] = m;
            modelView.setText(LocaleController.formatString(R.string.MoggoUgarModel, m));
        }));
        layout.addView(modelView);

        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.MoggoUgarSettings));
        b.setView(layout);
        b.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            NaConfig.INSTANCE.getMoggoGeminiKey().setConfigString(keyField.getText().toString().trim());
            NaConfig.INSTANCE.getMoggoGeminiModel().setConfigString(selectedModel[0]);
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        b.show();
    }

    /** Image models available to this key (via the gateway), or a built-in list if that fails. */
    private static void pickModel(Activity activity, String key, Utilities.Callback<String> onPicked) {
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<String> models = new ArrayList<>();
            if (!key.isEmpty() && MoggoPushGateway.base() != null) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(MoggoPushGateway.base() + "gemini-models").openConnection();
                    c.setRequestProperty("X-Gemini-Key", key);
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    if (c.getResponseCode() == 200) {
                        JSONArray list = new JSONObject(read(c.getInputStream())).optJSONArray("models");
                        for (int i = 0; list != null && i < list.length(); i++) {
                            String name = list.getJSONObject(i).optString("name").replace("models/", "");
                            if (name.startsWith("gemini") && name.contains("image")) models.add(name);
                        }
                    }
                    c.disconnect();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (models.isEmpty()) java.util.Collections.addAll(models, FALLBACK_MODELS);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                CharSequence[] items = models.toArray(new CharSequence[0]);
                AlertDialog.Builder b = new AlertDialog.Builder(activity);
                b.setTitle(LocaleController.getString(R.string.MoggoUgarPickModel));
                b.setItems(items, (d, which) -> onPicked.run(models.get(which)));
                b.show();
            });
        });
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
