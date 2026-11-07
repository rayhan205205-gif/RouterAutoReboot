package com.router.autoreboot;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class NetUtil {
    private static volatile Network wifiNet;
    private static ConnectivityManager.NetworkCallback wifiCb;

    private NetUtil() {}

    /**
     * Forces ALL traffic of this app process over the Wi-Fi network, even when Wi-Fi has no
     * internet (e.g. router's WAN is down) and mobile data is on. Without this, Android sends
     * requests to 192.168.0.1 over mobile data and they fail.
     */
    public static synchronized boolean bindWifi(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network existing = wifiNet;
        if (existing != null) {
            NetworkInfo ni = cm.getNetworkInfo(existing);
            if (ni != null && ni.isConnected()) {
                bind(cm, existing);
                return true;
            }
            wifiNet = null;
        }

        if (wifiCb != null) {
            try { cm.unregisterNetworkCallback(wifiCb); } catch (Exception ignored) { }
            wifiCb = null;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        wifiCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                wifiNet = network;
                latch.countDown();
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(wifiNet)) wifiNet = null;
            }
        };
        try {
            cm.requestNetwork(req, wifiCb);
        } catch (Exception e) {
            return false;
        }
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Network n = wifiNet;
        if (n == null) return false;
        bind(cm, n);
        return true;
    }

    @SuppressWarnings("deprecation")
    private static void bind(ConnectivityManager cm, Network n) {
        if (Build.VERSION.SDK_INT >= 23) {
            cm.bindProcessToNetwork(n);
        } else {
            ConnectivityManager.setProcessDefaultNetwork(n);
        }
    }

    @SuppressWarnings("deprecation")
    public static String gatewayIp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm == null ? null : wm.getDhcpInfo();
            if (d != null && d.gateway != 0) {
                int g = d.gateway;
                return String.format(Locale.US, "%d.%d.%d.%d",
                        g & 255, (g >> 8) & 255, (g >> 16) & 255, (g >> 24) & 255);
            }
        } catch (Exception ignored) { }
        return "192.168.0.1";
    }

    public static boolean portOpen(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** Returns true once the router stops answering (= it is rebooting). */
    public static boolean waitRouterDown(String ip, long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        int misses = 0;
        while (System.currentTimeMillis() < end) {
            if (portOpen(ip, 80, 1200)) {
                misses = 0;
            } else if (++misses >= 2) {
                return true;
            }
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
