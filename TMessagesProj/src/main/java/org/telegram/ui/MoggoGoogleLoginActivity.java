package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MoggoGeminiWeb;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;

/** Google sign-in for "@ugar": once the session cookie appears, it is kept for gemini.google.com. */
public class MoggoGoogleLoginActivity extends BaseFragment {

    private static final String LOGIN_URL = "https://accounts.google.com/ServiceLogin?hl=ru&continue=https%3A%2F%2Fgemini.google.com%2Fapp";

    private final Runnable onSignedIn;
    private WebView webView;
    private boolean done;

    public MoggoGoogleLoginActivity(Runnable onSignedIn) {
        this.onSignedIn = onSignedIn;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MoggoUgarGoogleSignIn));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frame = new FrameLayout(context);
        webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Google refuses sign-in in embedded browsers marked "wv"
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", "").replaceAll("Version/[\\d.]+ ", ""));
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                check();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                check();
            }
        });
        frame.addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        webView.loadUrl(LOGIN_URL);
        fragmentView = frame;
        return frame;
    }

    private void check() {
        if (done) return;
        String cookie = CookieManager.getInstance().getCookie("https://gemini.google.com");
        if (cookie == null || !cookie.contains("__Secure-1PSID=")) return;
        if (!MoggoGeminiWeb.saveCookies(cookie)) return;
        done = true;
        CookieManager.getInstance().flush();
        AndroidUtilities.runOnUIThread(() -> {
            finishFragment();
            if (onSignedIn != null) onSignedIn.run();
        }, 500);
    }

    @Override
    public void onFragmentDestroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Exception ignore) {
            }
            webView = null;
        }
        super.onFragmentDestroy();
    }
}
