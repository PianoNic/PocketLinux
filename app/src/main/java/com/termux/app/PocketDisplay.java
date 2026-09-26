package com.termux.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.termux.x11.LorieApp;
import com.termux.x11.MainActivity;
import com.termux.x11.Prefs;

/**
 * Tunes the built-in Termux:X11 display for Pocket Linux without changing its code:
 * <ul>
 *   <li>defaults: no extra-keys bar, resolution follows the window ("scaled" mode)</li>
 *   <li>auto scale: picks the scale from the screen density whenever the window is resized
 *       or moves to another display, so the desktop fits a phone and a monitor alike</li>
 * </ul>
 */
final class PocketDisplay implements Application.ActivityLifecycleCallbacks {

    /** Density (dpi) that maps to 100 %. A phone at ~450 dpi gets 200 %, a monitor 100 %. */
    private static final int BASE_DPI = 225;
    private static final int MIN_SCALE = 100, MAX_SCALE = 300;
    private static final String OWN_PREFS = "pocket_display";

    private final LorieApp app;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private PocketDisplay(LorieApp app) {
        this.app = app;
    }

    static void install(LorieApp app) {
        PocketDisplay d = new PocketDisplay(app);
        d.applyDefaultsOnce();
        app.registerActivityLifecycleCallbacks(d);
    }


    private SharedPreferences own() {
        return app.getSharedPreferences(OWN_PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the scale follows the screen density (the user can turn this off). */
    static boolean isAutoScale(Context ctx) {
        return ctx.getSharedPreferences(OWN_PREFS, Context.MODE_PRIVATE).getBoolean("autoScale", true);
    }

    private void applyDefaultsOnce() {
        if (own().getBoolean("defaultsApplied", false)) return;
        for (Prefs p : new Prefs[] { app.builtInPrefs, app.secondaryPrefs }) {
            p.showAdditionalKbd.put(false);      // the ESC / CTRL / arrow keys bar
            p.additionalKbdVisible.put(false);
            p.displayResolutionMode.put("scaled"); // resolution = window size / scale
        }
        own().edit().putBoolean("defaultsApplied", true).apply();
    }

    // ---- automatic scale ------------------------------------------------------------------

    private void updateScale(Activity activity) {
        if (!isAutoScale(activity)) return;
        Prefs prefs = app.getPrefs(activity);
        if (!"scaled".equals(prefs.displayResolutionMode.get())) return; // user picked another mode

        int dpi = activity.getResources().getDisplayMetrics().densityDpi;
        int scale = Math.round(dpi * 100f / BASE_DPI / 10f) * 10;   // steps of 10 %
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        if (prefs.displayScale.get() != scale)
            // IntPreference has no setter; the display reacts to the change by itself.
            prefs.get().edit().putInt("displayScale", scale).commit();
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {
        if (!(a instanceof MainActivity)) return;
        // Fires on resize, rotation, split screen and when the window moves to another display.
        a.getWindow().getDecorView().addOnLayoutChangeListener(
            (View v, int l, int t, int r, int bo, int ol, int ot, int or, int ob) -> {
                if (r - l != or - ol || bo - t != ob - ot) handler.post(() -> updateScale(a));
            });
    }

    @Override public void onActivityResumed(Activity a) {
        if (!(a instanceof MainActivity)) return;
        updateScale(a);
    }

    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityPaused(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { }

}
