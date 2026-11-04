package io.github.shishonomisaki.parentlock;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates from this repo's GitHub releases. The phone page shows when a newer one is out; the parent starts
 * it with the PIN on an unlocked TV, the TV downloads the APK, and Android's own install screen asks to
 * install it (OK on the remote). Android only installs it over this app when the same key signed both; the
 * APK is checked for that, and for this package and a newer version, before Android is even asked.
 *
 * A release carries one asset named parental-lock-<versionCode>.apk (the release workflow makes it).
 */
final class Updater {
    static final String REPO = "shishonomisaki/TVcleanup_with_kidslock";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Pattern ASSET = Pattern.compile("parental-lock-(\\d+)\\.apk");
    // Checked when the phone page asks (opening Update), at most every 10 min: GitHub allows 60 unsigned
    // requests an hour per address, and a release should show up soon after it is published.
    private static final long CHECK_EVERY_MS = 10 * 60_000L;
    private static final int MAX_APK = 30 * 1024 * 1024;

    private static volatile String latestName = "";
    private static volatile long latestCode;
    private static volatile String apkUrl;
    private static volatile long checkedAt;
    private static volatile boolean checking;
    /** "", "downloading", "confirm" (the TV shows Android's install screen), or an error code. */
    static volatile String state = "";

    /** For GET /api/update: this version, the newest release, and where an update stands. */
    static JSONObject info(Context context) throws Exception {
        long now = System.currentTimeMillis();
        if (now - checkedAt > CHECK_EVERY_MS && !checking) {
            checking = true;
            new Thread(Updater::check, "update-check").start();
        }
        PackageInfo me = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        long mine = code(me);
        return new JSONObject()
                .put("version", me.versionName)
                .put("latest", latestName)
                .put("available", latestCode > mine && apkUrl != null)
                .put("state", state);
    }

    private static void check() {
        try {
            JSONObject r = new JSONObject(new String(get(LATEST, 512 * 1024), StandardCharsets.UTF_8));
            JSONArray assets = r.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                Matcher m = ASSET.matcher(a.optString("name"));
                if (m.matches()) {
                    latestCode = Long.parseLong(m.group(1));
                    apkUrl = a.getString("browser_download_url");
                    latestName = r.optString("tag_name").replaceFirst("^v", "");
                }
            }
            checkedAt = System.currentTimeMillis();
        } catch (Exception e) {
            android.util.Log.i("ParentLock", "update check failed: " + e);
            checkedAt = System.currentTimeMillis();   // try again at the next check, 10 min on
        } finally {
            checking = false;
        }
    }

    /** POST /api/update (PIN checked, TV unlocked): download, check, and ask Android to install. */
    static String start(Context context) throws Exception {
        PackageInfo me = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        if (apkUrl == null || latestCode <= code(me)) return "no_update";
        if (state.equals("downloading")) return null;
        if (!context.getPackageManager().canRequestPackageInstalls()) return "install_off";
        state = "downloading";
        Context app = context.getApplicationContext();
        String url = apkUrl;
        new Thread(() -> {
            try {
                File apk = new File(app.getCacheDir(), "update.apk");
                try (OutputStream o = new FileOutputStream(apk)) {
                    o.write(get(url, MAX_APK));
                }
                String bad = verify(app, apk);
                if (bad != null) {
                    state = bad;
                    apk.delete();
                    return;
                }
                install(app, apk);
            } catch (Exception e) {
                android.util.Log.i("ParentLock", "update failed: " + e);
                state = "update_fail";
            }
        }, "update").start();
        return null;
    }

    /** null when the APK is this app, newer, and signed by the same key. */
    private static String verify(Context context, File apk) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo got = pm.getPackageArchiveInfo(apk.getPath(), flags);
        PackageInfo me = pm.getPackageInfo(context.getPackageName(), flags);
        if (got == null || !context.getPackageName().equals(got.packageName)) return "update_bad";
        if (code(got) <= code(me)) return "no_update";
        if (!Arrays.equals(signers(got), signers(me))) return "update_key";
        return null;
    }

    private static void install(Context context, File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(context.getPackageName());
        int id = installer.createSession(p);
        try (PackageInstaller.Session s = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                s.fsync(out);
            }
            int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent done = PendingIntent.getBroadcast(context, id,
                    new Intent(context, UpdateReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | mutable);
            s.commit(done.getIntentSender());
        }
        apk.delete();
    }

    private static Signature[] signers(PackageInfo p) {
        if (Build.VERSION.SDK_INT >= 28) {
            return p.signingInfo == null ? null : p.signingInfo.getApkContentsSigners();
        }
        return p.signatures;
    }

    private static long code(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private static byte[] get(String url, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        c.setRequestProperty("User-Agent", "parental-lock");
        try (InputStream in = c.getInputStream()) {   // follows GitHub's https redirect to the file
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) {
                buf.write(b, 0, n);
                if (buf.size() > max) throw new java.io.IOException("too big");
            }
            return buf.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private Updater() {
    }
}
