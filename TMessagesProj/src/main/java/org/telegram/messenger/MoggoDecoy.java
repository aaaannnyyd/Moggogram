package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Moggogram decoy passcode: unlocking with it opens the same account with the selected chats
 * hidden from chat lists, search and notifications until the app is unlocked with the real code.
 * The code itself is stored by Nagram's PasscodeHelper under {@link #PASSCODE_SLOT}.
 */
public final class MoggoDecoy {

    public static final int PASSCODE_SLOT = Integer.MAX_VALUE - 1;

    private static volatile boolean active;
    private static HashSet<Long> hidden;

    private MoggoDecoy() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("moggo_decoy", Context.MODE_PRIVATE);
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        if (active == value) return;
        active = value;
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload);
                }
            }
        });
    }

    public static synchronized Set<Long> hiddenDialogs() {
        if (hidden == null) {
            hidden = new HashSet<>();
            for (String id : prefs().getStringSet("hidden", new HashSet<>())) {
                try {
                    hidden.add(Long.parseLong(id));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return hidden;
    }

    public static synchronized void setHiddenDialogs(Set<Long> ids) {
        hidden = new HashSet<>(ids);
        HashSet<String> stored = new HashSet<>();
        for (long id : ids) stored.add(String.valueOf(id));
        prefs().edit().putStringSet("hidden", stored).apply();
    }

    public static boolean isHidden(long dialogId) {
        return active && hiddenDialogs().contains(dialogId);
    }

    public static ArrayList<TLRPC.Dialog> filter(ArrayList<TLRPC.Dialog> dialogs) {
        if (!active || dialogs == null || dialogs.isEmpty() || hiddenDialogs().isEmpty()) return dialogs;
        ArrayList<TLRPC.Dialog> result = new ArrayList<>(dialogs.size());
        for (TLRPC.Dialog dialog : dialogs) {
            if (dialog == null || !hiddenDialogs().contains(dialog.id)) result.add(dialog);
        }
        return result;
    }

    public static void filterMessages(ArrayList<MessageObject> messages) {
        if (!active || messages == null || hiddenDialogs().isEmpty()) return;
        messages.removeIf(m -> m != null && hiddenDialogs().contains(m.getDialogId()));
    }

    /** Search results: users, chats and dialogs; names stay aligned with results. */
    public static void filterSearch(ArrayList<Object> results, ArrayList<CharSequence> names) {
        if (!active || results == null || hiddenDialogs().isEmpty()) return;
        for (int i = results.size() - 1; i >= 0; i--) {
            Object o = results.get(i);
            long id = 0;
            if (o instanceof TLRPC.User) id = ((TLRPC.User) o).id;
            else if (o instanceof TLRPC.Chat) id = -((TLRPC.Chat) o).id;
            else if (o instanceof TLRPC.Dialog) id = ((TLRPC.Dialog) o).id;
            if (id != 0 && hiddenDialogs().contains(id)) {
                results.remove(i);
                if (names != null && i < names.size()) names.remove(i);
            }
        }
    }
}
