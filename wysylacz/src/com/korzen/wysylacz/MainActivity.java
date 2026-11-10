package com.korzen.wysylacz;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
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

    private static final String ACTION_INSTALL = "com.korzen.wysylacz.INSTALL_STATUS";
    private static final String REPO = "korzen551/wyslij-na-pc";

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
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button update = new Button(this);
        update.setText("Sprawdź aktualizację (masz wersję " + myVersion() + ")");
        update.setOnClickListener(v -> checkUpdate());
        root.addView(update);

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
        if (ACTION_INSTALL.equals(in.getAction())) {
            int st = in.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = in.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm);
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                status.setText("Aktualizacja nieudana: "
                        + in.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
            return;
        }
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
                    String which = (i + 1) + "/" + uris.size();
                    if (size[0] < FAST_MIN || !uploadFast(h, uris.get(i), name, size[0], which)) {
                        upload(h, uris.get(i), name, size[0], which);
                    }
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

    private static final long FAST_MIN = 8L << 20;   // mniejsze pliki ida jednym strumieniem
    private static final int PART = 4 << 20;         // rozmiar kawalka
    private static final int STREAMS = 6;            // ile kawalkow naraz

    private HttpURLConnection post(String h, String path, String id, String name, long total) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + h + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("X-Id", id);
        c.setRequestProperty("X-Total", String.valueOf(total));
        c.setRequestProperty("X-Filename", URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
        c.setRequestProperty("Content-Type", "application/octet-stream");
        return c;
    }

    /**
     * Szybka wysylka: plik idzie w kawalkach kilkoma polaczeniami naraz, co na sieci
     * komorkowej daje duzo wiekszy transfer niz jedno polaczenie. Zwraca false, gdy
     * pliku nie da sie czytac od dowolnego miejsca; wtedy leci zwykla wysylka.
     */
    private boolean uploadFast(String h, Uri uri, String name, long size, String which) throws Exception {
        final android.os.ParcelFileDescriptor pfd;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
        } catch (Exception e) {
            return false;
        }
        if (pfd == null) return false;
        final String id = Long.toHexString(System.nanoTime()) + Long.toHexString(new java.util.Random().nextLong() | 1L << 62);
        final java.util.concurrent.atomic.AtomicLong next = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicLong sent = new java.util.concurrent.atomic.AtomicLong(0);
        final Exception[] failed = {null};
        try (java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor())) {
            final java.nio.channels.FileChannel ch = fis.getChannel();
            try {
                ch.read(java.nio.ByteBuffer.allocate(1), 0);  // czy da sie czytac od dowolnego miejsca
            } catch (Exception e) {
                return false;
            }
            Thread[] workers = new Thread[STREAMS];
            for (int w = 0; w < STREAMS; w++) {
                workers[w] = new Thread(() -> {
                    byte[] buf = new byte[PART];
                    while (failed[0] == null) {
                        long off = next.getAndAdd(PART);
                        if (off >= size) return;
                        int len = (int) Math.min(PART, size - off);
                        try {
                            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf, 0, len);
                            long pos = off;
                            while (bb.hasRemaining()) {
                                int n = ch.read(bb, pos);
                                if (n < 0) throw new Exception("plik krótszy niż podano");
                                pos += n;
                            }
                            sendPart(h, id, name, size, off, buf, len, sent);
                        } catch (Exception e) {
                            failed[0] = e;
                        }
                    }
                });
                workers[w].start();
            }
            long start = System.currentTimeMillis();
            boolean alive = true;
            while (alive) {
                alive = false;
                for (Thread t : workers) {
                    t.join(60);
                    alive |= t.isAlive();
                }
                final long fs = Math.min(sent.get(), size);
                final double secs = Math.max(0.2, (System.currentTimeMillis() - start) / 1000.0);
                runOnUiThread(() -> {
                    status.setText(which + "  " + name + "\n" + mb(fs) + " / " + mb(size)
                            + String.format(Locale.US, "   %.1f MB/s", fs / 1048576.0 / secs));
                    bar.setProgress((int) (fs * 1000 / size));
                });
            }
        } finally {
            pfd.close();
        }
        if (failed[0] != null) throw failed[0];
        HttpURLConnection c = post(h, "/done", id, name, size);
        try {
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            if (c.getResponseCode() != 200) throw new Exception("komputer odpowiedział " + c.getResponseCode());
        } finally {
            c.disconnect();
        }
        runOnUiThread(() -> bar.setProgress(1000));
        return true;
    }

    /** Jeden kawalek; przy bledzie sieci probuje do trzech razy. */
    private void sendPart(String h, String id, String name, long total, long off, byte[] buf, int len,
                          java.util.concurrent.atomic.AtomicLong sent) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            long counted = 0;
            HttpURLConnection c = post(h, "/part", id, name, total);
            try {
                c.setRequestProperty("X-Offset", String.valueOf(off));
                c.setFixedLengthStreamingMode(len);
                try (OutputStream out = c.getOutputStream()) {
                    for (int p = 0; p < len; p += 1 << 16) {
                        int n = Math.min(1 << 16, len - p);
                        out.write(buf, p, n);
                        counted += n;
                        sent.addAndGet(n);
                    }
                }
                if (c.getResponseCode() == 200) {
                    // Doczytanie odpowiedzi zostawia polaczenie otwarte dla nastepnego kawalka.
                    try (InputStream in = c.getInputStream()) {
                        while (in.read() >= 0) { /* nic */ }
                    }
                    return;
                }
                last = new Exception("komputer odpowiedział " + c.getResponseCode());
            } catch (Exception e) {
                last = e;
            }
            c.disconnect();
            sent.addAndGet(-counted);  // kawalek pojdzie jeszcze raz
        }
        throw last;
    }

    private String myVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Czy wersja a jest nowsza niz b (np. 1.10 > 1.9). */
    private static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private void setStatus(final String s) {
        runOnUiThread(() -> status.setText(s));
    }

    /** Pyta GitHuba o najnowsze wydanie; jesli jest nowsze, pobiera je i odpala instalator. */
    private void checkUpdate() {
        if (busy) {
            Toast.makeText(this, "Poczekaj, trwa wysyłanie", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        status.setText("Sprawdzam aktualizację...");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                java.io.ByteArrayOutputStream json = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int n;
                try (InputStream in = c.getInputStream()) {
                    while ((n = in.read(buf)) > 0) json.write(buf, 0, n);
                }
                String latest = new org.json.JSONObject(json.toString("UTF-8"))
                        .getString("tag_name").replaceFirst("^v", "");
                if (!newer(latest, myVersion())) {
                    setStatus("Masz najnowszą wersję (" + myVersion() + ")");
                    return;
                }
                setStatus("Pobieram wersję " + latest + "...");
                HttpURLConnection d = (HttpURLConnection) new URL(
                        "https://github.com/" + REPO + "/releases/download/v" + latest + "/wysylacz.apk")
                        .openConnection();
                d.setConnectTimeout(8000);
                d.setReadTimeout(30000);
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                int id = pi.createSession(new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL));
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = d.getInputStream();
                         OutputStream out = s.openWrite("apk", 0, -1)) {
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    Intent cb = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    s.commit(PendingIntent.getActivity(this, 0, cb, flags).getIntentSender());
                }
                setStatus("Potwierdź instalację wersji " + latest);
            } catch (Exception e) {
                setStatus("Nie udało się sprawdzić aktualizacji: " + e.getMessage());
            } finally {
                busy = false;
            }
        }).start();
    }

    private static String mb(long bytes) {
        if (bytes < 0) return "?";
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void say(final String line) {
        runOnUiThread(() -> log.setText(line + "\n" + log.getText()));
    }
}
