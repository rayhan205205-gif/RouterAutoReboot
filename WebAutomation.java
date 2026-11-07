package com.router.autoreboot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Drives the TP-Link Archer (AX) web UI inside a hidden WebView:
 * login with the local password -> #/reboot -> click Reboot -> confirm.
 * Every step is written to the in-app log so a failure can be diagnosed.
 */
final class WebAutomation {
    private interface Res { void on(String r); }

    private static final int S_LOAD = 0, S_LOGIN = 1, S_WAIT_LOGIN = 2, S_NAV = 3, S_CLICK = 4, S_CONFIRM = 5;

    private static final String WAIT_JS =
            "(function(){var h=location.hash||'';var inp=document.querySelector('input[type=password]');"
            + "if(!inp&&h.indexOf('login')<0)return 'LOGGED:'+h;return inp?'STILL_LOGIN':'WAIT:'+h;})()";

    private static final String NAV_JS = "location.hash='#/reboot';'NAV'";

    private static final String CLICK_JS =
            "(function(){try{var all=document.querySelectorAll('button,a,span,div,[role=button]');var best=null,bs=0;"
            + "for(var pass=0;pass<2&&!best;pass++){"
            + "for(var i=0;i<all.length;i++){var e=all[i];"
            + "var t=(e.innerText||'').replace(/\\s+/g,' ').trim().toUpperCase();if(t!=='REBOOT')continue;"
            + "var nav=false,p=e;while(p&&p.nodeType===1){var c=(typeof p.className==='string'?p.className:'').toLowerCase();"
            + "if(c.indexOf('menu')>=0||c.indexOf('nav')>=0||c.indexOf('sidebar')>=0){nav=true;break;}p=p.parentElement;}"
            + "if(pass===0&&nav)continue;"
            + "var cl=(typeof e.className==='string'?e.className:'').toLowerCase();"
            + "var s=(e.tagName==='BUTTON'||e.getAttribute('role')==='button'||cl.indexOf('button')>=0)?2:1;"
            + "if(s>=bs){best=e;bs=s;}}}"
            + "if(!best)return 'NO_REBOOT_BUTTON:'+(location.hash||'');best.click();return 'REBOOT_CLICKED';"
            + "}catch(e){return 'ERR:'+e.message;}})()";

    private static final String CONFIRM_JS =
            "(function(){try{var all=document.querySelectorAll('button,a,span,div,[role=button]');var hit=null;"
            + "for(var i=0;i<all.length;i++){var e=all[i];"
            + "var t=(e.innerText||'').replace(/\\s+/g,' ').trim().toUpperCase();"
            + "var ok=(t==='OK'||t==='YES'||t==='CONFIRM');"
            + "if(!ok&&t==='REBOOT'){var p=e;while(p&&p.nodeType===1){var c=(typeof p.className==='string'?p.className:'').toLowerCase();"
            + "if(c.indexOf('modal')>=0||c.indexOf('dialog')>=0||c.indexOf('popup')>=0||c.indexOf('msgbox')>=0){ok=true;break;}p=p.parentElement;}}"
            + "if(ok)hit=e;}"
            + "if(!hit)return 'NO_CONFIRM';hit.click();return 'CONFIRM_CLICKED';"
            + "}catch(e){return 'ERR:'+e.message;}})()";

    private static final String SNAP_JS =
            "(function(){return (location.hash||'')+' | '+((document.body&&document.body.innerText)||'')"
            + ".replace(/\\s+/g,' ').slice(0,250);})()";

    private final Context ctx;
    private final AppPrefs prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final CountDownLatch done = new CountDownLatch(1);
    private final Res ignore = new Res() { @Override public void on(String r) { } };

    private WebView wv;
    private volatile boolean finished = false;
    private volatile String error = null;
    private boolean pageStarted = false;
    private int state = S_LOAD;
    private int tries = 0;
    private int loginAttempts = 0;
    private long deadline;
    private String ip;
    private String password = "";

    private final Runnable ticker = new Runnable() {
        @Override public void run() { tick(); }
    };

    WebAutomation(Context c, AppPrefs p) {
        ctx = c.getApplicationContext();
        prefs = p;
    }

