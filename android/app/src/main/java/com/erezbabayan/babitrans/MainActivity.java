package com.erezbabayan.babitrans;

import android.app.*; import android.content.*; import android.database.Cursor; import android.graphics.*;
import android.graphics.drawable.GradientDrawable; import android.net.Uri; import android.os.*;
import android.provider.OpenableColumns; import android.text.InputType; import android.view.*; import android.widget.*;
import java.io.*; import java.net.*; import java.util.*; import org.json.JSONObject;

public class MainActivity extends Activity {
    static final int NAVY=0xFF14112B, CREAM=0xFFFFFBEA, YELLOW=0xFFFFC72C, PURPLE=0xFF2A1B7A;
    static final int PICK=1, PICK_MODEL=2;
    static final String MODEL_URL="https://github.com/erezbabayan/BabiTrans/releases/download/model-ivrit-turbo-q5/ivrit-turbo-q5_0.bin";
    static final long MODEL_SIZE=574041195L;
    static long wctx=0; static final Object LOCK=new Object();
    List<Uri> pending; volatile boolean busy; Button dlBtn;
    SharedPreferences prefs; TextView status, transcript; LinearLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        buildUi();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshModelUi();
        handle(getIntent());
    }
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
        TextView title = label("BabiTrans · מתמלל", 28, YELLOW); title.setTypeface(Typeface.DEFAULT_BOLD); root.addView(title);
        root.addView(label("שתפו הודעה קולית מוואטסאפ ל‑BabiTrans, או בחרו קובץ. התמלול רץ על המכשיר — בלי שרת ובלי אינטרנט.", 15, CREAM));
        status = label("", 15, YELLOW); root.addView(status);
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
        dlBtn = btn("הורד מודל תמלול (חד‑פעמי, ~550MB)", YELLOW, NAVY, v -> downloadModel()); root.addView(dlBtn);
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
        Button pm = new Button(this); pm.setText("טען קובץ מודל ‎.bin‎ מהטלפון"); pm.setAllCaps(false); l.addView(pm);
        pm.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_MODEL); });
        l.addView(label("מודל: " + (modelFile().exists() ? "מותקן (" + modelFile().length()/1048576 + "MB)" : "לא מותקן"), 13, Color.GRAY));
        new AlertDialog.Builder(this).setTitle("הגדרות").setView(l).setPositiveButton("שמור", (dd,w) -> {
            prefs.edit().putString("base", base.getText().toString().trim().replaceAll("/+$","")).putString("token", tok.getText().toString().trim()).putBoolean("preferCloud", cloud.isChecked()).apply();
            refreshModelUi(); }).setNegativeButton("ביטול", null).show();
    }
    String nameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) {}
        return "audio.ogg";
    }
    File modelFile() { return new File(getFilesDir(), "ivrit-turbo-q5_0.bin"); }
    boolean modelReady() { File f = modelFile(); return f.exists() && f.length() > 50_000_000L; }
    void refreshModelUi() {
        if (busy) return;
        boolean ok = modelReady();
        dlBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
        status.setText(ok ? "מוכן — תמלול במכשיר (ivrit‑ai turbo)" : "להפעלה ראשונה: הורידו את מודל התמלול פעם אחת (~550MB, מומלץ Wi‑Fi). אחר כך הכול עובד בלי אינטרנט.");
    }
    void transcribe(List<Uri> uris) {
        String base = prefs.getString("base",""), token = prefs.getString("token","");
        boolean cloud = !base.isEmpty() && (prefs.getBoolean("preferCloud", false) || !modelReady());
        if (!cloud && !modelReady()) { pending = uris; status.setText("כדי לתמלל צריך קודם להוריד את המודל (פעם אחת). מתחיל הורדה…"); downloadModel(); return; }
        if (busy) { Toast.makeText(this, "תמלול אחר עדיין רץ", Toast.LENGTH_SHORT).show(); return; }
        busy = true; transcript.setText(""); status.setText(cloud ? "שולח לשרת…" : "מפענח שמע…");
        new Thread(() -> {
            StringBuilder all = new StringBuilder(); long t0 = System.currentTimeMillis();
            for (Uri u : uris) {
                try {
                    String t;
                    if (cloud) t = post(base + "/transcribe", token, u);
                    else {
                        float[] pcm = AudioDecoder.decode(this, u);
                        int secs = pcm.length / 16000;
                        ui("מתמלל במכשיר… (" + secs + " שניות שמע, ייתכן שייקח כמה שניות עד דקות)");
                        synchronized (LOCK) {
                            if (wctx == 0) { ui("טוען מודל…"); wctx = Whisper.init(modelFile().getAbsolutePath()); }
                            if (wctx == 0) throw new IOException("טעינת המודל נכשלה — נסו להוריד אותו שוב");
                            ui("מתמלל במכשיר… (" + secs + " שניות שמע)");
                            t = Whisper.transcribe(wctx, pcm, "he").trim();
                        }
                    }
                    if (all.length()>0) all.append("\n\n"); all.append(t);
                } catch (Throwable e) { all.append("שגיאה: ").append(e.getMessage()).append("\n"); }
            }
            long dt = (System.currentTimeMillis() - t0) / 1000;
            runOnUiThread(() -> { busy = false; status.setText("הסתיים (" + dt + " שנ׳)" + (cloud ? " · שרת" : " · במכשיר")); transcript.setText(all.toString()); });
        }).start();
    }
    void ui(String s) { runOnUiThread(() -> status.setText(s)); }
    void downloadModel() {
        if (busy) return; busy = true; dlBtn.setEnabled(false);
        new Thread(() -> {
            File part = new File(getFilesDir(), "model.part");
            try {
                long have = part.exists() ? part.length() : 0; String url = MODEL_URL;
                HttpURLConnection c = null;
                for (int hop = 0; hop < 6; hop++) {
                    c = (HttpURLConnection) new URL(url).openConnection(); c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(20000); c.setReadTimeout(60000);
                    if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                    int code = c.getResponseCode();
                    if (code / 100 == 3) { url = new URL(new URL(url), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
                    if (code == 200) have = 0; else if (code != 206) throw new IOException("HTTP " + code);
                    break;
                }
                try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(part, have > 0)) {
                    byte[] b = new byte[1 << 16]; int n; long got = have, last = 0;
                    while ((n = in.read(b)) > 0) { o.write(b, 0, n); got += n;
                        if (got - last > 4_000_000) { last = got; long g = got; ui("מוריד מודל… " + (g * 100 / MODEL_SIZE) + "% (" + g/1048576 + "/" + MODEL_SIZE/1048576 + "MB)"); } }
                }
                if (part.length() < MODEL_SIZE) throw new IOException("ההורדה לא הושלמה — לחצו שוב להמשך");
                if (!part.renameTo(modelFile())) throw new IOException("שמירה נכשלה");
                runOnUiThread(() -> { busy = false; dlBtn.setEnabled(true); refreshModelUi(); Toast.makeText(this, "המודל הותקן", Toast.LENGTH_SHORT).show();
                    if (pending != null) { List<Uri> p = pending; pending = null; transcribe(p); } });
            } catch (Exception e) {
                runOnUiThread(() -> { busy = false; dlBtn.setEnabled(true); status.setText("הורדה נכשלה: " + e.getMessage() + " — לחצו שוב להמשך"); });
            }
        }).start();
    }
    void importModel(Uri u) {
        busy = true; status.setText("מעתיק מודל…");
        new Thread(() -> {
            File tmp = new File(getFilesDir(), "model.import");
            try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n);
                synchronized (LOCK) { if (wctx != 0) { Whisper.free(wctx); wctx = 0; } modelFile().delete(); tmp.renameTo(modelFile()); }
                runOnUiThread(() -> { busy = false; refreshModelUi(); });
            } catch (Exception e) { runOnUiThread(() -> { busy = false; status.setText("טעינה נכשלה: " + e.getMessage()); }); }
        }).start();
    }
    String post(String url, String token, Uri u) throws Exception {
        String bnd = "----bt" + System.currentTimeMillis(); String mime = getContentResolver().getType(u);
        if (mime == null) mime = "application/octet-stream";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setDoOutput(true); c.setConnectTimeout(20000); c.setReadTimeout(600000); c.setChunkedStreamingMode(0);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bnd);
        if (!token.isEmpty()) c.setRequestProperty("X-Matamel-Token", token);
        try (OutputStream o = c.getOutputStream(); InputStream in = getContentResolver().openInputStream(u)) {
            o.write(("--"+bnd+"\r\nContent-Disposition: form-data; name=\"audio\"; filename=\""+nameOf(u).replace("\"","")+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes("UTF-8"));
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
