package com.termux.x11;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * Service latar depan supaya desktop Debian tidak dimatikan Android saat aplikasi ada di latar belakang.
 * Notifikasinya punya tombol "Hentikan".
 */
public class DesktopService extends Service {
    static final String ACTION_STOP = "com.termux.x11.DESKTOP_STOP";
    private static final String CHANNEL_ID = "desktop_debian";
    private static final int NOTIFICATION_ID = 7331;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            DesktopManager.stopProcesses();
            DesktopManager.log("[app] desktop dihentikan dari notifikasi");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null)
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Desktop Debian", NotificationManager.IMPORTANCE_LOW));
        }

        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, DesktopService.class).setAction(ACTION_STOP), piFlags);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_x11_icon)
                .setContentTitle("Desktop Debian berjalan")
                .setContentText("Ketuk untuk membuka layar desktop")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, "Hentikan", stop)
                .build();
        startForeground(NOTIFICATION_ID, n);

        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termux-x11:desktop");
                wakeLock.acquire();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld())
            wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }
}
