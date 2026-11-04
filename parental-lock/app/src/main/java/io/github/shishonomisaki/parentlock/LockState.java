package io.github.shishonomisaki.parentlock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Everything the guard needs to know, and nothing else. The TV is locked unless a parent has opened a window:
 * the window is stored against the uptime clock and the boot count, so a reboot, a clock change or a power
 * cut always lands back on the lock screen. A window that runs past midnight is not cut short.
 */
final class LockState {
    static final int[] CHOICES = {30, 60, 90, 120};
    private static final int MAX_FAILS = 5;
    private static final long LOCKOUT_MS = 10 * 60 * 1000L;

    private final Context context;
    private final SharedPreferences prefs;

    LockState(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    // The phone page's port: picked at random on first start, so no two TVs share one; SET_PORT changes it.
    static final int PORT_MIN = 20000;
    static final int PORT_MAX = 59999;

    synchronized int port() {
        int p = prefs.getInt("port", 0);
        if (p == 0) {
            p = PORT_MIN + new SecureRandom().nextInt(PORT_MAX - PORT_MIN + 1);
            prefs.edit().putInt("port", p).apply();
        }
        return p;
    }

    synchronized void setPort(int port) {
        prefs.edit().putInt("port", port).apply();
    }

    // The remote relay (RemoteRelay): unset until SET_RELAY over adb, and then the phone page shows the remote.

    synchronized String relayHost() {
        return prefs.getString("relayHost", null);
    }

    synchronized int relayPort() {
        return prefs.getInt("relayPort", 0);
    }

    synchronized String relayToken() {
        return prefs.getString("relayToken", "");
    }

    /** host null clears it. */
    synchronized void setRelay(String host, int port, String token) {
        if (host == null) {
            prefs.edit().remove("relayHost").remove("relayPort").remove("relayToken").apply();
        } else {
            prefs.edit().putString("relayHost", host).putInt("relayPort", port).putString("relayToken", token).apply();
        }
    }

    // The phone page's app buttons (AppShortcuts): a package per slot, "" for an empty one.

    synchronized String shortcut(int slot, String fallback) {
        return prefs.getString("shortcut" + slot, fallback);
    }

    synchronized void setShortcut(int slot, String pkg) {
        prefs.edit().putString("shortcut" + slot, pkg).apply();
    }

    private int bootCount() {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    /** Milliseconds of viewing left; 0 means locked. */
    synchronized long remainingMs() {
        if (prefs.getInt("boot", -2) != bootCount()) return 0;
        long left = prefs.getLong("until", 0) - SystemClock.elapsedRealtime();
        return Math.max(0, left);
    }

    boolean locked() {
        return remainingMs() == 0;
    }

    /** Settings stay shut unless the parent opened them from the phone. */
    synchronized boolean settingsOpen() {
        if (prefs.getInt("boot", -2) != bootCount()) return false;
        return prefs.getLong("settingsUntil", 0) > SystemClock.elapsedRealtime();
    }

    synchronized long settingsLeftMs() {
        if (!settingsOpen()) return 0;
        return prefs.getLong("settingsUntil", 0) - SystemClock.elapsedRealtime();
    }

    synchronized void unlock(int minutes) {
        rollDay();
        prefs.edit().putInt("boot", bootCount())
                .putLong("until", SystemClock.elapsedRealtime() + minutes * 60_000L)
                .putLong("durationMs", minutes * 60_000L)
                .putInt("extensions", 0)
                .putInt("unlocks", prefs.getInt("unlocks", 0) + 1).apply();
    }

    // "+30 min" on an open window: three times per unlock at most, from the second time on with the PIN (WebServer).

    static final int EXTEND_MINUTES = 30;
    static final int MAX_EXTENSIONS = 3;

    synchronized int extensions() {
        return prefs.getInt("extensions", 0);
    }

    synchronized void extend() {
        long add = EXTEND_MINUTES * 60_000L;
        prefs.edit().putLong("until", prefs.getLong("until", 0) + add)
                .putLong("durationMs", prefs.getLong("durationMs", 0) + add)
                .putInt("extensions", extensions() + 1).apply();
    }

    /** The length of the window the parent picked, for the phone page's ring. */
    synchronized long durationMs() {
        return prefs.getLong("durationMs", 0);
    }

    // Today's totals for the phone page, reset at midnight (local time).

    private void rollDay() {
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(new java.util.Date());
        if (!today.equals(prefs.getString("day", ""))) {
            prefs.edit().putString("day", today).putLong("watchedMs", 0).putInt("unlocks", 0).apply();
        }
    }

    synchronized void addWatched(long ms) {
        rollDay();
        prefs.edit().putLong("watchedMs", prefs.getLong("watchedMs", 0) + ms).apply();
    }

    synchronized long watchedTodayMs() {
        rollDay();
        return prefs.getLong("watchedMs", 0);
    }

    synchronized int unlocksToday() {
        rollDay();
        return prefs.getInt("unlocks", 0);
    }

    synchronized void lock() {
        prefs.edit().putLong("until", 0).putLong("settingsUntil", 0).apply();
    }

    synchronized void openSettings(int minutes) {
        prefs.edit().putInt("boot", bootCount())
                .putLong("settingsUntil", SystemClock.elapsedRealtime() + minutes * 60_000L).apply();
    }

    // PIN: salted SHA-256, five misses lock the phone page for ten minutes.

    synchronized boolean hasPin() {
        return prefs.contains("pinHash");
    }

    synchronized void setPin(String pin) {
        byte[] salt = new byte[8];
        new SecureRandom().nextBytes(salt);
        String s = hex(salt);
        prefs.edit().putString("salt", s).putString("pinHash", hash(s, pin)).putInt("fails", 0).apply();
    }

    /**
     * Null when the PIN is right, else an error code for the phone page, which words it in the phone's language:
     * "lockout" (minutes left in {@code n[0]}), "no_pin", or "wrong_pin" (tries left in {@code n[0]}).
     */
    synchronized String checkPin(String pin, int[] n) {
        long now = SystemClock.elapsedRealtime();
        long until = prefs.getLong("lockoutUntil", 0);
        if (until > now) {
            n[0] = (int) ((until - now) / 60000 + 1);
            return "lockout";
        }
        if (!hasPin()) return "no_pin";
        if (pin != null && hash(prefs.getString("salt", ""), pin).equals(prefs.getString("pinHash", ""))) {
            prefs.edit().putInt("fails", 0).apply();
            return null;
        }
        int fails = prefs.getInt("fails", 0) + 1;
        if (fails >= MAX_FAILS) {
            prefs.edit().putInt("fails", 0).putLong("lockoutUntil", now + LOCKOUT_MS).apply();
            n[0] = (int) (LOCKOUT_MS / 60000);
            return "lockout";
        }
        prefs.edit().putInt("fails", fails).apply();
        n[0] = MAX_FAILS - fails;
        return "wrong_pin";
    }

    synchronized void clearPin() {
        prefs.edit().remove("pinHash").remove("salt").putInt("fails", 0).putLong("lockoutUntil", 0).apply();
    }

    // Recovery code: ten digits the parent keeps somewhere safe, typed with the TV's own remote on the lock
    // screen when the phone can't reach the TV (Tailscale down, a new router, a forgotten PIN). It is separate
    // from the PIN, works once, and is made on the phone page. Tries never run out, but the third miss waits a
    // minute and each one after it ten (ten digits: years and years to guess). The wait runs on the wall clock,
    // so a reboot doesn't reset it (the clock can't be changed while the TV is locked).

    static final int RECOVERY_DIGITS = 10;

    synchronized boolean hasRecovery() {
        return prefs.contains("recHash");
    }

    /** A new code, replacing any old one; shown once on the phone page and stored only as a salted hash. */
    synchronized String newRecovery() {
        SecureRandom r = new SecureRandom();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < RECOVERY_DIGITS; i++) code.append(r.nextInt(10));
        byte[] salt = new byte[16];
        r.nextBytes(salt);
        String s = hex(salt);
        prefs.edit().putString("recSalt", s).putString("recHash", hash(s, code.toString()))
                .putInt("recFails", 0).putLong("recUntil", 0).apply();
        return code.toString();
    }

    /** How long before the next try, 0 when one may be made now. */
    synchronized long recoveryWaitMs() {
        return Math.max(0, prefs.getLong("recUntil", 0) - System.currentTimeMillis());
    }

    /** Tries left before a wait starts (the first wait comes at the third miss). */
    synchronized int recoveryTriesLeft() {
        return Math.max(0, 3 - prefs.getInt("recFails", 0));
    }

    /** True when right: the code is then used up. The third miss waits 1 min, every miss after it 10 min. */
    synchronized boolean checkRecovery(String code) {
        if (!hasRecovery() || recoveryWaitMs() > 0 || code == null) return false;
        if (hash(prefs.getString("recSalt", ""), code).equals(prefs.getString("recHash", ""))) {
            prefs.edit().remove("recHash").remove("recSalt").putInt("recFails", 0).putLong("recUntil", 0).apply();
            return true;
        }
        int f = prefs.getInt("recFails", 0) + 1;
        long wait = f < 3 ? 0 : f == 3 ? 60_000L : 600_000L;
        prefs.edit().putInt("recFails", f).putLong("recUntil", System.currentTimeMillis() + wait).apply();
        return false;
    }

    static boolean validMinutes(int minutes) {
        for (int c : CHOICES) if (c == minutes) return true;
        return false;
    }

    // Volume, kept per output ("tv" for the set's own speakers, "bt" for a Bluetooth speaker, which has its own
    // scale) and per app: each app comes back at the level it was last left at. Starting levels are unset (-1,
    // the volume is left alone) until set over adb, see AdminReceiver: one per output for unlocking and for apps
    // not seen yet, and optionally one per app.

    synchronized int defaultVolume(String output) {
        return prefs.getInt("def:" + output, -1);
    }

    synchronized int appVolume(String output, String pkg) {
        int start = prefs.getInt("def:" + output + ":" + pkg, defaultVolume(output));
        return prefs.getInt("vol:" + output + ":" + pkg, start);
    }

    /** pkg null sets the output's default; volume -1 removes the setting. */
    synchronized void setStartVolume(String output, String pkg, int volume) {
        String key = pkg == null ? "def:" + output : "def:" + output + ":" + pkg;
        if (volume < 0) prefs.edit().remove(key).apply();
        else prefs.edit().putInt(key, volume).apply();
    }

    /** Forgets the levels learned per app; the starting levels set over adb stay. */
    synchronized void resetVolumes() {
        SharedPreferences.Editor e = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith("vol:")) e.remove(key);
        e.apply();
    }

    synchronized void saveAppVolume(String output, String pkg, int volume) {
        if (volume > 0) prefs.edit().putInt("vol:" + output + ":" + pkg, volume).apply();
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + ":" + pin).getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
