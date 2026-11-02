package io.freemytv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * A headline opened when the TV has no browser (many Android TVs don't): the article in a WebView of our own. Back
 * closes it and returns to the home screen. The address comes in {@link #EXTRA_URL}; only http(s) is accepted.
 */
public class StoryActivity extends Activity {
    static final String EXTRA_URL = "io.freemytv.STORY_URL";

    private WebView web;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 11, 30));
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(115);
        web.setBackgroundColor(Color.rgb(7, 11, 30));
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        web.loadUrl(url);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
