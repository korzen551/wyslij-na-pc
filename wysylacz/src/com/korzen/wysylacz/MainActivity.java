package com.korzen.wysylacz;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Wybierasz pliki, apka wysyla je na komputer w tej samej sieci Wi-Fi. */
public class MainActivity extends Activity {
    private EditText host;
    private TextView log;
    private TextView status;
    private ProgressBar bar;
    private SharedPreferences prefs;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ustawienia", 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        TextView label = new TextView(this);
        label.setText("Adres komputera w Tailscale (wpisujesz raz):");
        root.addView(label);

        host = new EditText(this);
        host.setSingleLine(true);
        host.setHint("np. 100.101.102.103");
        host.setText(prefs.getString("host", ""));
        root.addView(host);

        Button pick = new Button(this);
        pick.setText("Wybierz pliki i wyślij");
        pick.setTextSize(20);
        pick.setPadding(p, p, p, p);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, "Wybierz pliki"), 1);
        });
        root.addView(pick);

        status = new TextView(this);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, p, 0, p / 2);
        status.setText("Gotowe do wysyłania");
        root.addView(status);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar);

        log = new TextView(this);
        log.setPadding(0, p, 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll);

        setContentView(root);
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    /** Pliki przyslane przez "Udostepnij" z galerii albo innej apki. */
    private void handleShare(Intent in) {
        if (in == null || in.getAction() == null) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        if (!uris.isEmpty()) send(uris);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 1 || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) send(uris);
    }

    private void send(final List<Uri> uris) {
        if (busy) {
            Toast.makeText(this, "Poczekaj, trwa wysyłanie", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        String typed = host.getText().toString().trim().replaceFirst("^https?://", "");
        if (typed.isEmpty()) {
            busy = false;
            status.setText("Najpierw wpisz adres komputera z Tailscale");
            return;
        }
        prefs.edit().putString("host", typed).apply();
        final String h = typed.contains(":") ? typed : typed + ":8765";
        new Thread(() -> {
            int ok = 0;
            for (int i = 0; i < uris.size(); i++) {
                String name = "plik";
                try {
                    long[] size = {-1};
                    name = describe(uris.get(i), size);
                    upload(h, uris.get(i), name, size[0], (i + 1) + "/" + uris.size());
                    ok++;
                    say("✔ " + name + " (" + mb(size[0]) + ")");
                } catch (Exception e) {
                    say("✘ " + name + ": " + e.getMessage());
                }
            }
            final String done = "Wysłano " + ok + " z " + uris.size();
            runOnUiThread(() -> status.setText(done));
            busy = false;
        }).start();
    }

    private String describe(Uri uri, long[] size) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size[0] = c.getLong(s);
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "plik" : name;
    }

    private void upload(String h, Uri uri, String name, long size, String which) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + h + "/upload").openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("X-Filename", URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
            c.setRequestProperty("Content-Type", "application/octet-stream");
            if (size >= 0) c.setFixedLengthStreamingMode(size);
            else c.setChunkedStreamingMode(1 << 18);

            byte[] buf = new byte[1 << 18];
            long sent = 0, lastUi = 0;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = c.getOutputStream()) {
                if (in == null) throw new Exception("nie mogę otworzyć pliku");
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sent += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 200) {
                        lastUi = now;
                        final long fs = sent;
                        runOnUiThread(() -> {
                            status.setText(which + "  " + name + "\n" + mb(fs) + (size > 0 ? " / " + mb(size) : ""));
                            if (size > 0) bar.setProgress((int) (fs * 1000 / size));
                        });
                    }
                }
            }
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("komputer odpowiedział " + code);
            runOnUiThread(() -> bar.setProgress(1000));
        } finally {
            c.disconnect();
        }
    }

    private static String mb(long bytes) {
        if (bytes < 0) return "?";
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void say(final String line) {
        runOnUiThread(() -> log.setText(line + "\n" + log.getText()));
    }
}
