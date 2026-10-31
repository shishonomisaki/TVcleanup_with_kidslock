package io.github.shishonomisaki.parentlock;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * The phone page's app buttons: three slots, Netflix and YouTube until the parent picks others. Unlike remote
 * keys these need no relay: the app opens the chosen app itself, which it may do from the background because it
 * holds the overlay permission (install.sh).
 */
final class AppShortcuts {
    static final int SLOTS = 3;
    static final String[] DEFAULTS = {"com.netflix.ninja", "com.google.android.youtube.tv", ""};

    // Never offered and never opened: factory and test menus (on some boards opening one flips a factory-mode flag
    // the shell cannot undo) and vendor service screens.
    private static final java.util.regex.Pattern DENY =
            java.util.regex.Pattern.compile("(?i).*(factory|hkcfac|test|disclaimer|\\.tv\\.service).*");

    private AppShortcuts() {
    }

    static boolean allowed(Context context, String pkg) {
        return !DENY.matcher(pkg).matches() && !pkg.equals(context.getPackageName());
    }

    /** The slots as {pkg, label} objects, or null for an empty slot or an app no longer installed. */
    static JSONArray slots(Context context, LockState state) throws Exception {
        PackageManager pm = context.getPackageManager();
        JSONArray a = new JSONArray();
        for (int i = 0; i < SLOTS; i++) {
            String pkg = state.shortcut(i, DEFAULTS[i]);
            String label = pkg.isEmpty() ? null : label(pm, pkg);
            a.put(label == null ? JSONObject.NULL : new JSONObject().put("pkg", pkg).put("label", label));
        }
        return a;
    }

    /** Every app the TV can launch, sorted by name, for picking a slot. */
    static JSONArray launchable(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        Map<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String pkg = r.activityInfo.packageName;
                if (!allowed(context, pkg) || byLabel.containsValue(pkg)) continue;
                byLabel.put(r.loadLabel(pm).toString() + "\u0000" + pkg, pkg);
            }
        }
        JSONArray a = new JSONArray();
        for (Map.Entry<String, String> e : byLabel.entrySet()) {
            a.put(new JSONObject().put("pkg", e.getValue()).put("label", e.getKey().split("\u0000")[0]));
        }
        return a;
    }

    static boolean launch(Context context, LockState state, int slot) {
        String pkg = state.shortcut(slot, DEFAULTS[slot]);
        if (pkg.isEmpty() || !allowed(context, pkg)) return false;
        PackageManager pm = context.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(i);
            return true;
        } catch (Exception e) {
            android.util.Log.i("ParentLock", "launch failed: " + e);
            return false;
        }
    }

    static boolean installed(Context context, String pkg) {
        if (!allowed(context, pkg)) return false;
        PackageManager pm = context.getPackageManager();
        return pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
