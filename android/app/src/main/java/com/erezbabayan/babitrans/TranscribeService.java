package com.erezbabayan.babitrans;

import android.app.*; import android.content.*; import android.content.pm.ServiceInfo; import android.net.Uri; import android.os.*;
import java.io.IOException; import java.util.*; import java.util.concurrent.*; import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Foreground service that owns all transcription jobs, so they keep running while the user is in other apps
 * (an Activity-owned thread gets frozen/killed by Android once the app is in the background — the 0.3.2 "stuck" bug).
 * Up to MAX_PAR jobs run at once; each gets its own whisper_state over ONE shared model (no second 450MB load).
 */
public class TranscribeService extends Service {
    static final String CH_RUN = "running", CH_DONE = "done"; static final int FG_ID = 100;
    static final class Job {
        final int id; final Uri uri; final String name; final boolean cloud;
        volatile String state = "ממתין בתור"; volatile int audioSecs = -1; volatile long startMs, endMs, etaMs, sttMs; volatile boolean done, failed, cancelled, inNative; volatile String text = ""; volatile Thread worker;
        Job(int id, Uri u, String n, boolean c) { this.id = id; uri = u; name = n; cloud = c; }
        /** Seconds left (estimated), or -1 if unknown. */
        long remaining() { if (done || etaMs <= 0) return -1; return Math.max(0, (etaMs - System.currentTimeMillis() + 999) / 1000); }
        long elapsed() { return startMs == 0 ? 0 : ((done ? endMs : System.currentTimeMillis()) - startMs) / 1000; }
    }
    static final List<Job> JOBS = new CopyOnWriteArrayList<>();
    static volatile Runnable listener; // MainActivity refresh hook (called on any thread)
    static int nextId = 1;
    // Engine: one model ctx, shared by concurrent jobs (read lock); load/free/wipe take the write lock.
    static long wctx = 0; static String wpath = null;
    static final ReentrantReadWriteLock RW = new ReentrantReadWriteLock();
    static ExecutorService pool; static int maxPar = 2;
    /** Native STT is strictly one-at-a-time: concurrent whisper_full on a shared context (even with separate states) thrashed/hung on device. Decode stays parallel. */
    static final java.util.concurrent.locks.ReentrantLock STT = new java.util.concurrent.locks.ReentrantLock(true);
    static final Set<Integer> active = ConcurrentHashMap.newKeySet();
    PowerManager.WakeLock wl; final Handler h = new Handler(Looper.getMainLooper());

    static void ensureCtx(String path) {
        RW.writeLock().lock();
        try { if (wctx != 0 && path.equals(wpath)) return;
            if (wctx != 0) { Whisper.free(wctx); wctx = 0; wpath = null; }
            wctx = Whisper.init(path); wpath = wctx != 0 ? path : null;
        } finally { RW.writeLock().unlock(); }
    }
    static void freeCtx() { RW.writeLock().lock(); try { if (wctx != 0) { Whisper.free(wctx); wctx = 0; wpath = null; } } finally { RW.writeLock().unlock(); } }
    static boolean anyRunning() { for (Job j : JOBS) if (!j.done) return true; return false; }

