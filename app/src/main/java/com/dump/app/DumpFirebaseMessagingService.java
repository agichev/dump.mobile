package com.dump.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class DumpFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "dump_notifications";
    private static final String PREF_NAME = "dump_fcm";
    private static final String KEY_TOKEN = "fcm_token";

    @Override
    public void onNewToken(String token) {
        saveToken(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
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

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Dump")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent);

        NotificationManagerCompat.from(this).notify((int) System.currentTimeMillis(), builder.build());
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

    private void saveToken(String token) {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply();
    }

    public static String getSavedToken(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null);
    }
}