    /** Blocking. Returns null on success, otherwise an error message. Call from a background thread. */
    String run(final String routerIp, final String pw) {
        ip = routerIp;
        password = pw == null ? "" : pw;
        deadline = System.currentTimeMillis() + 75000L;
        h.post(new Runnable() {
            @Override public void run() { start(); }
        });
        try {
            if (!done.await(85, TimeUnit.SECONDS)) {
                h.post(new Runnable() {
                    @Override public void run() { finish("Web automation hard timeout"); }
                });
                return "Web automation hard timeout";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
        return error;
    }

    private void start() {
        try {
            wv = new WebView(ctx);
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            // A detached WebView has a 0x0 viewport; give it a real size so the page lays out.
            wv.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            wv.layout(0, 0, 1280, 900);
            wv.resumeTimers();
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    if (!pageStarted) {
                        pageStarted = true;
                        prefs.addLog("Router page loaded");
                        state = S_LOGIN;
                        h.postDelayed(ticker, 1500);
                    }
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                    if (finished) return;
                    if (state >= S_CLICK) {
                        prefs.addLog("Page dropped after reboot click (expected): " + desc);
                        finish(null);
                    } else if (failingUrl != null && failingUrl.startsWith("http://" + ip + "/webpages/index.html")) {
                        finish("Cannot open router page: " + desc);
                    }
                }
            });
            prefs.addLog("Opening http://" + ip + "/webpages/index.html");
            wv.loadUrl("http://" + ip + "/webpages/index.html#/login");
            h.postDelayed(ticker, 20000); // safety: if page never finishes loading, tick() will time out
        } catch (Exception e) {
            finish("WebView error: " + e.getMessage());
        }
    }

    private void next(long ms) {
        if (!finished) h.postDelayed(ticker, ms);
    }

    private void tick() {
        if (finished) return;
        if (System.currentTimeMillis() > deadline) {
            failWithSnapshot("Timed out in step " + state);
            return;
        }
        if (state == S_LOAD) {
            next(1000);
            return;
        }
        switch (state) {
            case S_LOGIN:
                js(loginJs(password), new Res() {
                    @Override public void on(String r) {
                        if (r.startsWith("CLICKED")) {
                            prefs.addLog("Login submitted");
                            state = S_WAIT_LOGIN;
                            tries = 0;
                            next(1500);
                        } else if (r.startsWith("LOGGED")) {
                            prefs.addLog("Already logged in");
                            state = S_NAV;
                            next(200);
                        } else if (++tries > 25) {
                            failWithSnapshot("Login form not found (" + r + ")");
                        } else {
                            next(600);
                        }
                    }
                });
                break;

            case S_WAIT_LOGIN:
                js(WAIT_JS, new Res() {
                    @Override public void on(String r) {
                        tries++;
                        if (r.startsWith("LOGGED")) {
                            prefs.addLog("Login OK");
                            state = S_NAV;
                            tries = 0;
                            next(300);
                        } else if (r.startsWith("WAIT:") && tries >= 6) {
                            prefs.addLog("Login assumed OK (" + r + ")");
                            state = S_NAV;
                            tries = 0;
                            next(300);
                        } else if (tries >= 10) {
                            loginAttempts++;
                            if (loginAttempts >= 3) {
                                failWithSnapshot("Login failed: wrong password or router login is locked");
                            } else {
                                prefs.addLog("Login not accepted yet, retrying (" + loginAttempts + ")");
                                state = S_LOGIN;
                                tries = 0;
                                next(500);
                            }
                        } else {
                            next(900);
                        }
                    }
                });
                break;

            case S_NAV:
                js(NAV_JS, new Res() {
                    @Override public void on(String r) {
                        prefs.addLog("Opening reboot page");
                        state = S_CLICK;
                        tries = 0;
                        next(2200);
                    }
                });
                break;

            case S_CLICK:
                js(CLICK_JS, new Res() {
                    @Override public void on(String r) {
                        if (r.startsWith("REBOOT_CLICKED")) {
                            prefs.addLog("Reboot button clicked");
                            state = S_CONFIRM;
                            tries = 0;
                            next(900);
                        } else if (++tries > 15) {
                            failWithSnapshot("Reboot button not found (" + r + ")");
                        } else {
                            if (tries % 5 == 0) js(NAV_JS, ignore);
                            next(700);
                        }
                    }
                });
                break;

            case S_CONFIRM:
                js(CONFIRM_JS, new Res() {
                    @Override public void on(String r) {
                        if (r.startsWith("CONFIRM_CLICKED")) {
                            prefs.addLog("Confirm clicked");
                            h.postDelayed(new Runnable() {
                                @Override public void run() { finish(null); }
                            }, 1500);
                        } else if (++tries > 6) {
                            prefs.addLog("No confirm dialog found, continuing");
                            finish(null);
                        } else {
                            next(600);
                        }
                    }
                });
                break;

            default:
                next(500);
        }
    }

    private static String loginJs(String pw) {
        return "(function(pw){try{var h=location.hash||'';var inp=document.querySelector('input[type=password]');"
                + "if(!inp){if(h.indexOf('login')<0&&h.length>1)return 'LOGGED:'+h;return 'WAIT_INPUT:'+h;}"
                + "var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');"
                + "inp.focus();d.set.call(inp,pw);"
                + "inp.dispatchEvent(new Event('input',{bubbles:true}));"
                + "inp.dispatchEvent(new Event('change',{bubbles:true}));"
                + "var c=document.querySelectorAll('button,a,span,div,input[type=button],input[type=submit]');var b=null;"
                + "for(var i=0;i<c.length;i++){var t=((c[i].innerText||c[i].value)||'').replace(/\\s+/g,' ').trim().toUpperCase();"
                + "if(t==='LOG IN'||t==='LOGIN'||t==='SIGN IN'){b=c[i];}}"
                + "if(!b)return 'NO_BUTTON';"
                + "setTimeout(function(){b.click();},300);return 'CLICKED';"
                + "}catch(e){return 'ERR:'+e.message;}})(" + JSONObject.quote(pw) + ")";
    }

    private void js(String code, final Res cb) {
        if (finished || wv == null) return;
        try {
            wv.evaluateJavascript(code, new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    if (!finished) cb.on(clean(v));
                }
            });
        } catch (Exception e) {
            finish("JS error: " + e.getMessage());
        }
    }

    private static String clean(String r) {
        if (r == null || r.equals("null")) return "";
        if (r.length() >= 2 && r.startsWith("\"") && r.endsWith("\"")) {
            r = r.substring(1, r.length() - 1);
        }
        return r.replace("\\u003C", "<").replace("\\\"", "\"").replace("\\n", " ");
    }

    private void failWithSnapshot(final String msg) {
        if (finished) return;
        js(SNAP_JS, new Res() {
            @Override public void on(String r) { finish(msg + " | page: " + r); }
        });
        h.postDelayed(new Runnable() {
            @Override public void run() { finish(msg); }
        }, 3000);
    }

    private void finish(String err) {
        if (finished) return;
        finished = true;
        error = err;
        h.removeCallbacks(ticker);
        try {
            if (wv != null) {
                wv.stopLoading();
                wv.destroy();
                wv = null;
            }
        } catch (Exception ignored) { }
        done.countDown();
    }
}
