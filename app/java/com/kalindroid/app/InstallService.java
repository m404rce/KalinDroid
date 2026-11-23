package com.kalindroid.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.concurrent.atomic.AtomicBoolean;

public final class InstallService extends Service {
    public static final String ACTION_EXTRACT = "com.kalindroid.app.EXTRACT";
    public static final String ACTION_STOP = "com.kalindroid.app.STOP";
    public static final String EXTRA_MODE = "mode";

    private static final String CHANNEL = "kalindroid.install";
    private static final int NOTIFICATION_ID = 41;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                getString(R.string.install_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.install_channel_desc));
        channel.setShowBadge(false);
        if (notificationManager != null) notificationManager.createNotificationChannel(channel);
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        String mode = intent.getStringExtra(EXTRA_MODE);
        startForeground(NOTIFICATION_ID, buildNotification(0, "Preparando la instalación...", true));

        if (!RootfsManager.validMode(mode)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return START_NOT_STICKY;
        }

        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KalinDroid:install");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(3L * 60L * 60L * 1000L);
        }

        final RootfsManager manager = new RootfsManager(this);
        if (manager.extractMarker().isFile() && !manager.installed()) {
            // The previous process died while extracting. Start clean from the archive.
            RootfsManager.deleteRecursive(manager.rootfsDir());
            RootfsManager.deleteRecursive(manager.extractMarker());
        }

        final String selectedMode = mode;
        new Thread(() -> {
            try {
                manager.extractSync(selectedMode, (percent, message) -> {
                    if (notificationManager != null) {
                        notificationManager.notify(NOTIFICATION_ID,
                                buildNotification(percent, message, false));
                    }
                });
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = "No se pudo instalar Kali";
                }
                manager.markExtractionFailed(message);
            } finally {
                RUNNING.set(false);
                releaseWakeLock();
                stopForeground(true);
                stopSelf();
            }
        }, "kalindroid-install").start();

        return START_NOT_STICKY;
    }

    private Notification buildNotification(int percent, String text, boolean indeterminate) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("KalinDroid")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, indeterminate);
        return builder.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        RUNNING.set(false);
        releaseWakeLock();
        super.onDestroy();
    }
}
