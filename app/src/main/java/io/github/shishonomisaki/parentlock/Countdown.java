package io.github.shishonomisaki.parentlock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/**
 * A small pill under the clock on the home screen with the viewing time left. Shown on Home only, so it
 * never sits over a film; it takes no focus and no touches.
 */
final class Countdown {
    private final Context context;
    private final LockState state;
    private final WindowManager windows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView pill;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (pill == null) return;
            long sec = state.remainingMs() / 1000;
            if (sec <= 0) {
                update(false);
                return;
            }
            pill.setText(String.format(Locale.ROOT, "⏱ 剩下 %d:%02d", sec / 60, sec % 60));
            handler.postDelayed(this, 1000);
        }
    };

    Countdown(Context context, LockState state) {
        this.context = context;
        this.state = state;
        this.windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    void update(boolean visible) {
        if (visible && pill == null) {
            pill = new TextView(context);
            pill.setTextColor(Color.WHITE);
            pill.setTextSize(22);
            pill.setPadding(36, 14, 36, 14);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC1B1E2C);
            bg.setCornerRadius(999);
            bg.setStroke(2, 0x55FFFFFF);
            pill.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            // Under the launcher's clock and date, clear of its pills on the right.
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 110;
            lp.y = 230;
            try {
                windows.addView(pill, lp);
            } catch (Exception e) {
                pill = null;   // overlay permission missing: the phone page still shows the time
                return;
            }
            handler.post(tick);
        } else if (!visible && pill != null) {
            handler.removeCallbacks(tick);
            try { windows.removeView(pill); } catch (Exception ignored) { }
            pill = null;
        }
    }
}