    /** Called by the Activity: register jobs and make sure the service is running in the foreground. */
    static void enqueue(Context c, List<Uri> uris, boolean cloud) {
        Intent si = new Intent(c, TranscribeService.class).setAction("enqueue");
        ArrayList<Integer> ids = new ArrayList<>();
        synchronized (JOBS) { for (Uri u : uris) { Job j = new Job(nextId++, u, MainActivity.nameOf(c, u), cloud); JOBS.add(j); ids.add(j.id); } }
        si.putIntegerArrayListExtra("ids", ids);
        // carry the share's read grant over to the service
        ClipData cd = null; for (Uri u : uris) { if (cd == null) cd = ClipData.newRawUri("a", u); else cd.addItem(new ClipData.Item(u)); }
        si.setClipData(cd); si.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(si); else c.startService(si);
        changed();
    }
    /**
     * Cancel ALWAYS takes effect immediately in the UI: the job is marked done/cancelled right away, its worker thread is
     * interrupted (unblocks the STT-lock wait, decode, model-load wait), and native whisper_full is told to abort (works even if
     * whisper hasn't registered the job yet). If native still hasn't returned after HARD_MS the process is restarted —
     * whisper_full cannot be killed from Java, and a stuck call would otherwise hold the STT lock forever.
     */
    static final long HARD_MS = 8000;
    static void cancel(int id) {
        for (Job j : JOBS) if (j.id == id && !j.done) {
            j.cancelled = true; Whisper.cancel(id);
            Thread w = j.worker; if (w != null) w.interrupt();
            markCancelled(j);
            if (j.inNative) hardStopLater(j, HARD_MS);
        }
        changed();
    }
    static void markCancelled(Job j) { j.failed = true; j.text = "בוטל"; j.state = "בוטל"; if (j.endMs == 0) j.endMs = System.currentTimeMillis(); j.done = true; }
    static final Handler BG = new Handler(Looper.getMainLooper());
    static void hardStopLater(Job j, long ms) {
        BG.postDelayed(() -> {
            if (!j.inNative) return; // native returned — normal path
            android.util.Log.e("BabiTrans", "native whisper did not stop for job " + j.id + " — restarting process");
            for (Job o : JOBS) if (!o.done) { o.failed = true; o.text = "הופסק (המנוע אופס) — שלחו שוב"; o.state = "הופסק"; o.done = true; }
            changed();
            BG.postDelayed(() -> { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(0); }, 1500);
        }, ms);
    }
    static void changed() { Runnable l = listener; if (l != null) l.run(); }
    /** Finished rows are dismissed once the user has seen them in the app. */
    static void clearFinished() { for (Job j : JOBS) if (j.done) JOBS.remove(j); }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH_RUN, "תמלול פעיל", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_DONE, "תמלולים", NotificationManager.IMPORTANCE_DEFAULT));
        }
        // 2 concurrent jobs on 6GB+ phones (S23 8GB: model ~450MB + ~2 states), 1 on smaller ones.
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); getSystemService(ActivityManager.class).getMemoryInfo(mi);
        maxPar = mi.totalMem >= 5_500_000_000L ? 2 : 1;
        if (pool == null) pool = Executors.newFixedThreadPool(maxPar);
        wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BabiTrans:stt");
        wl.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent i, int f, int sid) {
        startFg();
        if (!wl.isHeld()) wl.acquire(60 * 60 * 1000L);
        if (i != null && "enqueue".equals(i.getAction())) {
            ArrayList<Integer> ids = i.getIntegerArrayListExtra("ids");
            if (ids != null) for (int id : ids) for (Job j : JOBS) if (j.id == id) pool.submit(() -> run(j));
        }
        if (!anyRunning()) stopIfIdle();
        tick();
        return START_NOT_STICKY;
    }
    void startFg() {
        Notification n = fgNote();
        if (Build.VERSION.SDK_INT >= 29) startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(FG_ID, n);
    }
    Notification.Builder nb(String ch) { return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this); }
    PendingIntent openApp() { return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE); }
    Notification fgNote() {
        StringBuilder sb = new StringBuilder(); int n = 0;
        for (Job j : JOBS) if (!j.done) { n++; if (sb.length() > 0) sb.append('\n'); sb.append(MainActivity.jobLine(j)); }
        String title = n <= 1 ? "BabiTrans · מתמלל ברקע" : "BabiTrans · " + n + " תמלולים ברקע";
        return nb(CH_RUN).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title).setContentText(sb.length() == 0 ? "מסיים…" : sb.toString().split("\n")[0])
            .setStyle(new Notification.BigTextStyle().bigText(sb.toString())).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp()).build();
    }
    void tick() {
        h.removeCallbacksAndMessages(null);
        if (!anyRunning()) return;
        getSystemService(NotificationManager.class).notify(FG_ID, fgNote()); changed();
        h.postDelayed(this::tick, 1000);
    }
    void stopIfIdle() { h.post(() -> { if (anyRunning()) return; h.removeCallbacksAndMessages(null);
        if (wl.isHeld()) wl.release(); stopForeground(true); stopSelf(); changed(); }); }

    void run(Job j) {
        if (j.done || j.cancelled) { if (!j.done) markCancelled(j); changed(); stopIfIdle(); return; }
        j.worker = Thread.currentThread(); Thread.interrupted();
        active.add(j.id); j.startMs = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        try {
            String t;
            if (j.cloud) { j.state = "שולח לשרת"; t = MainActivity.post(this, prefs.getString("base","") + "/transcribe", prefs.getString("token",""), j.uri); }
            else {
                j.state = "מפענח שמע"; changed();
                float[] pcm = AudioDecoder.decode(this, j.uri);
                if (j.cancelled) throw new IOException("בוטל");
                int secs = Math.max(1, pcm.length / 16000); j.audioSecs = secs;
                String mp = MainActivity.modelFile(this).getAbsolutePath();
                if (wctx == 0 || !mp.equals(wpath)) { j.state = "טוען מודל"; changed(); ensureCtx(mp); }
                if (j.cancelled) throw new IOException("בוטל");
                String key = "rtf_" + (mp.contains("q5") ? "hq" : "fast");
                float rtf = prefs.getFloat(key, mp.contains("q5") ? 0.35f : 0.15f); // processing sec per audio sec, learned
                int eff = secs < 28 ? secs : (int) Math.ceil(secs / 30.0) * 30; // >28s runs full 30s windows
                long est = (long) (1000 * (1.5 + eff * rtf));
                // watchdog: 4x estimate, min 60s, so a hung note fails fast instead of spinning for minutes
                int timeout = (int) Math.max(60, est * 4 / 1000);
                j.state = "ממתין למנוע"; changed();
                long t1;
                STT.lockInterruptibly();
                try {
                    RW.readLock().lock();
                    try { if (j.cancelled) throw new IOException("בוטל");
                        if (wctx == 0 || !mp.equals(wpath)) throw new IOException("טעינת המודל נכשלה — נסו להוריד אותו שוב");
                        t1 = System.currentTimeMillis(); j.sttMs = t1; j.etaMs = t1 + est; j.state = "מתמלל"; changed();
                        j.inNative = true;
                        // Java-side watchdog in case native abort_callback is never polled (hung inside a kernel)
                        final int to = timeout; BG.postDelayed(() -> { if (j.inNative && !j.cancelled) { j.cancelled = true; Whisper.cancel(j.id); hardStopLater(j, HARD_MS); } }, to * 1000L + 5000);
                        try { t = Whisper.transcribe(wctx, pcm, "he", 6, j.id, timeout); } finally { j.inNative = false; } }
                    finally { RW.readLock().unlock(); }
                } finally { STT.unlock(); }
                if ("\u0001CANCEL".equals(t)) throw new IOException("בוטל");
                if ("\u0001TIMEOUT".equals(t)) throw new IOException("התמלול נעצר אחרי " + timeout + " שנ׳ (חריגה מזמן) — נסו שוב");
                t = t.trim();
                float got = ((System.currentTimeMillis() - t1) / 1000f - 1.5f) / eff;
                if (got > 0.01f && got < 5f) prefs.edit().putFloat(key, rtf * 0.6f + got * 0.4f).apply();
            }
            j.text = t; j.state = "הסתיים";
            if (j.cancelled) throw new IOException("בוטל");
        } catch (Throwable e) { j.failed = true; j.text = j.cancelled ? "בוטל" : "שגיאה: " + e.getMessage(); j.state = j.cancelled ? "בוטל" : "נכשל"; }
        if (j.endMs == 0) j.endMs = System.currentTimeMillis();
        j.done = true; j.worker = null; active.remove(j.id); Thread.interrupted();
        if (!j.failed) deliver(j, prefs); else if (!j.cancelled) notifyText(j, "BabiTrans · תמלול נכשל", j.text);
        changed(); stopIfIdle();
    }
    void deliver(Job j, SharedPreferences prefs) {
        boolean copy = prefs.getBoolean("autoCopy", true) && !j.text.isEmpty();
        if (copy) h.post(() -> { try { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("transcript", j.text)); } catch (Throwable ignored) {} });
        notifyText(j, "BabiTrans · " + j.name + String.format(Locale.US, " (%d שנ׳)", j.elapsed()) + (copy ? " · הועתק" : ""), j.text);
    }
    void notifyText(Job j, String title, String text) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) return;
            getSystemService(NotificationManager.class).notify(1000 + j.id, nb(CH_DONE).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title)
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(openApp()).setAutoCancel(true).build());
        } catch (Throwable ignored) {}
    }
    @Override public void onDestroy() { h.removeCallbacksAndMessages(null); if (wl != null && wl.isHeld()) wl.release(); super.onDestroy(); }
}
