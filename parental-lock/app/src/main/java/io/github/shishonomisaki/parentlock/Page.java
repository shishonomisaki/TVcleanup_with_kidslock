package io.github.shishonomisaki.parentlock;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The phone page, from assets/page.html: two tabs, Time (the countdown ring, then unlock while locked, or +30 min
 * and hold-to-lock while open) and Remote (D-pad, keys, app buttons), light and dark. English, or Traditional
 * Chinese when the phone's language is Chinese; the server sends codes ("wrong_pin", "@home"), the page words them.
 */
final class Page {
    private static String html;

    static synchronized String html(Context context) throws Exception {
        if (html == null) {
            try (InputStream in = context.getAssets().open("page.html")) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                html = buf.toString(StandardCharsets.UTF_8.name());
            }
        }
        return html;
    }

    /** Another file from assets/, read each time (the app icon, the manifest, the service worker). */
    static byte[] asset(Context context, String name) throws Exception {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return buf.toByteArray();
        }
    }

    private Page() {
    }
}
