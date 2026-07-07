package com.dump.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.firebase.messaging.FirebaseMessaging;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    WebView webView;
    private View skeletonOverlay;
    private View loadingLogo;
    private FrameLayout vpnBlock;
    private FrameLayout errorScreen;
    private TextView errorMsg;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean pageLoaded = false;
    private boolean isRu = false;
    private boolean destroyed = false;
    private ValueCallback<Uri[]> filePathCallback;
    public static volatile boolean isForeground = false;

    private String pendingNavType;
    private String pendingNavFromUserId;
    private String pendingNavPostId;
    private String pendingNavPostSlug;

    private static final String DUMP_URL = "https://dump.press";
    private static final String GEO_API = "https://get.geojs.io/v1/ip/country";
    private static final int MAX_RETRIES = 2;
    private int retryCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        skeletonOverlay = findViewById(R.id.skeletonOverlay);
        loadingLogo = findViewById(R.id.loadingLogo);
        vpnBlock = findViewById(R.id.vpnBlock);
        errorScreen = findViewById(R.id.errorScreen);
        errorMsg = findViewById(R.id.errorMsg);

        findViewById(R.id.retryBtn).setOnClickListener(v -> restart());
        findViewById(R.id.errorRetryBtn).setOnClickListener(v -> restart());

        setupWebView();
        DumpFirebaseMessagingService.setActiveActivity(this);
        requestAndInjectToken();
        handleIntent(getIntent());
        requestNotifPermission();
        startSkeleton();
        checkIp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        DumpFirebaseMessagingService.setActiveActivity(this);
        requestAndInjectToken();
        handlePendingNav();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        DumpFirebaseMessagingService.setActiveActivity(null);
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        pendingNavType = intent.getStringExtra("nav_type");
        pendingNavFromUserId = intent.getStringExtra("nav_from_user_id");
        pendingNavPostId = intent.getStringExtra("nav_post_id");
        pendingNavPostSlug = intent.getStringExtra("nav_post_slug");
        handlePendingNav();
    }

    private void handlePendingNav() {
        if (pendingNavType == null || pendingNavType.isEmpty()) return;
        if (!pageLoaded || webView == null) return;
        webView.post(() -> navigateFromNotification());
    }

    private void navigateFromNotification() {
        if (pendingNavType == null || pendingNavType.isEmpty()) return;
        String js;
        switch (pendingNavType) {
            case "comment":
                if (pendingNavPostSlug != null && !pendingNavPostSlug.isEmpty()) {
                    js = "navigate('/post/" + jsEscape(pendingNavPostSlug) + "');setTimeout(function(){openComments(" + 
                        (pendingNavPostId != null ? pendingNavPostId : "null") + ",'" + jsEscape(pendingNavPostSlug) + "')},300);";
                } else {
                    js = "navigate('/notifications');";
                }
                break;
            case "like":
            case "follow":
                if (pendingNavFromUserId != null && !pendingNavFromUserId.isEmpty()) {
                    js = "navigate('/profile/" + jsEscape(pendingNavFromUserId) + "');";
                } else {
                    js = "navigate('/notifications');";
                }
                break;
            case "new_post":
                if (pendingNavPostSlug != null && !pendingNavPostSlug.isEmpty()) {
                    js = "navigate('/post/" + jsEscape(pendingNavPostSlug) + "');";
                } else if (pendingNavFromUserId != null && !pendingNavFromUserId.isEmpty()) {
                    js = "navigate('/profile/" + jsEscape(pendingNavFromUserId) + "');";
                } else {
                    js = "navigate('/notifications');";
                }
                break;
            case "login":
                js = "navigate('/profile');setTimeout(function(){openSettings();setTimeout(function(){switchSettingsTab('sessions')},100)},300);";
                break;
            case "notifications":
                js = "navigate('/notifications');";
                break;
            default:
                js = "navigate('/notifications');";
        }
        if (webView != null) {
            webView.evaluateJavascript(js, null);
        }
        pendingNavType = null;
        pendingNavFromUserId = null;
        pendingNavPostId = null;
        pendingNavPostSlug = null;
    }

    private String jsEscape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1002);
            }
        }
    }

    private void requestAndInjectToken() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) return;
            String t = task.getResult();
            DumpFirebaseMessagingService.saveToken(this, t);
            DumpFirebaseMessagingService.registerOnServerRetry(t);
            if (webView != null) {
                String escaped = t.replace("\\", "\\\\").replace("'", "\\'");
                webView.evaluateJavascript(
                    "window.__fcmToken='" + escaped + "';if(typeof fcmRetry==='function')fcmRetry();", null);
            }
        });
    }

    private void restart() {
        vpnBlock.setVisibility(View.GONE);
        errorScreen.setVisibility(View.GONE);
        pageLoaded = false;
        isRu = false;
        retryCount = 0;
        webView.stopLoading();
        startSkeleton();
        checkIp();
    }

    private void startSkeleton() {
        skeletonOverlay.setVisibility(View.VISIBLE);
        skeletonOverlay.setAlpha(1f);
        loadingLogo.setScaleX(1f);
        loadingLogo.setScaleY(1f);
        handler.removeCallbacksAndMessages(null);
        startBreathing();
    }

    private void startBreathing() {
        if (destroyed || skeletonOverlay.getVisibility() != View.VISIBLE) return;
        loadingLogo.animate()
            .scaleX(1.12f).scaleY(1.12f)
            .setDuration(1000)
            .withEndAction(() -> {
                if (!destroyed && skeletonOverlay.getVisibility() == View.VISIBLE) {
                    loadingLogo.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(1000)
                        .withEndAction(() -> {
                            if (!destroyed && skeletonOverlay.getVisibility() == View.VISIBLE) {
                                handler.post(this::startBreathing);
                            }
                        });
                }
            });
    }

    private void hideSkeleton() {
        skeletonOverlay.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction(() -> {
                    skeletonOverlay.setVisibility(View.GONE);
                    skeletonOverlay.setAlpha(1f);
                    loadingLogo.setScaleX(1f);
                    loadingLogo.setScaleY(1f);
                }).start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(WebSettings.getDefaultUserAgent(this) + " DumpApp/1.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, false);
        }

        webView.setBackgroundColor(Color.BLACK);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, "Выберите изображение"), 1001);
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!pageLoaded && !isRu) {
                    pageLoaded = true;
                    hideSkeleton();
                }
                String t = DumpFirebaseMessagingService.getSavedToken(MainActivity.this);
                if (t != null) {
                    String escaped = t.replace("\\", "\\\\").replace("'", "\\'");
                    view.evaluateJavascript(
                        "window.__fcmToken='" + escaped + "';if(typeof fcmRetry==='function')fcmRetry();", null);
                    DumpFirebaseMessagingService.registerOnServerRetry(t);
                }
                requestAndInjectToken();
                view.evaluateJavascript(
                    "(function(){var m=document.querySelector('meta[name=viewport]');if(m){var c=m.content;if(c.indexOf('width=390')>=0){m.content=c.replace('width=390','width=device-width')}}if(typeof CSS!=='undefined'&&CSS.supports&&!CSS.supports('height','1dvh')){var s=document.createElement('style');s.textContent='body,.feed-container,.post-card{height:100vh!important}.post-wrapper{height:82vh!important}.profile-container{height:100vh!important}.modal-overlay{height:100vh!important}.modal-content{max-height:90vh!important}';document.head.appendChild(s)}document.body.style.overflow='hidden';requestAnimationFrame(function(){document.body.style.overflow='';window.dispatchEvent(new Event('resize'))})})();",
                    null
                );
                if (pendingNavType != null && !pendingNavType.isEmpty()) {
                    navigateFromNotification();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int code, String desc, String failUrl) {
                if ((failUrl.equals(DUMP_URL) || failUrl.equals(DUMP_URL + "/")) && !pageLoaded && !isRu) {
                    showError("Не удалось загрузить сайт.\nПроверьте интернет и VPN.");
                }
            }
        });
    }

    private boolean isGmsAvailable() {
        try {
            getPackageManager().getPackageInfo("com.google.android.gms", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void loadSite() {
        if (!isGmsAvailable()) {
            if (destroyed) return;
            new AlertDialog.Builder(this)
                .setTitle("Предупреждение")
                .setMessage("На вашем устройстве отсутствуют сервисы Google Play.\n\nPush-уведомления работать не будут. Остальные функции приложения могут быть нестабильны.")
                .setPositiveButton("Продолжить", (d, w) -> webView.loadUrl(DUMP_URL))
                .setNegativeButton("Закрыть", (d, w) -> finish())
                .setCancelable(false)
                .show();
        } else {
            webView.loadUrl(DUMP_URL);
        }
    }

    private void checkIp() {
        executor.execute(() -> {
            try {
                URL url = new URL(GEO_API);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "DumpApp/1.0");

                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                String country = r.readLine();
                r.close();

                handler.post(() -> {
                    if (destroyed) return;
                    if ("RU".equals(country != null ? country.trim() : "")) {
                        isRu = true;
                        webView.stopLoading();
                        skeletonOverlay.setVisibility(View.GONE);
                        vpnBlock.setVisibility(View.VISIBLE);
                    } else {
                        loadSite();
                    }
                });
            } catch (Exception e) {
                if (retryCount < MAX_RETRIES) {
                    retryCount++;
                    handler.postDelayed(this::checkIp, 1500);
                } else {
                    handler.post(() -> {
                        if (!destroyed) {
                            loadSite();
                        }
                    });
                }
            }
        });
    }

    private void showError(String msg) {
        errorMsg.setText(msg);
        skeletonOverlay.setVisibility(View.GONE);
        vpnBlock.setVisibility(View.GONE);
        errorScreen.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1001) {
            if (filePathCallback != null) {
                Uri[] result = (resultCode == Activity.RESULT_OK && data != null)
                        ? new Uri[]{data.getData()} : null;
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
