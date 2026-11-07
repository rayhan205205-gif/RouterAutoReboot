package com.router.autoreboot;

import android.content.Context;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class RouterEngine {
    public static final class Result {
        public final boolean ok;
        public final String msg;

        Result(boolean ok, String msg) {
            this.ok = ok;
            this.msg = msg;
        }
    }

    private RouterEngine() {}

    /** Blocking: call from a background thread. */
    public static Result reboot(Context ctx, AppPrefs p) {
        String ip = p.getRouterIp();
        if (ip == null || ip.trim().isEmpty()) ip = NetUtil.gatewayIp(ctx);
        ip = ip.trim();
        p.addLog("Reboot started: " + ip + " (" + p.getMethod() + ")");

        if (!NetUtil.bindWifi(ctx)) {
            return new Result(false, "No Wi-Fi network: phone must be connected to the router's Wi-Fi");
        }
        if (!NetUtil.portOpen(ip, 80, 3000)) {
            return new Result(false, "Router " + ip + " is not reachable on port 80");
        }

        String err;
        if (AppPrefs.METHOD_CUSTOM.equals(p.getMethod())) {
            err = customHttp(p);
        } else {
            err = new WebAutomation(ctx, p).run(ip, p.getPassword());
        }
        if (err != null) return new Result(false, err);

        p.addLog("Command sent. Waiting to confirm that the router goes offline...");
        if (NetUtil.waitRouterDown(ip, 45000L)) {
            return new Result(true, "Reboot confirmed (router went offline)");
        }
        return new Result(false, "Command sent but the router did NOT go offline, so the reboot is not confirmed");
    }

    /** Returns null when the request was sent, otherwise an error text. */
    private static String customHttp(AppPrefs p) {
        String url = p.getCustomUrl();
        if (url == null || url.trim().isEmpty()) return "Custom URL is empty";
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url.trim()).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            String m = p.getCustomMethod() == null ? "GET" : p.getCustomMethod().trim().toUpperCase(Locale.US);
            c.setRequestMethod("POST".equals(m) ? "POST" : "GET");
            if ("POST".equals(m)) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                byte[] body = p.getCustomBody().getBytes(StandardCharsets.UTF_8);
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.flush();
                os.close();
            }
            int code = c.getResponseCode();
            if (code >= 400) return "Custom HTTP returned " + code;
            return null;
        } catch (IOException e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
            // Routers usually drop the connection while rebooting: treat as "sent".
            if (msg.contains("reset") || msg.contains("unexpected end") || msg.contains("broken pipe")
                    || msg.contains("eof") || msg.contains("timed out")) {
                return null;
            }
            return "Custom HTTP error: " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
