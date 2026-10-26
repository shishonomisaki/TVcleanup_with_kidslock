package io.github.shishonomisaki.parentlock;

import android.animation.ValueAnimator;
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
 * A small pill under the clock on the home screen with the viewing time left, shown on Home only so it never
 * sits over a film. Near the end (GuardService: 5, 3 and 1 minutes left) it glows and blinks for 15 seconds,
 * and then it also shows over whatever is playing, without taking focus or touches, and goes again.
 */
final class Countdown {
    private static final long ALERT_MS = 15_000;
    private static final float OPACITY = 0.75f;   // see-through over a film; blinking dips to 0.3
    private static final int NORMAL_BG = 0xCC1B1E2C, ALERT_BG = 0xEED9480F;

    private final Context context;
    private final LockState state;
    private final WindowManager windows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView pill;
    private GradientDrawable bg;
    private ValueAnimator blink;
    private boolean onHome;
    private boolean alerting;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (pill == null) return;
            long sec = state.remainingMs() / 1000;
            if (sec <= 0) {
                endAlert.run();
                update(false);
                return;
            }
            String left = String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
            pill.setText(context.getString(alerting ? R.string.countdown_alert : R.string.countdown_left, left));
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable endAlert = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (!alerting) return;
            alerting = false;
            if (blink != null) blink.cancel();
            if (pill != null) {
                pill.setAlpha(OPACITY);
                bg.setColor(NORMAL_BG);
                bg.setStroke(2, 0x55FFFFFF);
            }
            if (!onHome) remove();
        }
    };

    Countdown(Context context, LockState state) {
        this.context = context;
        this.state = state;
        this.windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /** Called on every check: whether Home is on screen. An alert keeps the pill up wherever it is. */
    void update(boolean home) {
        boolean moved = home != onHome;
        onHome = home;
        if (home || alerting) {
            if (moved && pill != null) {
                try { windows.updateViewLayout(pill, layout()); } catch (Exception ignored) { }
            }
            show();
        } else {
            remove();
        }
    }

    /** 15 seconds of glow and blink, over Home or over whatever is playing. */
    void alert() {
        alerting = true;
        if (!show()) return;
        bg.setColor(ALERT_BG);
        bg.setStroke(6, 0xFFFFD43B);
        if (blink != null) blink.cancel();
        blink = ValueAnimator.ofFloat(OPACITY, 0.3f);
        blink.setDuration(450);
        blink.setRepeatMode(ValueAnimator.REVERSE);
        blink.setRepeatCount(ValueAnimator.INFINITE);
        blink.addUpdateListener(a -> { if (pill != null) pill.setAlpha((float) a.getAnimatedValue()); });
        blink.start();
        handler.removeCallbacks(endAlert);
        handler.postDelayed(endAlert, ALERT_MS);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** Locked or leaving: everything off. */
    void hide() {
        endAlert.run();
        alerting = false;
        remove();
    }

    private boolean show() {
        if (pill != null) return true;
        pill = new TextView(context);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(22);
        pill.setPadding(36, 14, 36, 14);
        bg = new GradientDrawable();
        bg.setColor(NORMAL_BG);
        bg.setCornerRadius(999);
        bg.setStroke(2, 0x55FFFFFF);
        pill.setBackground(bg);
        pill.setAlpha(OPACITY);
        try {
            windows.addView(pill, layout());
        } catch (Exception e) {
            pill = null;   // overlay permission missing: the phone page still shows the time
            return false;
        }
        handler.post(tick);
        return true;
    }

    /** On Home under the launcher's clock and date, clear of its pills; over an app, tucked into the corner. */
    private WindowManager.LayoutParams layout() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = onHome ? 110 : 40;
        lp.y = onHome ? 230 : 30;
        return lp;
    }

    private void remove() {
        if (pill == null) return;
        handler.removeCallbacks(tick);
        if (blink != null) blink.cancel();
        try { windows.removeView(pill); } catch (Exception ignored) { }
        pill = null;
    }
}
