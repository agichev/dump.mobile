package com.dump.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.CookieManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

public class DumpFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "dump_notifications";
    private static final String PREF_NAME = "dump_fcm";
    private static final String KEY_TOKEN = "fcm_token";
    private static volatile MainActivity activeActivity;

    public static void setActiveActivity(MainActivity a) {
        activeActivity = a;
    }

    @Override
    public void onNewToken(String token) {
        saveToken(this, token);
        registerOnServerRetry(token);
        MainActivity a = activeActivity;
        if (a != null && a.webView != null) {
            a.webView.post(() -> a.webView.evaluateJavascript(
                "window.__fcmToken='" + token.replace("\\", "\\\\").replace("'", "\\'") + "';" +
                "fcmRegistered=false;if(typeof fcmRetry==='function')fcmRetry();", null));
        }
    }

    public static void registerOnServerRetry(String token) {
        new Thread(() -> {
            for (int i = 0; i < 300; i++) {
                try {
                    String cookies = CookieManager.getInstance().getCookie("https://dump.press");
                    if (cookies == null || !cookies.contains("session")) {
                        Thread.sleep(2000);
                        continue;
                    }
                    URL url = new URL("https://dump.press/index.php?api=register_fcm_token_native");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    c.setRequestProperty("Cookie", cookies);
                    c.setDoOutput(true);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    String body = "token=" + URLEncoder.encode(token, "UTF-8");
                    try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes()); }
                    int code = c.getResponseCode();
                    c.disconnect();
                    if (code == 200) return;
                } catch (Exception ignored) {}
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }).start();
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        if (MainActivity.isForeground) return;
        Map<String, String> data = message.getData();
        if (data.isEmpty()) return;

        String type = data.get("type");
        String fromUserId = data.get("from_user_id");
        String fromUsername = data.get("from_username");
        String postId = data.get("post_id");
        String postSlug = data.get("post_slug");
        String body = data.get("body");

        if (body == null || body.isEmpty()) {
            body = switch (type != null ? type : "") {
                case "like" -> (fromUsername != null ? fromUsername : "Кто-то") + " поставил(а) лайк на ваш пост";
                case "comment" -> (fromUsername != null ? fromUsername : "Кто-то") + " написал(а) комментарий к вашему посту";
                case "follow" -> (fromUsername != null ? fromUsername : "Кто-то") + " подписался(-ась) на вас";
                case "new_post" -> (fromUsername != null ? fromUsername : "Кто-то") + " опубликовал(а) новый пост";
                case "login" -> "Выполнен вход в ваш аккаунт";
                default -> "Новое уведомление";
            };
        }

        createNotificationChannel();
        showNotification(body, type, fromUserId, postId, postSlug);
    }

    private void showNotification(String body, String type, String fromUserId, String postId, String postSlug) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("nav_type", type != null ? type : "");
        intent.putExtra("nav_from_user_id", fromUserId != null ? fromUserId : "");
        intent.putExtra("nav_post_id", postId != null ? postId : "");
        intent.putExtra("nav_post_slug", postSlug != null ? postSlug : "");

        int requestCode = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.notification))
            .setContentTitle("Dump")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent);

        NotificationManagerCompat.from(this).notify(requestCode, builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Уведомления Dump",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Уведомления о лайках, комментариях и подписках");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    public static void saveToken(Context ctx, String token) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply();
    }

    public static String getSavedToken(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null);
    }
}
