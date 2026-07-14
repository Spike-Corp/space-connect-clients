package com.limelight.account;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Obtains a reCAPTCHA v3 token for the Space Connect login by loading the
 * server-hosted token page in an off-screen WebView. The page runs on the
 * spacecloud.gg origin so the token matches the site key allowlist, then hands
 * the token back through the {@code SpaceConnectRecaptcha} JavaScript bridge.
 *
 * The token is an empty string when it could not be produced (WebView missing,
 * network error, timeout, or reCAPTCHA disabled server-side). The backend
 * decides whether an empty token is acceptable.
 */
public final class RecaptchaClient {
    private static final String RECAPTCHA_URL = "https://spacecloud.gg/api/launcher/v1/recaptcha";
    private static final String BRIDGE = "SpaceConnectRecaptcha";
    private static final long TIMEOUT_MS = 15_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface TokenCallback {
        void onToken(String token);
    }

    private RecaptchaClient() {
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public static void fetchToken(Context context, String action, TokenCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> fetchToken(context, action, callback));
            return;
        }

        final WebView webView;
        try {
            webView = new WebView(context.getApplicationContext());
        } catch (Throwable creationError) {
            callback.onToken("");
            return;
        }

        final boolean[] delivered = {false};
        final String[] token = {""};
        final Runnable[] timeout = new Runnable[1];

        final Runnable finish = () -> {
            if (delivered[0]) {
                return;
            }
            delivered[0] = true;
            if (timeout[0] != null) {
                MAIN.removeCallbacks(timeout[0]);
            }
            try {
                webView.removeJavascriptInterface(BRIDGE);
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.destroy();
            } catch (Throwable ignored) {
            }
            callback.onToken(token[0]);
        };

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onToken(String value) {
                token[0] = value != null ? value : "";
                MAIN.post(finish);
            }

            @JavascriptInterface
            public void onError(String message) {
                token[0] = "";
                MAIN.post(finish);
            }
        }, BRIDGE);

        timeout[0] = () -> {
            token[0] = "";
            finish.run();
        };
        MAIN.postDelayed(timeout[0], TIMEOUT_MS);

        webView.loadUrl(RECAPTCHA_URL + "?action=" + Uri.encode(action));
    }
}
