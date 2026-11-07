package com.router.autoreboot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class Scheduler {
    public static final String ACTION_ALARM = "com.router.autoreboot.ACTION_TRIGGER_REBOOT";
    private static final int REQ = 4401;

    private Scheduler() {}

    public static long calcNext(AppPrefs p) {
        long now = System.currentTimeMillis();
        long interval = p.getIntervalMinutes() * 60000L;
        boolean backup = AppPrefs.ROLE_BACKUP.equals(p.getRole());
        long grace = backup ? p.getGraceMinutes() * 60000L : 0L;
        long base = backup ? Math.max(p.getLastReboot(), p.getLastObserved()) : p.getLastReboot();
        if (base > 0) {
            long t = base + interval + grace;
            if (t > now + 30000L) return t;
        }
        return now + interval + grace;
    }

    public static void schedule(Context c, AppPrefs p) {
        if (!p.isEnabled()) {
            cancel(c);
            return;
        }
        scheduleAt(c, p, calcNext(p));
    }

    public static void scheduleAt(Context c, AppPrefs p, long t) {
        p.setNextTime(t);
        setAlarm(c, p, t);
    }

    /** Re-arms the alarm after boot / service restart. */
    public static void restore(Context c, AppPrefs p) {
        long n = p.getNext();
        if (p.isEnabled() && n > System.currentTimeMillis() + 5000L) {
            setAlarm(c, p, n);
        } else {
            schedule(c, p);
        }
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c));
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_ALARM);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ, i, flags);
    }

    private static void setAlarm(Context c, AppPrefs p, long t) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                p.addLog("Exact-alarm permission is OFF: using inexact alarm (can be a few minutes late). Tap 'Allow exact alarm'.");
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, t, pi);
            }
        } catch (SecurityException e) {
            p.addLog("Exact alarm denied (" + e.getMessage() + "), using inexact alarm");
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, t, pi);
            }
        }
    }
}
