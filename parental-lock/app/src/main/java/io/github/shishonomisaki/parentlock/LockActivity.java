package io.github.shishonomisaki.parentlock;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The lock screen: black, one sentence, a clock. Between 03:00 and 08:00 a second line suggests going back to
 * bed, a different one each time the screen comes up. Closes itself once the TV is unlocked.
 */
public class LockActivity extends Activity {
    private static final int EARLY_FROM = 3, EARLY_UNTIL = 8;   // hours, local time

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView clock;
    private TextView bedtime;
    private LockState state;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!state.locked()) {
                finish();
                return;
            }
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            bedtime.setVisibility(hour >= EARLY_FROM && hour < EARLY_UNTIL ? View.VISIBLE : View.GONE);
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = new LockState(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);

        TextView message = new TextView(this);
        message.setText(R.string.locked_message);
        message.setTextColor(Color.WHITE);
        message.setTextSize(48);
        message.setTypeface(Typeface.DEFAULT_BOLD);
        message.setGravity(Gravity.CENTER);

        clock = new TextView(this);
        clock.setTextColor(0xFF8A8FA8);
        clock.setTextSize(44);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(0, 48, 0, 0);

        bedtime = new TextView(this);
        bedtime.setTextColor(0xFFB8C4FF);
        bedtime.setTextSize(30);
        bedtime.setGravity(Gravity.CENTER);
        bedtime.setPadding(64, 56, 64, 0);
        bedtime.setVisibility(View.GONE);

        recovery = new TextView(this);
        recovery.setTextColor(Color.WHITE);
        recovery.setTextSize(30);
        recovery.setTypeface(Typeface.MONOSPACE);
        recovery.setGravity(Gravity.CENTER);
        recovery.setPadding(64, 56, 64, 0);
        recovery.setVisibility(View.GONE);

        root.addView(message);
        root.addView(clock);
        root.addView(bedtime);
        root.addView(recovery);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String[] lines = getResources().getStringArray(R.array.bedtime_lines);
        bedtime.setText(lines[new java.util.Random().nextInt(lines.length)]);
        front = this;
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        if (front == this) front = null;
        handler.removeCallbacks(tick);
        super.onPause();
    }

    // The guard already eats the keys; this is the belt to its braces.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return state.locked() || super.dispatchKeyEvent(event);
    }

    // Recovery: holding OK for 5 s opens a box for the ten-digit recovery code (up/down change a digit,
    // left/right move, number keys type it, OK tries it, Back closes it). The right code opens a menu: unlock
    // for an hour, open the TV settings for 15 min, clear the PIN, or remove Parental Lock. The guard eats every
    // key while locked and hands them here (GuardService.onKeyEvent), since this screen never sees them itself.

    private static final long HOLD_MS = 5000;
    static volatile LockActivity front;   // the lock screen on screen now, for the guard's keys

    private TextView recovery;            // the code box, or the menu after the right code
    private final int[] digits = new int[LockState.RECOVERY_DIGITS];
    private int at;
    private boolean menu;
    private int choice;

    /** A remote key while locked; true when the recovery box or menu used it. */
    boolean onRemoteKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
        if (recovery.getVisibility() != View.VISIBLE) {
            if (ok && e.getAction() == KeyEvent.ACTION_DOWN && e.getEventTime() - e.getDownTime() >= HOLD_MS) {
                openBox();
            } else if (ok && e.getAction() == KeyEvent.ACTION_UP && e.getEventTime() - e.getDownTime() >= HOLD_MS) {
                openBox();
            }
            return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (k == KeyEvent.KEYCODE_BACK) {
            closeBox();
            return true;
        }
        if (menu) {
            int n = getResources().getStringArray(R.array.recovery_menu).length;
            if (k == KeyEvent.KEYCODE_DPAD_UP) choice = (choice + n - 1) % n;
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) choice = (choice + 1) % n;
            else if (ok && e.getRepeatCount() == 0) {
                act(choice);
                return true;
            }
            drawMenu(null);
            return true;
        }
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            digits[at] = k - KeyEvent.KEYCODE_0;
            at = Math.min(at + 1, digits.length - 1);
        } else if (k == KeyEvent.KEYCODE_DPAD_UP) digits[at] = (digits[at] + 1) % 10;
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) digits[at] = (digits[at] + 9) % 10;
        else if (k == KeyEvent.KEYCODE_DPAD_LEFT) at = Math.max(at - 1, 0);
        else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) at = Math.min(at + 1, digits.length - 1);
        else if (ok && e.getRepeatCount() == 0) {
            tryCode();
            return true;
        }
        drawBox(null);
        return true;
    }

    private void openBox() {
        if (recovery.getVisibility() == View.VISIBLE) return;
        java.util.Arrays.fill(digits, 0);
        at = 0;
        menu = false;
        recovery.setVisibility(View.VISIBLE);
        if (!state.hasRecovery()) drawBox(getString(R.string.recovery_none));
        else drawBox(waitText());
    }

    private void closeBox() {
        recovery.setVisibility(View.GONE);
        menu = false;
    }

    private String waitText() {
        long w = state.recoveryWaitMs();
        return w > 0 ? getString(R.string.recovery_wait, (int) ((w + 59_999) / 60000)) : null;
    }

    private void tryCode() {
        if (!state.hasRecovery()) {
            drawBox(getString(R.string.recovery_none));
            return;
        }
        if (state.recoveryWaitMs() > 0) {
            drawBox(waitText());
            return;
        }
        StringBuilder code = new StringBuilder();
        for (int d : digits) code.append(d);
        if (state.checkRecovery(code.toString())) {
            menu = true;
            choice = 0;
            drawMenu(getString(R.string.recovery_right));
            return;
        }
        String w = waitText();
        drawBox(w != null ? w : getString(R.string.recovery_wrong, state.recoveryTriesLeft()));
    }

    private void drawBox(String note) {
        StringBuilder s = new StringBuilder(getString(R.string.recovery_title)).append("\n\n");
        for (int i = 0; i < digits.length; i++) {
            if (i == 5) s.append("  ");
            s.append(i == at ? "[" + digits[i] + "]" : " " + digits[i] + " ");
        }
        s.append("\n\n").append(note != null ? note : getString(R.string.recovery_help));
        recovery.setText(s);
    }

    private void drawMenu(String note) {
        String[] items = getResources().getStringArray(R.array.recovery_menu);
        StringBuilder s = new StringBuilder(note != null ? note : getString(R.string.recovery_right)).append("\n\n");
        for (int i = 0; i < items.length; i++) s.append(i == choice ? "▶ " : "   ").append(items[i]).append('\n');
        s.append('\n').append(getString(R.string.recovery_menu_help));
        recovery.setText(s);
    }

    /** 0 unlock 60 min, 1 also open the TV settings for 15 min, 2 also clear the PIN, 3 remove Parental Lock. */
    private void act(int which) {
        state.unlock(60);
        if (which == 1) state.openSettings(15);
        if (which == 2) state.clearPin();
        android.util.Log.i("ParentLock", "recovery code used, choice " + which);
        GuardService.refresh();
        if (which == 3) {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_DELETE,
                    android.net.Uri.parse("package:" + getPackageName()))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        closeBox();
        finish();
    }

    @Override
    public void onBackPressed() {
        if (!state.locked()) super.onBackPressed();
    }
}
