package com.router.autoreboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        AppPrefs p = new AppPrefs(app);
        if (!p.isEnabled()) return;
        p.addLog("Phone booted / app updated: resuming service");
        Scheduler.restore(app, p);
        Intent s = new Intent(app, RebootService.class).setAction(RebootService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(s);
            } else {
                app.startService(s);
            }
        } catch (Throwable t) {
            p.addLog("Boot auto-start error: " + t);
        }
    }
}
