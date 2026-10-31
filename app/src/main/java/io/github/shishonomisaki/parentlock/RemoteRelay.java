package io.github.shishonomisaki.parentlock;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Remote keys from the phone page. The TV's remote service refuses connections that come from the TV itself, so
 * a key goes to a relay on another machine on the home network (set with SET_RELAY, see AdminReceiver), which
 * sends it on to the TV. A plain socket, like the phone page's server: the relay speaks just enough HTTP.
 */
final class RemoteRelay {
    static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
            "BACK", "HOME", "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE", "MEDIA_PLAY_PAUSE", "POWER"));

    /** True when the relay answered 200. */
    static boolean send(LockState state, String key) {
        String host = state.relayHost();
        if (host == null || !KEYS.contains(key)) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, state.relayPort()), 2000);
            s.setSoTimeout(3000);
            String req = String.format(Locale.ROOT,
                    "POST /key HTTP/1.0\r\nX-Token: %s\r\nContent-Length: %d\r\n\r\n%s",
                    state.relayToken(), key.length(), key);
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] head = new byte[16];
            InputStream in = s.getInputStream();
            int n = in.read(head);
            return n > 12 && new String(head, 0, n, StandardCharsets.US_ASCII).startsWith("HTTP/1.0 200");
        } catch (Exception e) {
            android.util.Log.i("ParentLock", "relay failed: " + e);
            return false;
        }
    }

    private RemoteRelay() {
    }
}
