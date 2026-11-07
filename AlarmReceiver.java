package com.router.autoreboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        AppPrefs p = new AppPrefs(app);
        if (!p.isEnabled()) {
            Scheduler.cancel(app);
            return;
        }
        // Keep the chain alive even if the service cannot be started right now.
        Scheduler.scheduleAt(app, p, System.currentTimeMillis() + 5 * 60 * 1000L);

        Intent s = new Intent(app, RebootService.class).setAction(RebootService.ACTION_ALARM);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(s);
            } else {
                app.startService(s);
            }
        } catch (Throwable t) {
            p.addLog("Could not start service from alarm: " + t);
        }
    }
}
