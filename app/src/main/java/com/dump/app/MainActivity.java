package com.dump.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.messaging.FirebaseMessaging;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

public class MainActivity extends AppCompatActivity {

    WebView webView;
    private View skeletonOverlay;
    private View loadingLogo;
    private FrameLayout errorScreen;
    private TextView errorMsg;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pageLoaded = false;
    private boolean destroyed = false;
    private ValueCallback<Uri[]> filePathCallback;
    public static volatile boolean isForeground = false;

    private String pendingNavType;
    private String pendingNavFromUserId;
    private String pendingNavPostId;
    private String pendingNavPostSlug;

    private static final String DUMP_URL = "https://dump.press";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        skeletonOverlay = findViewById(R.id.skeletonOverlay);
        loadingLogo = findViewById(R.id.loadingLogo);
        errorScreen = findViewById(R.id.errorScreen);
        errorMsg = findViewById(R.id.errorMsg);

        findViewById(R.id.errorRetryBtn).setOnClickListener(v -> restart());

        setupWebView();
        DumpFirebaseMessagingService.setActiveActivity(this);
        requestAndInjectToken();
        handleIntent(getIntent());
        startSkeleton();
        loadSite();
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
        errorScreen.setVisibility(View.GONE);
        pageLoaded = false;
        webView.stopLoading();
        startSkeleton();
        loadSite();
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

        webView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (downloadUrl != null && downloadUrl.startsWith("blob:")) {
                handler.post(() -> {
                    webView.evaluateJavascript(
                        "(function(){var m=document.querySelector('meta[name=download-url]');return m?m.getAttribute('content'):''})();",
                        value -> {
                            String url = value != null ? value.replace("\"", "") : "";
                            if (!url.isEmpty()) {
                                downloadAndSaveImage(url, "dump_" + System.currentTimeMillis() + ".jpg");
                            } else {
                                showToast("Скачивание через WebView не поддерживается");
                            }
                        }
                    );
                });
            } else if (downloadUrl != null) {
                downloadAndSaveImage(downloadUrl, "dump_" + System.currentTimeMillis() + ".jpg");
            }
        });

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void requestNotificationPermission() {
                handler.post(() -> requestNotifPermissionIfNeeded());
            }

            @JavascriptInterface
            public void downloadImage(String url, String fileName) {
                handler.post(() -> downloadAndSaveImage(url, fileName));
            }
        }, "Android");

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
                if (!pageLoaded) {
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
                view.evaluateJavascript("window.isAndroidApp=true;", null);
                if (pendingNavType != null && !pendingNavType.isEmpty()) {
                    navigateFromNotification();
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (url.contains("/notifications")) {
                    requestNotifPermissionIfNeeded();
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
                if ((failUrl.equals(DUMP_URL) || failUrl.equals(DUMP_URL + "/")) && !pageLoaded) {
                    showError("Не удалось загрузить сайт.\nПроверьте интернет и VPN.");
                }
            }
        });
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1002);
            }
        }
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

    private void showError(String msg) {
        errorMsg.setText(msg);
        skeletonOverlay.setVisibility(View.GONE);
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

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void downloadAndSaveImage(String urlStr, String fileName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1003);
                showToast("Разрешите доступ к хранилищу и повторите");
                return;
            }
        }

        String finalUrl = urlStr;
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            if (urlStr.startsWith("//")) {
                finalUrl = "https:" + urlStr;
            } else if (urlStr.startsWith("/")) {
                finalUrl = DUMP_URL + urlStr;
            } else {
                String base = webView != null ? webView.getUrl() : DUMP_URL;
                if (base != null && base.contains("/")) {
                    base = base.substring(0, base.lastIndexOf('/') + 1);
                    finalUrl = base + urlStr;
                } else {
                    finalUrl = DUMP_URL + "/" + urlStr;
                }
            }
        }

        String finalUrl0 = finalUrl;
        new Thread(() -> {
            try {
                URL url = new URL(finalUrl0);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);

                String cookies = CookieManager.getInstance().getCookie(finalUrl0);
                if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                conn.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(this) + " DumpApp/1.0");
                conn.setRequestProperty("Referer", DUMP_URL + "/");

                int code = conn.getResponseCode();
                String ct = conn.getContentType();

                if (code != 200 || ct == null || !ct.startsWith("image/")) {
                    conn.disconnect();
                    handler.post(() -> showToast("Ошибка загрузки: " + code));
                    return;
                }

                Bitmap bitmap = BitmapFactory.decodeStream(conn.getInputStream());
                conn.disconnect();

                if (bitmap == null) {
                    handler.post(() -> showToast("Ошибка загрузки изображения"));
                    return;
                }

                handler.post(() -> saveBitmap(bitmap, fileName));
            } catch (Exception e) {
                handler.post(() -> showToast("Ошибка загрузки изображения"));
            }
        }).start();
    }

    private void saveBitmap(Bitmap bitmap, String fileName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Dump");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                    if (out != null) out.close();
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    showToast("Сохранено в Галерею");
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Dump");
                dir.mkdirs();
                File file = new File(dir, fileName);
                FileOutputStream out = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                out.close();
                Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                intent.setData(Uri.fromFile(file));
                sendBroadcast(intent);
                showToast("Сохранено в Галерею");
            }
        } catch (Exception e) {
            showToast("Ошибка сохранения");
        }
    }
}
