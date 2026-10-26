package com.erezbabayan.babitrans;

import android.app.*; import android.content.*; import android.content.pm.ServiceInfo; import android.net.wifi.WifiManager; import android.os.*;
import java.io.*; import java.net.*; import java.util.Locale;

/**
 * Foreground service that downloads the Whisper model (0.3.4). Before this, the download ran on a plain thread owned by
 * MainActivity: leaving the app let Android freeze/kill it ("ההורדה נתקעת"), and re-opening the app reset the busy flag so a
 * second thread could append to the same .part file -> SHA mismatch -> delete -> start over. Now: one static download,
 * wake+wifi lock, timeouts, auto-retry with HTTP Range resume (GitHub's release-assets CDN returns 206), cancel button.
 */
public class DownloadService extends Service {
    static final String CH = "download"; static final int ID = 200;
    static volatile boolean running, cancelled, hq, finished, ok;
    static volatile long got, total, startBytes, startMs; static volatile double bps;
    static volatile String msg = "";
    static volatile Runnable listener;
    static void changed() { Runnable l = listener; if (l != null) l.run(); }

    static void start(Context c, boolean wantHq) {
        if (running) return;
        Intent i = new Intent(c, DownloadService.class).setAction("start").putExtra("hq", wantHq);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    static void cancel(Context c) { cancelled = true; c.startService(new Intent(c, DownloadService.class).setAction("cancel")); }

    static String mb(long b) { return String.format(Locale.US, "%.0f", b / 1048576.0); }
    static String progressLine() {
        int pct = total > 0 ? (int) (got * 100 / total) : 0;
        String s = "מוריד מודל… " + pct + "% · " + mb(got) + "/" + mb(total) + "MB";
        if (bps > 1000) { s += String.format(Locale.US, " · %.1f MB/s", bps / 1048576.0); long left = (long) ((total - got) / bps);
            s += " · נותרו ~" + MainActivity.fmt(left); }
        return s;
    }

    PowerManager.WakeLock wl; WifiManager.WifiLock wifi; final Handler h = new Handler(Looper.getMainLooper()); Thread worker;
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CH, "הורדת מודל", NotificationManager.IMPORTANCE_LOW));
        wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BabiTrans:dl"); wl.setReferenceCounted(false);
        wifi = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BabiTrans:dl"); wifi.setReferenceCounted(false);
    }
    Notification note() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        int pct = total > 0 ? (int) (got * 100 / total) : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, DownloadService.class).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("BabiTrans · הורדת מודל " + pct + "%")
            .setContentText(msg).setProgress(100, pct, total == 0).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "ביטול", cancel).build()).build();
    }
    @Override public int onStartCommand(Intent i, int f, int sid) {
        String a = i == null ? null : i.getAction();
        if ("cancel".equals(a)) { cancelled = true; if (!running) stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        hq = i != null && i.getBooleanExtra("hq", false);
        running = true; cancelled = false; finished = false; ok = false; got = 0; bps = 0;
        total = hq ? MainActivity.HQ_SIZE : MainActivity.FAST_SIZE; msg = "מתחבר לשרת…";
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(ID, note());
        wl.acquire(2 * 60 * 60 * 1000L); wifi.acquire();
        worker = new Thread(this::work, "model-dl"); worker.start(); tick();
        return START_NOT_STICKY;
    }
    void tick() { if (!running) return; getSystemService(NotificationManager.class).notify(ID, note()); changed(); h.postDelayed(this::tick, 1000); }

    void work() {
        final String url0 = hq ? MainActivity.HQ_URL : MainActivity.FAST_URL, sha = hq ? MainActivity.HQ_SHA : MainActivity.FAST_SHA;
        File part = new File(getFilesDir(), hq ? "model.part" : "model-fast.part");
        File dest = new File(getFilesDir(), hq ? "ivrit-turbo-q5_0.bin" : "ivrit-turbo-q4_0.bin");
        String err = null;
        try {
            if (part.length() > total) part.delete();
            int fails = 0;
            while (part.length() < total) {
                if (cancelled) throw new IOException("בוטל");
                try { fetch(url0, part); fails = 0; }
                catch (IOException e) {
                    if (cancelled) throw new IOException("בוטל");
                    if (++fails > 12) throw e;
                    long wait = Math.min(30, 2L << Math.min(fails, 4));
                    for (long s = wait; s > 0 && !cancelled; s--) { msg = "החיבור נקטע (" + e.getMessage() + ") · " + mb(part.length()) + "/" + mb(total) + "MB נשמרו · מנסה שוב בעוד " + s + " שנ׳"; Thread.sleep(1000); }
                }
            }
            msg = "מאמת חתימת SHA‑256 של המודל… (עד דקה)"; bps = 0;
            if (!MainActivity.sha256(part).equals(sha)) { part.delete(); throw new IOException("קובץ המודל לא תקין (SHA‑256) — נמחק, לחצו שוב"); }
            TranscribeService.freeCtx(); dest.delete();
            if (!part.renameTo(dest)) throw new IOException("שמירה נכשלה");
            ok = true; msg = "המודל הותקן";
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("hq", hq).apply();
        } catch (Throwable e) { err = e.getMessage(); msg = cancelled ? "ההורדה בוטלה — " + mb(part.length()) + "MB נשמרו, לחיצה תמשיך מאותה נקודה" : "הורדה נכשלה: " + err + " — לחצו שוב להמשך"; }
        finished = true; running = false;
        h.post(() -> { h.removeCallbacksAndMessages(null);
            if (wl.isHeld()) wl.release(); if (wifi.isHeld()) wifi.release();
            stopForeground(true);
            try { if (!(Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)) {
                Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
                getSystemService(NotificationManager.class).notify(ID + 1, b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(ok ? "BabiTrans · המודל הותקן" : "BabiTrans · ההורדה נעצרה").setContentText(msg).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)).build()); } } catch (Throwable ignored) {}
            changed(); stopSelf(); });
    }

    /** One HTTP attempt: follow redirects manually (keeps Range on every hop), resume from part.length(). */
    void fetch(String url, File part) throws IOException {
        long have = part.length(); HttpURLConnection c = null;
        for (int hop = 0; ; hop++) {
            if (hop > 8) throw new IOException("יותר מדי הפניות");
            c = (HttpURLConnection) new URL(url).openConnection(); c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000); c.setReadTimeout(30000);
            c.setRequestProperty("Accept-Encoding", "identity"); c.setRequestProperty("User-Agent", "BabiTrans-Android");
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code / 100 == 3) { String loc = c.getHeaderField("Location"); c.disconnect(); if (loc == null) throw new IOException("הפניה ללא כתובת"); url = new URL(new URL(url), loc).toString(); continue; }
            if (code == 416) { c.disconnect(); if (have >= total) return; part.delete(); throw new IOException("HTTP 416"); }
            if (code == 200) { have = 0; }               // server ignored Range -> restart file cleanly (no corrupt append)
            else if (code != 206) { c.disconnect(); throw new IOException("HTTP " + code); }
            break;
        }
        startBytes = have; startMs = System.currentTimeMillis(); got = have;
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(part, have > 0)) {
            byte[] b = new byte[1 << 16]; int n; long lastT = startMs, lastB = have;
            while ((n = in.read(b)) > 0) {
                if (cancelled) throw new IOException("בוטל");
                o.write(b, 0, n); got += n;
                long now = System.currentTimeMillis();
                if (now - lastT >= 1000) { double inst = (got - lastB) * 1000.0 / (now - lastT); bps = bps == 0 ? inst : bps * 0.7 + inst * 0.3; lastT = now; lastB = got; msg = progressLine(); }
            }
        } finally { c.disconnect(); }
        if (part.length() < total) throw new IOException("החיבור נסגר באמצע");
    }
    @Override public void onDestroy() { h.removeCallbacksAndMessages(null); if (wl != null && wl.isHeld()) wl.release(); if (wifi != null && wifi.isHeld()) wifi.release(); super.onDestroy(); }
}
