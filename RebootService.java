package com.router.autoreboot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RebootService extends Service {
    public static final String ACTION_START = "com.router.autoreboot.ACTION_START";
    public static final String ACTION_ALARM = "com.router.autoreboot.ACTION_RUN_ALARM";
    public static final String ACTION_TEST = "com.router.autoreboot.ACTION_TEST";

    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL = "router_auto_reboot_channel";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService exec;
    private AppPrefs prefs;
    private ConnectivityManager.NetworkCallback watch;
    private volatile long lostAt = 0L;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            updateNotification();
            main.postDelayed(this, 60000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new AppPrefs(this);
        exec = Executors.newSingleThreadExecutor();
        createChannel();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) action = ACTION_START;

        startFg(); // must happen immediately after startForegroundService()

        if (ACTION_ALARM.equals(action)) {
            prefs.addLog("Alarm fired");
            runJob(false);
        } else if (ACTION_TEST.equals(action)) {
            prefs.addLog("Test reboot requested");
            runJob(true);
        } else {
            if (!prefs.isEnabled()) {
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            Scheduler.restore(this, prefs);
            prefs.addLog("Service running. Next reboot at "
                    + new SimpleDateFormat("MMM d, HH:mm", Locale.US).format(new Date(prefs.getNext())));
        }

        if (prefs.isEnabled()) {
            startWatching();
            main.removeCallbacks(refresher);
            main.postDelayed(refresher, 60000L);
        }
        updateNotification();
        return prefs.isEnabled() ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(refresher);
        stopWatching();
        if (exec != null) exec.shutdown();
        super.onDestroy();
    }

    private void runJob(final boolean manual) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RouterAutoReboot:job");
        wl.acquire(4 * 60 * 1000L);
        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    doJob(manual);
                } catch (Throwable t) {
                    prefs.addLog("Job error: " + t);
                    if (!manual && prefs.isEnabled()) {
                        Scheduler.scheduleAt(RebootService.this, prefs, System.currentTimeMillis() + 5 * 60 * 1000L);
                    }
                } finally {
                    if (wl.isHeld()) wl.release();
                    main.post(new Runnable() {
                        @Override public void run() {
                            updateNotification();
                            if (!prefs.isEnabled()) {
                                stopForeground(true);
                                stopSelf();
                            }
                        }
                    });
                }
            }
        });
    }

    private void doJob(boolean manual) {
        long now = System.currentTimeMillis();
        if (!manual && AppPrefs.ROLE_BACKUP.equals(prefs.getRole())) {
            long base = Math.max(prefs.getLastReboot(), prefs.getLastObserved());
            long interval = prefs.getIntervalMinutes() * 60000L;
            if (base > 0 && now - base < interval) {
                prefs.addLog("Backup: router was rebooted " + ((now - base) / 60000L)
                        + " min ago (by the other phone). Skipping duplicate reboot.");
                Scheduler.schedule(this, prefs);
                return;
            }
        }

        RouterEngine.Result r = RouterEngine.reboot(this, prefs);
        if (r.ok) {
            prefs.setLastReboot(System.currentTimeMillis());
            prefs.setLastStatus("Success");
            prefs.addLog("SUCCESS: " + r.msg);
            if (prefs.isEnabled()) Scheduler.schedule(this, prefs);
        } else {
            prefs.setLastStatus("Failed");
            if (manual) {
                prefs.addLog("TEST FAILED: " + r.msg);
            } else {
                prefs.addLog("REBOOT FAILED: " + r.msg + " (retrying in 5 min)");
                Scheduler.scheduleAt(this, prefs, System.currentTimeMillis() + 5 * 60 * 1000L);
            }
        }
    }

    /** Detects "Wi-Fi went away for a while and came back" = the router (or someone) rebooted it. */
    private void startWatching() {
        if (watch != null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        watch = new ConnectivityManager.NetworkCallback() {
            @Override public void onLost(Network network) {
                lostAt = System.currentTimeMillis();
            }

            @Override public void onAvailable(Network network) {
                long l = lostAt;
                if (l <= 0) return;
                lostAt = 0L;
                long gap = System.currentTimeMillis() - l;
                if (gap >= 10000L && gap <= 600000L) {
                    prefs.setLastObserved(l);
                    prefs.addLog("Wi-Fi was down " + (gap / 1000L) + "s: router reboot observed");
                    if (prefs.isEnabled() && AppPrefs.ROLE_BACKUP.equals(prefs.getRole())) {
                        Scheduler.schedule(RebootService.this, prefs);
                    }
                }
            }
        };
        try {
            cm.registerNetworkCallback(req, watch);
        } catch (Exception e) {
            prefs.addLog("Wi-Fi watcher failed: " + e.getMessage());
            watch = null;
        }
    }

    private void stopWatching() {
        if (watch == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(watch);
        } catch (Exception ignored) { }
        watch = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Router Auto Reboot", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void startFg() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            prefs.addLog("startForeground failed: " + e);
        }
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) { }
    }

    private Notification buildNotification() {
        long next = prefs.getNext();
        String nextS = (prefs.isEnabled() && next > 0)
                ? new SimpleDateFormat("HH:mm", Locale.US).format(new Date(next)) : "--:--";
        String role = AppPrefs.ROLE_BACKUP.equals(prefs.getRole()) ? "Backup" : "Primary";
        String text = "Every " + (prefs.getIntervalMinutes() / 60) + "h \u2022 " + role
                + " \u2022 Next " + nextS + " \u2022 Last: " + prefs.getLastStatus();

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Router Auto Reboot")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
