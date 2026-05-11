package com.transkription;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class TranscriptionService extends Service {

    private static final String CHANNEL_ID = "transkription_channel";
    private static final int NOTIFICATION_ID = 42;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Transkription",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Läuft im Hintergrund während Audio transkribiert wird");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Transkription läuft")
            .setContentText("Audio wird verarbeitet — bitte warten...")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
