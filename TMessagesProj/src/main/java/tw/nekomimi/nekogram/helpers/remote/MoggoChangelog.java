package tw.nekomimi.nekogram.helpers.remote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Moggogram: after an update, show "what's new" from this version's release post in the releases
 * channel ("#release moggogram <versionName> (<code>) ..."; lines after the title line).
 */
public class MoggoChangelog extends BaseRemoteHelper {

    private static final long RELEASES_CHANNEL_ID = 0L;
    private static final String RELEASES_CHANNEL_NAME = "releases_channel";
    private static final String KEY_SEEN = "changelog_seen_code";

    private static boolean requested;
    private WeakReference<Activity> activityRef;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("moggo_changelog", Context.MODE_PRIVATE);
    }

    public static void showIfUpdated(Activity activity) {
        if (requested || activity == null) return;
        if (prefs().getInt(KEY_SEEN, 0) >= BuildConfig.VERSION_CODE) return;
        requested = true;
        MoggoChangelog helper = new MoggoChangelog();
        helper.activityRef = new WeakReference<>(activity);
        helper.load(null);
    }

    @Override
    protected long getChannelId() {
        return RELEASES_CHANNEL_ID;
    }

    @Override
    protected String getChannelName() {
        return RELEASES_CHANNEL_NAME;
    }

    @Override
    protected String getTag() {
        return "moggoChangelog";
    }

    @Override
    protected String getSearchQuery() {
        return "#release moggogram"; // full-text search splits "12.10.6-12"; the version is matched in parseMessage
    }

    @Override
    protected JSONObject parseMessage(TLRPC.Message message) throws JSONException {
        String prefix = "#release moggogram " + BuildConfig.VERSION_NAME + " (";
        if (message.message == null || !message.message.startsWith(prefix)) return null;
        String[] lines = message.message.split("\n");
        StringBuilder text = new StringBuilder();
        for (int i = 2; i < lines.length; i++) { // skip the tag line and the title line
            if (text.length() > 0) text.append('\n');
            text.append(lines[i]);
        }
        JSONObject json = new JSONObject();
        json.put("text", text.toString().trim());
        return json;
    }

    @Override
    protected void onError(String text, Delegate delegate) {
        requested = false;
    }

    @Override
    protected void onLoadSuccess(ArrayList<JSONObject> responses, Delegate delegate) {
        String text = responses.isEmpty() ? null : responses.get(0).optString("text", null);
        AndroidUtilities.runOnUIThread(() -> {
            requested = false;
            if (text == null) return; // post not found yet: try again next start
            prefs().edit().putInt(KEY_SEEN, BuildConfig.VERSION_CODE).apply();
            if (text.isEmpty()) return;
            Activity activity = activityRef == null ? null : activityRef.get();
            if (activity == null || activity.isFinishing()) return;
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.formatString(R.string.MoggoChangelogTitle, BuildConfig.VERSION_NAME));
            builder.setMessage(text);
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            try {
                builder.show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }
}
