package com.erezbabayan.babitrans;

import android.app.*; import android.content.*; import android.database.Cursor; import android.graphics.*;
import android.graphics.drawable.GradientDrawable; import android.net.Uri; import android.os.*;
import android.provider.OpenableColumns; import android.text.InputType; import android.view.*; import android.widget.*;
import java.io.*; import java.net.*; import java.util.*; import org.json.JSONObject;

public class MainActivity extends Activity {
    static final int NAVY=0xFF14112B, CREAM=0xFFFFFBEA, YELLOW=0xFFFFC72C, PURPLE=0xFF2A1B7A;
    static final int PICK=1;
    SharedPreferences prefs; TextView status, transcript; LinearLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        buildUi();
        if (prefs.getString("base","").isEmpty()) { status.setText("יש להגדיר כתובת שרת תמלול (HTTPS) בהגדרות"); showSettings(); }
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
        root.addView(label("שתפו הודעה קולית מוואטסאפ ל‑BabiTrans, או בחרו קובץ", 15, CREAM));
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
        root.addView(btn("הגדרות", NAVY, YELLOW, v -> showSettings()));
        setContentView(sv);
    }
    @Override protected void onActivityResult(int rq, int rc, Intent d) {
        if (rq == PICK && rc == RESULT_OK && d != null && d.getData() != null) transcribe(Collections.singletonList(d.getData()));
    }
    void showSettings() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(48,24,48,0);
        EditText base = new EditText(this); base.setHint("https://your-server.example"); base.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        base.setText(prefs.getString("base","")); EditText tok = new EditText(this); tok.setHint("X-Matamel-Token (אופציונלי)");
        tok.setText(prefs.getString("token","")); l.addView(label("כתובת שרת (חייב להיות זמין תמיד, HTTPS):",14,Color.GRAY)); l.addView(base); l.addView(tok);
        new AlertDialog.Builder(this).setTitle("הגדרות").setView(l).setPositiveButton("שמור", (dd,w) -> {
            prefs.edit().putString("base", base.getText().toString().trim().replaceAll("/+$","")).putString("token", tok.getText().toString().trim()).apply();
            status.setText(""); }).setNegativeButton("ביטול", null).show();
    }
    String nameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) {}
        return "audio.ogg";
    }
    void transcribe(List<Uri> uris) {
        String base = prefs.getString("base",""), token = prefs.getString("token","");
        if (base.isEmpty()) { status.setText("יש להגדיר כתובת שרת תמלול בהגדרות"); showSettings(); return; }
        status.setText("מתמלל…"); transcript.setText("");
        new Thread(() -> {
            StringBuilder all = new StringBuilder();
            for (Uri u : uris) {
                try { String t = post(base + "/transcribe", token, u); if (all.length()>0) all.append("\n\n"); all.append(t); }
                catch (Exception e) { all.append("שגיאה: ").append(e.getMessage()).append("\n"); }
            }
            runOnUiThread(() -> { status.setText("הסתיים"); transcript.setText(all.toString()); });
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
