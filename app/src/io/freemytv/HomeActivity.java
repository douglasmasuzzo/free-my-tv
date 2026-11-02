package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
        // At power-on "Live TV" (#346): the first time the home screen comes up after a full start, in Free My TV mode.
        if (HomeMode.noteHomeShown(this) && SystemClock.elapsedRealtime() < 240_000 && "live".equals(Prefs.powerOn())) {
            org.json.JSONObject s = HomeMode.state(this);
            if (HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted")) home.postDelayed(powerOnLive, 10_000);
        }
    }

    private boolean resumed, keyPressed;

    /**
     * Late night (the fixed schedule's 22:00–05:29): the screen dimmer and the sound lower, so the room stays dark. The
     * window's own brightness is used (no system setting is written); the volume goes back to what it was after 05:29.
     */
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private boolean lateComfort;
    private int volumeBefore = -1;
    private static final float LATE_BRIGHTNESS = 0.22f, LATE_VOLUME = 0.2f;

    private final Runnable lateCheck = new Runnable() {
        @Override public void run() {
            applyLateComfort();
            handler.postDelayed(this, 60_000);
        }
    };

    private void applyLateComfort() {
        boolean late = HomeData.get(this).isLate(System.currentTimeMillis());
        if (late == lateComfort && (!late || getWindow().getAttributes().screenBrightness == LATE_BRIGHTNESS)) return;
        lateComfort = late;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (late) {
            lp.screenBrightness = LATE_BRIGHTNESS;
            if (audio == null) audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio != null) {
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                int low = Math.max(1, Math.round(max * LATE_VOLUME));
                if (now > low) {
                    volumeBefore = now;
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, low, 0);
                    } catch (RuntimeException e) {
                        AppLog.w("late night: can't lower the volume: " + e.getMessage());
                    }
                }
            }
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            if (audio != null && volumeBefore >= 0) {
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0);
                } catch (RuntimeException e) {
                    AppLog.w("late night: can't restore the volume: " + e.getMessage());
                }
                volumeBefore = -1;
            }
        }
        getWindow().setAttributes(lp);
    }

    /** Live TV, unless a key came first or something else is in front by then. */
    private final Runnable powerOnLive = () -> {
        if (!resumed || keyPressed) return;
        AppLog.w("at power-on: Live TV");
        HomeApps.open(this, HomeApps.action(HomeApps.LIVE_TV, HomeApps.TOP_LIVE, Ui.tr("Live TV"), "live_tv"), null);
    };

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        applyLateComfort();   // the window's brightness is forgotten while another app is in front
        handler.removeCallbacks(lateCheck);
        handler.postDelayed(lateCheck, 60_000);
        Updates.tellOnce(this);   // an update the week's check got ready: said once
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(lateCheck);
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) {
            home.showAllApps();
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean twice = Prefs.recents().equals("home") && lastHome != 0 && now - lastHome < TWICE_MS;
        lastHome = twice ? 0 : now;
        if (twice) home.showRecents();
        else home.reset(true);
    }

    /** When Home was last pressed, and how soon the second press counts (Settings > Home Settings). */
    private long lastHome;
    private static final long TWICE_MS = 700;

    @Override protected void onDestroy() {
        handler.removeCallbacks(lateCheck);
        if (home != null) {
            home.removeCallbacks(powerOnLive);
            home.destroy();
        }
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        keyPressed = true;
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
