package com.example.phonelab;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public final class PhoneLabActivity extends Activity {
    private static final String ASSET = "file:///android_asset/index.html";
    private WebView screen;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        screen = new WebView(this);
        screen.setBackgroundColor(0xff12101c);
        screen.getSettings().setJavaScriptEnabled(true);
        screen.getSettings().setAllowFileAccess(false);
        screen.getSettings().setAllowContentAccess(false);
        screen.getSettings().setBlockNetworkLoads(true);
        screen.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !ASSET.equals(request.getUrl().toString());
            }
        });
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            final android.graphics.Insets safe = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime()
            );
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        });
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, () ->
            screen.evaluateJavascript("window.labBack ? window.labBack() : false", result -> {
                if (!"true".equals(result)) finish();
            })
        );
        setContentView(screen);
        screen.loadUrl(ASSET);
    }
}
