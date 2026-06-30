package com.example.fileupload;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "smb_prefs";

    /** GitHub Releases API for this repo; the CI workflow publishes the APK here. */
    private static final String UPDATE_API =
            "https://api.github.com/repos/AmsteinGraphics/apk-fileupload/releases/latest";

    private EditText hostField, shareField, pathField, userField, passField;
    private TextView selectedLabel, statusView;
    private ProgressBar progressBar;

    private Uri selectedUri;
    private String selectedName;
    private long selectedSize = -1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    selectedUri = uri;
                    readFileInfo(uri);
                    selectedLabel.setText("Selected: " + selectedName
                            + (selectedSize >= 0 ? "  (" + humanSize(selectedSize) + ")" : ""));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hostField = findViewById(R.id.host);
        shareField = findViewById(R.id.share);
        pathField = findViewById(R.id.path);
        userField = findViewById(R.id.user);
        passField = findViewById(R.id.pass);
        selectedLabel = findViewById(R.id.selected);
        statusView = findViewById(R.id.status);
        progressBar = findViewById(R.id.progress);

        loadPrefs();

        Button pick = findViewById(R.id.pick);
        pick.setOnClickListener(v -> picker.launch(new String[]{"*/*"}));

        Button upload = findViewById(R.id.upload);
        upload.setOnClickListener(v -> startUpload());

        Button update = findViewById(R.id.update);
        update.setOnClickListener(v -> checkForUpdate());

        ((TextView) findViewById(R.id.version)).setText(
                "Installed: " + BuildConfig.VERSION_NAME
                        + " (build " + BuildConfig.VERSION_CODE + ")");
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostField.setText(p.getString("host", ""));
        shareField.setText(p.getString("share", ""));
        pathField.setText(p.getString("path", ""));
        userField.setText(p.getString("user", ""));
        passField.setText(p.getString("pass", ""));
    }

    private void savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("host", hostField.getText().toString().trim())
                .putString("share", shareField.getText().toString().trim())
                .putString("path", pathField.getText().toString().trim())
                .putString("user", userField.getText().toString().trim())
                .putString("pass", passField.getText().toString())
                .apply();
    }

    private void readFileInfo(Uri uri) {
        selectedName = "upload.bin";
        selectedSize = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = c.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0 && !c.isNull(nameIdx)) selectedName = c.getString(nameIdx);
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) selectedSize = c.getLong(sizeIdx);
            }
        } catch (Exception ignored) {
        }
    }

    private void startUpload() {
        if (selectedUri == null) {
            status("Pick a file first.");
            return;
        }
        final String host = hostField.getText().toString().trim();
        final String share = shareField.getText().toString().trim();
        final String path = pathField.getText().toString().trim()
                .replace('/', '\\').replaceAll("^\\\\+|\\\\+$", "");
        final String userRaw = userField.getText().toString().trim();
        final String pass = passField.getText().toString();

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(share)) {
            status("Server and share name are required.");
            return;
        }

        String domain = "";
        String user = userRaw;
        if (userRaw.contains("\\")) {
            int i = userRaw.indexOf('\\');
            domain = userRaw.substring(0, i);
            user = userRaw.substring(i + 1);
        }
        final String fDomain = domain;
        final String fUser = user;

        savePrefs();
        setBusy(true);
        status("Connecting to " + host + " …");

        executor.submit(() -> {
            try {
                doUpload(host, share, path, fUser, pass, fDomain);
                runOnUiThread(() -> {
                    setProgressPct(100);
                    status("✓ Upload complete: " + selectedName);
                    setBusy(false);
                });
            } catch (Exception e) {
                final String msg = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
                runOnUiThread(() -> {
                    status("✗ Failed — " + msg);
                    setBusy(false);
                });
            }
        });
    }

    private void doUpload(String host, String shareName, String path,
                          String user, String pass, String domain) throws Exception {
        SmbConfig config = SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .build();

        try (SMBClient client = new SMBClient(config);
             Connection connection = client.connect(host)) {

            AuthenticationContext ac =
                    new AuthenticationContext(user, pass.toCharArray(), domain);
            Session session = connection.authenticate(ac);

            try (DiskShare disk = (DiskShare) session.connectShare(shareName)) {
                ensureFolders(disk, path);

                String remote = (TextUtils.isEmpty(path) ? "" : path + "\\") + selectedName;
                runOnUiThreadStatus("Uploading to \\\\" + host + "\\" + shareName
                        + "\\" + remote.replace('\\', '/') + " …");

                com.hierynomus.smbj.share.File remoteFile = disk.openFile(
                        remote,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        null);

                try (OutputStream out = remoteFile.getOutputStream();
                     InputStream in = getContentResolver().openInputStream(selectedUri)) {
                    if (in == null) throw new Exception("Could not open the selected file.");
                    byte[] buf = new byte[1 << 16];
                    long sent = 0;
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                        sent += n;
                        if (selectedSize > 0) {
                            int pct = (int) (sent * 100 / selectedSize);
                            runOnUiThread(() -> setProgressPct(pct));
                        }
                    }
                } finally {
                    remoteFile.close();
                }
            }
        }
    }

    /** Create each segment of the remote subfolder if it does not exist yet. */
    private void ensureFolders(DiskShare disk, String path) {
        if (TextUtils.isEmpty(path)) return;
        String[] parts = path.split("\\\\");
        StringBuilder cur = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (cur.length() > 0) cur.append('\\');
            cur.append(part);
            String p = cur.toString();
            if (!disk.folderExists(p)) {
                disk.mkdir(p);
            }
        }
    }

    // --- Self-update (OTA) from GitHub Releases ---

    private void checkForUpdate() {
        setBusy(true);
        status("Checking for updates …");
        executor.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(UPDATE_API).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "fileupload-app");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                int code = conn.getResponseCode();
                if (code == 404) throw new Exception("no published release yet");
                if (code != 200) throw new Exception("GitHub returned HTTP " + code);

                String body;
                try (InputStream in = conn.getInputStream()) {
                    body = readAll(in);
                } finally {
                    conn.disconnect();
                }

                JSONObject json = new JSONObject(body);
                final String tag = json.optString("tag_name", "");
                final int remote = parseBuild(tag);

                String apkUrl = null;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name", "").toLowerCase(Locale.US).endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                final String fUrl = apkUrl;

                runOnUiThread(() -> {
                    setBusy(false);
                    if (remote <= BuildConfig.VERSION_CODE) {
                        status("✓ Up to date (build " + BuildConfig.VERSION_CODE + ").");
                    } else if (fUrl == null) {
                        status("Update " + tag + " found, but it has no APK asset.");
                    } else {
                        promptInstall(remote, fUrl);
                    }
                });
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> {
                    setBusy(false);
                    status("✗ Update check failed — " + msg);
                });
            }
        });
    }

    private void promptInstall(int build, String url) {
        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("Build " + build + " is available (you have build "
                        + BuildConfig.VERSION_CODE + ").\n\nDownload and install now?")
                .setPositiveButton("Update", (d, w) -> downloadAndInstall(url))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void downloadAndInstall(String url) {
        setBusy(true);
        status("Downloading update …");
        executor.submit(() -> {
            try {
                File dir = new File(getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("cannot create cache dir");
                File apk = new File(dir, "update.apk");

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "fileupload-app");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("download HTTP " + code);

                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[1 << 16];
                    long got = 0;
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int pct = (int) (got * 100 / total);
                            runOnUiThread(() -> setProgressPct(pct));
                        }
                    }
                } finally {
                    conn.disconnect();
                }

                runOnUiThread(() -> {
                    setBusy(false);
                    status("Launching installer …");
                    launchInstaller(apk);
                });
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> {
                    setBusy(false);
                    status("✗ Download failed — " + msg);
                });
            }
        });
    }

    private void launchInstaller(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            status("✗ Could not open installer — " + e.getMessage());
        }
    }

    /** Pull the integer build number out of a tag like "v123". */
    private static int parseBuild(String tag) {
        String digits = tag.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // --- UI helpers ---

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) progressBar.setProgress(0);
        findViewById(R.id.upload).setEnabled(!busy);
        findViewById(R.id.pick).setEnabled(!busy);
        findViewById(R.id.update).setEnabled(!busy);
    }

    private void setProgressPct(int pct) {
        progressBar.setProgress(Math.max(0, Math.min(100, pct)));
    }

    private void status(String msg) {
        statusView.setText(msg);
    }

    private void runOnUiThreadStatus(String msg) {
        runOnUiThread(() -> status(msg));
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }
}
