package com.router.autoreboot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppPrefs {
    public static final String ROLE_PRIMARY = "PRIMARY";
    public static final String ROLE_BACKUP = "BACKUP";
    public static final String METHOD_ARCHER = "ARCHER_WEB";
    public static final String METHOD_CUSTOM = "CUSTOM_HTTP";

    private static final int MAX_LOGS = 50;
    private static final Object LOCK = new Object();
    private final SharedPreferences sp;

    public AppPrefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("router_auto_reboot_prefs", Context.MODE_PRIVATE);
    }

    public boolean isEnabled() { return sp.getBoolean("enabled", false); }
    public void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    public String getRole() { return sp.getString("role", ROLE_PRIMARY); }
    public void setRole(String v) { sp.edit().putString("role", v).apply(); }

    public int getIntervalMinutes() { return sp.getInt("interval_min", 180); }
    public void setIntervalMinutes(int v) { sp.edit().putInt("interval_min", v).apply(); }

    public int getGraceMinutes() { return sp.getInt("grace_min", 10); }

    public String getRouterIp() { return sp.getString("router_ip", ""); }
    public void setRouterIp(String v) { sp.edit().putString("router_ip", v).apply(); }

    public String getPassword() { return sp.getString("router_password", ""); }
    public void setPassword(String v) { sp.edit().putString("router_password", v).apply(); }

    public String getMethod() { return sp.getString("method", METHOD_ARCHER); }
    public void setMethod(String v) { sp.edit().putString("method", v).apply(); }

    public String getCustomUrl() { return sp.getString("custom_url", ""); }
    public void setCustomUrl(String v) { sp.edit().putString("custom_url", v).apply(); }
    public String getCustomMethod() { return sp.getString("custom_method", "GET"); }
    public void setCustomMethod(String v) { sp.edit().putString("custom_method", v).apply(); }
    public String getCustomBody() { return sp.getString("custom_body", ""); }
    public void setCustomBody(String v) { sp.edit().putString("custom_body", v).apply(); }

    public long getLastReboot() { return sp.getLong("last_reboot", 0L); }
    public void setLastReboot(long v) { sp.edit().putLong("last_reboot", v).apply(); }

    public long getLastObserved() { return sp.getLong("last_observed", 0L); }
    public void setLastObserved(long v) { sp.edit().putLong("last_observed", v).apply(); }

    public long getNext() { return sp.getLong("next_time", 0L); }
    public void setNextTime(long v) { sp.edit().putLong("next_time", v).apply(); }

    public String getLastStatus() { return sp.getString("last_status", "Never"); }
    public void setLastStatus(String v) { sp.edit().putString("last_status", v).apply(); }

    public void addLog(String msg) {
        synchronized (LOCK) {
            JSONArray arr;
            try {
                arr = new JSONArray(sp.getString("logs", "[]"));
            } catch (Exception e) {
                arr = new JSONArray();
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + msg;
            arr.put(line);
            JSONArray out = new JSONArray();
            int start = Math.max(0, arr.length() - MAX_LOGS);
            for (int i = start; i < arr.length(); i++) out.put(arr.optString(i));
            sp.edit().putString("logs", out.toString()).apply();
        }
    }

    /** Newest line first. */
    public String getLogsText() {
        synchronized (LOCK) {
            try {
                JSONArray arr = new JSONArray(sp.getString("logs", "[]"));
                if (arr.length() == 0) return "[No logs yet]";
                StringBuilder sb = new StringBuilder();
                for (int i = arr.length() - 1; i >= 0; i--) {
                    sb.append(arr.optString(i)).append('\n');
                }
                return sb.toString();
            } catch (Exception e) {
                return "[No logs yet]";
            }
        }
    }

    public void clearLogs() {
        synchronized (LOCK) {
            sp.edit().putString("logs", "[]").apply();
        }
    }
}
