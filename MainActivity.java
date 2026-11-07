package com.router.autoreboot;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int[] HOURS = {1, 2, 3, 4, 6, 12, 24};

    private AppPrefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private Switch swMaster;
    private Spinner spRole, spInterval, spMethod;
    private EditText etIp, etPass, etUrl, etHttpMethod, etBody;
    private TextView tvStatus, tvCountdown, tvLast, tvWarn, tvLogs;
    private LinearLayout customBox, archerBox;
    private boolean loading = true;
    private String lastLogs = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new AppPrefs(this);
        buildUi();
        loadValues();
        loading = false;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        saveInputs();
        super.onPause();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setPadding(0, dp(12), 0, dp(2));
        return tv;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        return e;
    }

    private Button button(String t, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(t);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        return bt;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(32));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("Router Auto Reboot (\u09B0\u09BE\u0989\u099F\u09BE\u09B0 \u0985\u099F\u09CB \u09B0\u09BF\u09AC\u09C1\u099F)");
        title.setTextSize(20);
        root.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(16);
        tvStatus.setPadding(0, dp(12), 0, 0);
        root.addView(tvStatus);

        tvCountdown = new TextView(this);
        tvCountdown.setTextSize(28);
        root.addView(tvCountdown);

        tvLast = new TextView(this);
        root.addView(tvLast);

        tvWarn = new TextView(this);
        tvWarn.setTextColor(0xFFB00020);
        tvWarn.setPadding(0, dp(8), 0, 0);
        root.addView(tvWarn);

        swMaster = new Switch(this);
        swMaster.setText("Master ON / OFF");
        swMaster.setPadding(0, dp(12), 0, dp(4));
        root.addView(swMaster);

        root.addView(label("Phone role"));
        spRole = spinner(new String[]{
                "Phone 1: Primary (\u09AA\u09CD\u09B0\u09A4\u09BF \u09A8\u09BF\u09B0\u09CD\u09A6\u09BF\u09B7\u09CD\u099F \u09B8\u09AE\u09DF \u09AA\u09B0 \u09AA\u09B0)",
                "Phone 2: Standby Backup (+10 min failover)"});
        root.addView(spRole);

        root.addView(label("Reboot every"));
        String[] iv = new String[HOURS.length];
        for (int i = 0; i < HOURS.length; i++) iv[i] = HOURS[i] + (HOURS[i] == 1 ? " hour" : " hours");
        spInterval = spinner(iv);
        root.addView(spInterval);

        root.addView(label("Router method"));
        spMethod = spinner(new String[]{"TP-Link Archer AX (web login)", "Custom HTTP (GET / POST URL)"});
        root.addView(spMethod);

        root.addView(label("Router IP"));
        LinearLayout ipRow = new LinearLayout(this);
        ipRow.setOrientation(LinearLayout.HORIZONTAL);
        etIp = edit("192.168.0.1");
        ipRow.addView(etIp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ipRow.addView(button("Detect", new View.OnClickListener() {
            @Override public void onClick(View v) {
                etIp.setText(NetUtil.gatewayIp(MainActivity.this));
            }
        }));
        root.addView(ipRow);

        archerBox = new LinearLayout(this);
        archerBox.setOrientation(LinearLayout.VERTICAL);
        archerBox.addView(label("Local Password (\u09B0\u09BE\u0989\u099F\u09BE\u09B0\u09C7\u09B0 \u09B2\u09CB\u0995\u09BE\u09B2 \u09AA\u09BE\u09B8\u0993\u09DF\u09BE\u09B0\u09CD\u09A1)"));
        etPass = edit("router login password");
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        archerBox.addView(etPass);
        root.addView(archerBox);

        customBox = new LinearLayout(this);
        customBox.setOrientation(LinearLayout.VERTICAL);
        customBox.addView(label("Custom URL"));
        etUrl = edit("http://192.168.0.1/...");
        customBox.addView(etUrl);
        customBox.addView(label("HTTP method (GET or POST)"));
        etHttpMethod = edit("GET");
        customBox.addView(etHttpMethod);
        customBox.addView(label("POST body"));
        etBody = edit("key=value");
        customBox.addView(etBody);
        root.addView(customBox);

        root.addView(button("\u26A1 Test Reboot Now", new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveInputs();
                startSvc(RebootService.ACTION_TEST);
                Toast.makeText(MainActivity.this, "Test started. Watch the logs below.", Toast.LENGTH_LONG).show();
            }
        }));
        root.addView(button("Check / Ping Router", new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveInputs();
                bg.execute(new Runnable() {
                    @Override public void run() { pingRouter(); }
                });
            }
        }));
        root.addView(button("Disable battery optimization", new View.OnClickListener() {
            @Override public void onClick(View v) { openBatterySettings(); }
        }));
        if (Build.VERSION.SDK_INT >= 31) {
            root.addView(button("Allow exact alarm", new View.OnClickListener() {
                @Override public void onClick(View v) { openExactAlarmSettings(); }
            }));
        }

        root.addView(label("Activity logs (last 50) - newest first"));
        tvLogs = new TextView(this);
        tvLogs.setTextSize(12);
        tvLogs.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLogs.setTextIsSelectable(true);
        root.addView(tvLogs);

        LinearLayout logRow = new LinearLayout(this);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.addView(button("Copy logs", new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("logs", prefs.getLogsText()));
                Toast.makeText(MainActivity.this, "Logs copied", Toast.LENGTH_SHORT).show();
            }
        }));
        logRow.addView(button("Clear logs", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.clearLogs();
                refresh();
            }
        }));
        root.addView(logRow);

        setContentView(sv);
        wireListeners();
    }

    private void wireListeners() {
        swMaster.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (loading) return;
                saveInputs();
                prefs.setEnabled(checked);
                if (checked) {
                    prefs.addLog("Service enabled");
                    startSvc(RebootService.ACTION_START);
                } else {
                    prefs.addLog("Service disabled by user");
                    Scheduler.cancel(MainActivity.this);
                    stopService(new Intent(MainActivity.this, RebootService.class));
                }
                refresh();
            }
        });

        AdapterView.OnItemSelectedListener changed = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (loading) return;
                saveInputs();
                updateBoxes();
                if (prefs.isEnabled()) Scheduler.schedule(MainActivity.this, prefs);
                refresh();
            }

            @Override public void onNothingSelected(AdapterView<?> p) { }
        };
        spRole.setOnItemSelectedListener(changed);
        spInterval.setOnItemSelectedListener(changed);
        spMethod.setOnItemSelectedListener(changed);
    }

    private void loadValues() {
        swMaster.setChecked(prefs.isEnabled());
        spRole.setSelection(AppPrefs.ROLE_BACKUP.equals(prefs.getRole()) ? 1 : 0);
        int idx = 2;
        for (int i = 0; i < HOURS.length; i++) if (HOURS[i] * 60 == prefs.getIntervalMinutes()) idx = i;
        spInterval.setSelection(idx);
        spMethod.setSelection(AppPrefs.METHOD_CUSTOM.equals(prefs.getMethod()) ? 1 : 0);
        etIp.setText(prefs.getRouterIp());
        etPass.setText(prefs.getPassword());
        etUrl.setText(prefs.getCustomUrl());
        etHttpMethod.setText(prefs.getCustomMethod());
        etBody.setText(prefs.getCustomBody());
        updateBoxes();
    }

    private void updateBoxes() {
        boolean custom = spMethod.getSelectedItemPosition() == 1;
        customBox.setVisibility(custom ? View.VISIBLE : View.GONE);
        archerBox.setVisibility(custom ? View.GONE : View.VISIBLE);
    }

    private void saveInputs() {
        if (loading || etIp == null) return;
        prefs.setRole(spRole.getSelectedItemPosition() == 1 ? AppPrefs.ROLE_BACKUP : AppPrefs.ROLE_PRIMARY);
        prefs.setIntervalMinutes(HOURS[Math.max(0, spInterval.getSelectedItemPosition())] * 60);
        prefs.setMethod(spMethod.getSelectedItemPosition() == 1 ? AppPrefs.METHOD_CUSTOM : AppPrefs.METHOD_ARCHER);
        prefs.setRouterIp(etIp.getText().toString().trim());
        prefs.setPassword(etPass.getText().toString());
        prefs.setCustomUrl(etUrl.getText().toString().trim());
        prefs.setCustomMethod(etHttpMethod.getText().toString().trim());
        prefs.setCustomBody(etBody.getText().toString());
    }

    private void refresh() {
        boolean on = prefs.isEnabled();
        tvStatus.setText(on ? "Running (24/7 Active)" : "Inactive (Stopped)");
        long next = prefs.getNext();
        if (on && next > 0) {
            long s = Math.max(0, (next - System.currentTimeMillis()) / 1000);
            tvCountdown.setText(String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60));
        } else {
            tvCountdown.setText("--:--:--");
        }
        long last = prefs.getLastReboot();
        tvLast.setText("Last: " + prefs.getLastStatus() + (last > 0
                ? "  (" + new SimpleDateFormat("MMM d, HH:mm", Locale.US).format(new Date(last)) + ")" : ""));

        StringBuilder warn = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                warn.append("Exact alarm is OFF: reboot can be a few minutes late. Tap 'Allow exact alarm'.\n");
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                warn.append("Battery optimization is ON: Android may kill the app. Tap 'Disable battery optimization'.");
            }
        }
        tvWarn.setText(warn.toString().trim());
        tvWarn.setVisibility(warn.length() == 0 ? View.GONE : View.VISIBLE);

        String logs = prefs.getLogsText();
        if (!logs.equals(lastLogs)) {
            lastLogs = logs;
            tvLogs.setText(logs);
        }
    }

    // ---------------------------------------------------------------- actions

    private void startSvc(String action) {
        Intent i = new Intent(this, RebootService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            prefs.addLog("Failed to start service: " + e);
            Toast.makeText(this, "Failed to start service: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void pingRouter() {
        String ip = prefs.getRouterIp();
        if (ip == null || ip.isEmpty()) ip = NetUtil.gatewayIp(this);
        boolean wifi = NetUtil.bindWifi(this);
        if (!wifi) {
            prefs.addLog("Ping: no Wi-Fi network (connect to the router's Wi-Fi)");
            return;
        }
        boolean up = NetUtil.portOpen(ip, 80, 3000);
        prefs.addLog("Ping: router " + ip + (up ? " is REACHABLE" : " is NOT reachable"));
    }

    private void openBatterySettings() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "Open Settings > Apps > Router Auto Reboot > Battery", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openExactAlarmSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings > Apps > Router Auto Reboot > Alarms & reminders", Toast.LENGTH_LONG).show();
        }
    }
}
