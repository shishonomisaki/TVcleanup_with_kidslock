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

        root.addView(message);
        root.addView(clock);
        root.addView(bedtime);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String[] lines = getResources().getStringArray(R.array.bedtime_lines);
        bedtime.setText(lines[new java.util.Random().nextInt(lines.length)]);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    // The guard already eats the keys; this is the belt to its braces.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return state.locked() || super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (!state.locked()) super.onBackPressed();
    }
}
