package com.erezbabayan.babitrans;

import android.app.*; import android.content.*; import android.database.Cursor; import android.graphics.*;
import android.graphics.drawable.GradientDrawable; import android.net.Uri; import android.os.*;
import android.provider.OpenableColumns; import android.text.InputType; import android.view.*; import android.widget.*;
import java.io.*; import java.net.*; import java.util.*; import org.json.JSONObject;

public class MainActivity extends Activity {
    static final int NAVY=0xFF14112B, CREAM=0xFFFFFBEA, YELLOW=0xFFFFC72C, PURPLE=0xFF2A1B7A;
    static final int PICK=1, PICK_MODEL=2;
    // fast = turbo q4_0 (default, ~3-4x faster encoder on ARM thanks to ggml q4_0 repack kernels); hq = turbo q5_0 (v0.2 model)
    static final String FAST_URL="https://github.com/erezbabayan/BabiTrans/releases/download/model-ivrit-turbo-q4/ivrit-turbo-q4_0.bin";
    static final long FAST_SIZE=473992235L;
    static final String FAST_SHA="e049b13550e5d67d44de3e30354710eaebd67569699d6b91ffe3103dbdc77975";
    static final String HQ_SHA="6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572";
    static final String HQ_URL="https://github.com/erezbabayan/BabiTrans/releases/download/model-ivrit-turbo-q5/ivrit-turbo-q5_0.bin";
    static final long HQ_SIZE=574041195L;
    List<Uri> pending; volatile boolean busy; Button dlBtn;
    SharedPreferences prefs; TextView status, transcript; LinearLayout root, jobsBox; int shownJob = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        buildUi();
        refreshModelUi();
        handle(getIntent());
        warmUp();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
    }
    @Override protected void onResume() { super.onResume(); DownloadService.listener = () -> runOnUiThread(this::renderDl); renderDl(); TranscribeService.listener = () -> runOnUiThread(this::renderJobs); renderJobs(); }
    @Override protected void onPause() { super.onPause(); DownloadService.listener = null; TranscribeService.listener = null; TranscribeService.clearFinished(); }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handle(i); }

    void handle(Intent i) {
        if (i == null) return; List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(i.getAction())) { Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM); if (u!=null) uris.add(u); }
        else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) { ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l!=null) uris.addAll(l); }
        if (!uris.isEmpty()) transcribe(uris);
    }

    TextView label(String t, int size, int color) { TextView v = new TextView(this); v.setText(t); v.setTextSize(size); v.setTextColor(color); v.setTextDirection(View.TEXT_DIRECTION_RTL); return v; }
    Button btn(String t, int bg, int fg, View.OnClickListener l) {
        Button x = new Button(this); x.setText(t); x.setTextColor(fg); x.setAllCaps(false); x.setTextSize(17);
        GradientDrawable d = new GradientDrawable(); d.setColor(bg); d.setCornerRadius(40); x.setBackground(d); x.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = 24; x.setLayoutParams(p); return x;
    }
    void buildUi() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(NAVY);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(48,64,48,48);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); sv.addView(root);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.logo_babitrans); logo.setAdjustViewBounds(true);
        GradientDrawable ld = new GradientDrawable(); ld.setColor(0xFFFFF9EB); ld.setCornerRadius(24); logo.setBackground(ld); logo.setPadding(8,8,8,8);
        int lsz = (int) (52 * getResources().getDisplayMetrics().density); // compact header (was full-width)
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(lsz, lsz); lp.bottomMargin = 16; lp.gravity = Gravity.CENTER_HORIZONTAL; logo.setLayoutParams(lp); root.addView(logo);
        TextView title = label("מתמלל הודעות קוליות", 22, YELLOW); title.setTypeface(Typeface.DEFAULT_BOLD); root.addView(title);
        root.addView(label("שתפו הודעה קולית מוואטסאפ ל‑BabiTrans, או בחרו קובץ. התמלול רץ על המכשיר — בלי שרת ובלי אינטרנט.", 15, CREAM));
        status = label("", 15, YELLOW); root.addView(status);
        jobsBox = new LinearLayout(this); jobsBox.setOrientation(LinearLayout.VERTICAL); root.addView(jobsBox);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(40,40,40,40);
        GradientDrawable cd = new GradientDrawable(); cd.setColor(CREAM); cd.setCornerRadius(36); card.setBackground(cd);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1,-2); cp.topMargin = 32; card.setLayoutParams(cp);
        transcript = label("התמלול יופיע כאן", 18, NAVY); transcript.setTextIsSelectable(true); card.addView(transcript); root.addView(card);
        root.addView(btn("העתק", YELLOW, NAVY, v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("transcript", transcript.getText()));
            Toast.makeText(this, "הועתק", Toast.LENGTH_SHORT).show(); }));
        root.addView(btn("בחר קובץ שמע", PURPLE, CREAM, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("audio/*");
            startActivityForResult(i, PICK); }));
        dlBtn = btn("הורד מודל תמלול מהיר (חד‑פעמי, ~450MB)", YELLOW, NAVY, v -> { if (DownloadService.running) downloadModel(); else downloadFast(); }); root.addView(dlBtn);
        root.addView(btn("הגדרות", NAVY, YELLOW, v -> showSettings()));
        setContentView(sv);
    }
    @Override protected void onActivityResult(int rq, int rc, Intent d) {
        if (rq == PICK_MODEL && rc == RESULT_OK && d != null && d.getData() != null) { importModel(d.getData()); return; }
        if (rq == PICK && rc == RESULT_OK && d != null && d.getData() != null) transcribe(Collections.singletonList(d.getData()));
    }
    void showSettings() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(48,24,48,0);
        EditText base = new EditText(this); base.setHint("https://your-server.example"); base.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        base.setText(prefs.getString("base","")); EditText tok = new EditText(this); tok.setHint("X-Matamel-Token (אופציונלי)");
        tok.setText(prefs.getString("token","")); l.addView(label("גיבוי בענן (אופציונלי): כתובת שרת matamel. ריק = תמלול במכשיר בלבד.",14,Color.GRAY)); l.addView(base); l.addView(tok);
        CheckBox cloud = new CheckBox(this); cloud.setText("העדף שרת על פני תמלול במכשיר"); cloud.setChecked(prefs.getBoolean("preferCloud", false)); l.addView(cloud);
        CheckBox hq = new CheckBox(this); hq.setText("הורדות: מודל איכות גבוהה (איטי פי ~3, ~550MB; המהיר תמיד מועדף אם מותקן)"); hq.setChecked(prefs.getBoolean("hq", false)); l.addView(hq);
        CheckBox ac = new CheckBox(this); ac.setText("העתק תמלול אוטומטית ללוח"); ac.setChecked(prefs.getBoolean("autoCopy", true)); l.addView(ac);
        Button pm = new Button(this); pm.setText("טען קובץ מודל ‎.bin‎ מהטלפון"); pm.setAllCaps(false); l.addView(pm);
        pm.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_MODEL); });
        LinearLayout about = new LinearLayout(this); about.setOrientation(LinearLayout.HORIZONTAL); about.setGravity(Gravity.CENTER_VERTICAL); about.setPadding(0,32,0,0);
        ImageView ai = new ImageView(this); ai.setImageResource(R.drawable.logo_bubble); about.addView(ai, new LinearLayout.LayoutParams(120,120));
        String ver = ""; try { ver = getPackageManager().getPackageInfo(getPackageName(),0).versionName; } catch (Exception ignored) {}
        TextView at = label("  BabiTrans v" + ver + " · תמלול עברי במכשיר", 14, Color.GRAY); about.addView(at); l.addView(about);
        LinearLayout links = new LinearLayout(this); links.setOrientation(LinearLayout.HORIZONTAL); links.setPadding(0,16,0,0);
        links.addView(link("פרטיות", () -> info("פרטיות", PRIVACY_HE, PRIVACY_URL)));
        links.addView(link("אבטחה ועדכונים", () -> info("אבטחה ועדכונים", SECURITY_HE, SECURITY_URL)));
        links.addView(link("תנאי שימוש", () -> openUrl(TERMS_URL)));
        l.addView(links);
        Button wipe = new Button(this); wipe.setText("מחק את כל הנתונים מהמכשיר"); wipe.setTextColor(0xFFC62828); wipe.setAllCaps(false); wipe.setOnClickListener(v -> confirmWipe()); l.addView(wipe);
        l.addView(label("מהיר: " + (ok(fastFile()) ? "מותקן" : "לא מותקן") + " · איכות גבוהה: " + (ok(hqFile()) ? "מותקן" : "לא מותקן"), 13, Color.GRAY));
        new AlertDialog.Builder(this).setTitle("הגדרות").setView(l).setPositiveButton("שמור", (dd,w) -> {
            String b0 = base.getText().toString().trim();
            if (!b0.isEmpty() && !(b0.startsWith("https://") || b0.startsWith("http://")) || !cleartextOk(b0)) {
                Toast.makeText(this, "כתובת שרת חייבת להיות https:// (http מותר רק לשרת הביתי 10.0.0.8)", Toast.LENGTH_LONG).show(); return; }
            prefs.edit().putString("base", base.getText().toString().trim().replaceAll("/+$","")).putString("token", tok.getText().toString().trim()).putBoolean("preferCloud", cloud.isChecked()).putBoolean("hq", hq.isChecked()).putBoolean("autoCopy", ac.isChecked()).apply();
            refreshModelUi(); warmUp(); }).setNegativeButton("ביטול", null).show();
    }
    static boolean cleartextOk(String b) {
        if (!b.startsWith("http://")) return true;
        try { String h = new URL(b).getHost();
            return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("10.0.0.8"); // must match res/xml/network_security_config.xml
        } catch (Exception e) { return false; }
    }
    static String sha256(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[1 << 20]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        StringBuilder sb = new StringBuilder(); for (byte x : md.digest()) sb.append(String.format("%02x", x)); return sb.toString();
    }
    TextView link(String t, Runnable r) {
        TextView v = label(t + "   ", 14, 0xFF5B4FC9); v.setPaintFlags(v.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG); v.setOnClickListener(x -> r.run()); return v;
    }
    void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { Toast.makeText(this, "לא ניתן לפתוח את הקישור", Toast.LENGTH_SHORT).show(); }
    }
    void info(String title, String body, String url) {
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("הבנתי", null)
            .setNeutralButton("מסמך מלא", (x, w) -> openUrl(url)).show();
        View m = d.findViewById(android.R.id.message); if (m != null) m.setTextDirection(View.TEXT_DIRECTION_RTL);
    }
    void confirmWipe() {
        new AlertDialog.Builder(this).setTitle("מחיקת נתונים").setMessage("יימחקו מהטלפון: הגדרות, כתובת שרת וטוקן, ומודלי התמלול שהורדו (יהיה צריך להוריד שוב). תמלילים לא נשמרים באפליקציה ממילא. להמשיך?")
            .setPositiveButton("מחק", (x, w) -> new Thread(() -> {
                TranscribeService.freeCtx();
                File[] fs = getFilesDir().listFiles(); if (fs != null) for (File f : fs) f.delete();
                File[] cs = getCacheDir().listFiles(); if (cs != null) for (File f : cs) f.delete();
                prefs.edit().clear().commit();
                runOnUiThread(() -> { transcript.setText("התמלול יופיע כאן"); refreshModelUi(); Toast.makeText(this, "הנתונים נמחקו מהמכשיר", Toast.LENGTH_LONG).show(); });
            }).start()).setNegativeButton("ביטול", null).show();
    }
    static final String DOCS = "https://github.com/erezbabayan/BabiTrans/blob/main/docs/";
    static final String PRIVACY_URL = DOCS + "PRIVACY_HE.md", SECURITY_URL = DOCS + "SECURITY_HE.md", TERMS_URL = DOCS + "TERMS_HE.md";
    static final String PRIVACY_HE =
        "• ברירת המחדל: התמלול רץ בטלפון עצמו (whisper.cpp + מודל ivrit‑ai). השמע והתמליל לא יוצאים מהמכשיר.\n\n" +
        "• אינטרנט נדרש רק להורדה חד‑פעמית של המודל מ‑GitHub Releases (ואופציונלית לשרת שבחרת).\n\n" +
        "• שרת ענן הוא אופציונלי וכבוי כברירת מחדל. אם הזנת כתובת שרת וסימנת \"העדף שרת\" — השמע נשלח לשרת שהגדרת (למשל matamel שלך). אנחנו לא מפעילים שרת ציבורי.\n\n" +
        "• תמלילים לא נשמרים באפליקציה; הם מוצגים, ואם בחרת — מועתקים ללוח ומוצגים בהתראה.\n\n" +
        "• אין פרסומות, אין אנליטיקס, אין דיווחי קריסה לשרת, אין חשבון משתמש. גיבוי ענן של Android כבוי לאפליקציה.\n\n" +
        "• \"מחק את כל הנתונים מהמכשיר\" בהגדרות מוחק הגדרות, טוקן ומודלים.";
    static final String SECURITY_HE =
        "• הורידו את BabiTrans רק מ‑github.com/erezbabayan/BabiTrans/releases. כל גרסה חתומה באותו מפתח — Android יסרב לעדכן מעל APK בחתימה אחרת.\n\n" +
        "• Play Protect עשוי להזהיר על אפליקציה שלא מ‑Google Play. זה צפוי בהתקנה מ‑GitHub; אל תכבו את Play Protect. אם הוא מסמן את האפליקציה כמזיקה — אל תתקינו ודווחו.\n\n" +
        "• אחרי ההתקנה אפשר לבטל שוב את \"התקנה ממקורות לא ידועים\" לדפדפן.\n\n" +
        "• עדכון: פתחו את קישור releases/latest והתקינו מעל הגרסה הקיימת — ההגדרות והמודל נשמרים.\n\n" +
        "• מודל התמלול מאומת ב‑SHA‑256 אחרי ההורדה; קובץ פגום נמחק.\n\n" +
        "• הרשאות: אינטרנט, התראות, ושירות רקע (כדי שהתמלול ימשיך כשעוברים לאפליקציה אחרת). אין מיקרופון, אנשי קשר, מיקום או אחסון.\n\n" +
        "• תעבורה: HTTPS בלבד, למעט השרת הביתי ברשת המקומית (10.0.0.8) אם הגדרת אותו.";
    static String nameOf(Context ctx, Uri u) {
        try (Cursor c = ctx.getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) {}
        return "audio.ogg";
    }
    File fastFile() { return new File(getFilesDir(), "ivrit-turbo-q4_0.bin"); }
    File hqFile() { return new File(getFilesDir(), "ivrit-turbo-q5_0.bin"); }
    /** v0.3.7: the fast q4 model ALWAYS wins when installed (q5 is ~3x slower on S23; old installs had hq=true stuck from the v0.2 q5 download). */
    static File modelFile(Context c) { File f = new File(c.getFilesDir(), "ivrit-turbo-q4_0.bin"), q = new File(c.getFilesDir(), "ivrit-turbo-q5_0.bin");
        return ok(f) || !ok(q) ? f : q; }
    static boolean ok(File f) { return f.exists() && f.length() > 50_000_000L; }
    boolean wantHq() { return prefs.getBoolean("hq", false); }
    /** Preferred model if installed, else whichever is installed (v0.2 users keep working with q5 right away). */
    File modelFile() { return modelFile(this); }
    boolean modelReady() { return ok(modelFile()); }
    boolean preferredMissing() { return !ok(fastFile()); }
    boolean onlySlow() { return !ok(fastFile()) && ok(hqFile()); }
    /** Load the model in the background as soon as the app opens, so a WhatsApp share doesn't pay the cold load. */
    void warmUp() {
        if (!modelReady()) return; final String path = modelFile().getAbsolutePath();
        new Thread(() -> TranscribeService.ensureCtx(path)).start();
    }
    void refreshModelUi() {
        if (busy) return;
        boolean ok = modelReady();
        boolean miss = preferredMissing();
        dlBtn.setVisibility(miss ? View.VISIBLE : View.GONE);
        dlBtn.setText(onlySlow() ? "⚡ הורד מודל מהיר — פי 3 יותר מהר (~450MB, מומלץ Wi‑Fi)" : "הורד מודל תמלול מהיר (חד‑פעמי, ~450MB)");
        String which = modelFile().equals(fastFile()) ? "מהיר" : "איכות גבוהה";
        status.setText(!ok ? "להפעלה ראשונה: הורידו את מודל התמלול פעם אחת (~450MB, מומלץ Wi‑Fi). אחר כך הכול עובד בלי אינטרנט."
            : miss ? "⚠ מותקן רק המודל האיטי (איכות גבוהה) — תמלול איטי פי ~3. הורידו את המודל המהיר בכפתור למטה; הוא ישמש אוטומטית."
            : "מוכן — תמלול במכשיר (ivrit‑ai turbo · " + which + ")");
    }
    void transcribe(List<Uri> uris) {
        String base = prefs.getString("base","");
        boolean cloud = !base.isEmpty() && (prefs.getBoolean("preferCloud", false) || !modelReady());
        if (!cloud && !modelReady()) { pending = uris; status.setText("כדי לתמלל צריך קודם להוריד את המודל (פעם אחת). מתחיל הורדה…"); downloadModel(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            Toast.makeText(this, "אפשרו התראות כדי לקבל את התמלול כשאתם באפליקציה אחרת", Toast.LENGTH_LONG).show();
        if (!cloud && onlySlow() && !DownloadService.running && !prefs.getBoolean("nagQ4Off", false)) {
            new AlertDialog.Builder(this).setTitle("התמלול יהיה איטי")
                .setMessage("מותקן אצלך רק המודל האיטי (איכות גבוהה) — הודעה קצרה יכולה לקחת פי 3 יותר זמן. מומלץ להוריד פעם אחת את המודל המהיר (~450MB, עדיף ב‑Wi‑Fi). התמלול הנוכחי ימשיך במודל האיטי, ומרגע שהמהיר יורד — הוא ישמש אוטומטית.")
                .setPositiveButton("הורד מודל מהיר", (d, w) -> downloadFast())
                .setNegativeButton("לא עכשיו", null)
                .setNeutralButton("אל תשאל שוב", (d, w) -> prefs.edit().putBoolean("nagQ4Off", true).apply()).show();
        }
        TranscribeService.enqueue(this, uris, cloud); // runs in a foreground service — you can leave the app
        Toast.makeText(this, uris.size() > 1 ? "נוספו " + uris.size() + " תמלולים — אפשר לעבור לאפליקציה אחרת" : "התמלול רץ ברקע — אפשר לעבור לאפליקציה אחרת", Toast.LENGTH_SHORT).show();
    }
    static String fmt(long s) { return s >= 60 ? String.format(Locale.US, "%d:%02d", s / 60, s % 60) : s + " שנ׳"; }
    static String jobLine(TranscribeService.Job j) {
        String a = j.audioSecs > 0 ? " · " + fmt(j.audioSecs) + " שמע" : "";
        if (j.done) return (j.failed ? "✗ " : "✓ ") + j.name + " — " + j.state + " (" + fmt(j.elapsed()) + ")";
        if (j.startMs == 0) return "⏳ " + j.name + " — ממתין בתור";
        long r = j.remaining();
        String eta = "";
        if ("מתמלל".equals(j.state)) {
            int p = j.prog, ph = p < 0 ? 0 : (p >>> 24), tok = p < 0 ? 0 : ((p >>> 8) & 0xFFFF), pct = p < 0 ? 0 : (p & 0xFF);
            String live = ph == 2 ? " · מפענח מילים (" + tok + ")" : ph == 1 ? " · מעבד שמע" : "";
            if (pct > 0 && pct < 100) live += " · " + pct + "%";
            if (r > 0) eta = live + " · נותרו ~" + fmt(r);
            else eta = live + " · לוקח יותר מהצפוי" + (j.hqModel ? " (מודל איטי)" : "");
        } else if ("ממתין למנוע".equals(j.state)) eta = " · ממתין לסיום התמלול הקודם";
        return "● " + j.name + " — " + j.state + a + " · עבר " + fmt(j.elapsed()) + eta;
    }
    void renderJobs() {
        jobsBox.removeAllViews(); boolean running = false;
        for (TranscribeService.Job j : TranscribeService.JOBS) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,8,0,8);
            TextView t = label(jobLine(j), 14, j.done ? (j.failed ? 0xFFFF8A80 : CREAM) : YELLOW);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            if (!j.done) { running = true; TextView x = label("  ביטול", 14, 0xFFFF8A80); x.setOnClickListener(v -> TranscribeService.cancel(j.id)); row.addView(x); }
            else if (!j.failed) { row.setOnClickListener(v -> { shownJob = j.id; transcript.setText(j.text); }); }
            jobsBox.addView(row);
            if (j.done && !j.failed && j.id > shownJob) { shownJob = j.id; transcript.setText(j.text); }
        }
        if (!running && !busy) refreshModelUi();
        else if (running) status.setText("ממשיך גם כשהאפליקציה סגורה — התוצאה תגיע בהתראה" + (prefs.getBoolean("autoCopy", true) ? " ותועתק ללוח" : ""));
    }
    void ui(String s) { runOnUiThread(() -> status.setText(s)); }
    void downloadFast() { if (DownloadService.running) return; prefs.edit().putBoolean("hq", false).apply(); downloadModel(); }
    void downloadModel() {
        if (DownloadService.running) { DownloadService.cancel(this); return; } // button doubles as cancel while downloading
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            Toast.makeText(this, "אפשרו התראות כדי לראות את התקדמות ההורדה", Toast.LENGTH_LONG).show();
        DownloadService.start(this, wantHq()); status.setText("מתחבר לשרת…"); renderDl();
        Toast.makeText(this, "ההורדה ממשיכה ברקע — אפשר לצאת מהאפליקציה", Toast.LENGTH_SHORT).show();
    }
    void renderDl() {
        if (DownloadService.running) { busy = true; dlBtn.setEnabled(true); dlBtn.setText("ביטול הורדה"); status.setText(DownloadService.msg); return; }
        if (busy && DownloadService.finished) { busy = false; DownloadService.finished = false; refreshModelUi(); status.setText(DownloadService.msg);
            if (DownloadService.ok) { warmUp(); if (pending != null) { List<Uri> p = pending; pending = null; transcribe(p); } } }
    }
    void importModel(Uri u) {
        busy = true; status.setText("מעתיק מודל…");
        new Thread(() -> {
            File tmp = new File(getFilesDir(), "model.import");
            try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n);
                File dst = tmp.length() < 520_000_000L ? fastFile() : hqFile(); // q4 ~452MB, q5 ~547MB
                TranscribeService.freeCtx(); dst.delete(); tmp.renameTo(dst);
                prefs.edit().putBoolean("hq", dst.equals(hqFile())).apply();
                runOnUiThread(() -> { busy = false; refreshModelUi(); warmUp(); });
            } catch (Exception e) { runOnUiThread(() -> { busy = false; status.setText("טעינה נכשלה: " + e.getMessage()); }); }
        }).start();
    }
    static String post(Context ctx, String url, String token, Uri u) throws Exception {
        String bnd = "----bt" + System.currentTimeMillis(); String mime = ctx.getContentResolver().getType(u);
        if (mime == null) mime = "application/octet-stream";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setDoOutput(true); c.setConnectTimeout(20000); c.setReadTimeout(600000); c.setChunkedStreamingMode(0);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bnd);
        if (!token.isEmpty()) c.setRequestProperty("X-Matamel-Token", token);
        try (OutputStream o = c.getOutputStream(); InputStream in = ctx.getContentResolver().openInputStream(u)) {
            o.write(("--"+bnd+"\r\nContent-Disposition: form-data; name=\"audio\"; filename=\""+nameOf(ctx, u).replace("\"","")+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes("UTF-8"));
            byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            o.write(("\r\n--"+bnd+"--\r\n").getBytes("UTF-8"));
        }
        int code = c.getResponseCode(); InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        if (is != null) while ((n = is.read(b)) > 0) bo.write(b, 0, n);
        String body = bo.toString("UTF-8");
        if (code >= 400) throw new IOException("HTTP " + code + " " + body);
        return new JSONObject(body).optString("text", body);
    }
}
