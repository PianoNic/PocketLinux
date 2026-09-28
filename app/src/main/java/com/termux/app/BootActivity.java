package com.termux.app;

import android.animation.ArgbEvaluator;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.graphics.shapes.Morph;
import androidx.graphics.shapes.RoundedPolygon;
import androidx.graphics.shapes.Shapes_androidKt;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.shape.MaterialShapes;
import com.termux.R;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * The only screen of the app: installs the base system, runs the one-time setup,
 * then boots straight into the Linux desktop (Termux:X11 window).
 */
public class BootActivity extends Activity {

    private static final String X11_ACTIVITY = "com.termux.x11.MainActivity";

    private static final String HOME = TermuxConstants.TERMUX_HOME_DIR_PATH;
    private static final String PREFIX = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
    private static final File READY = new File(HOME, ".termux/desktop-ready");
    private static final File SETUP_LOG = new File(HOME, ".termux/desktop-setup.log");
    private static final File SETUP_EXIT = new File(HOME, ".termux/desktop-setup.exit");
    private static final File X11_APK = new File(PREFIX, "tmp/termux-x11.apk");
    /** firstrun.sh also installs the developer tools when this file exists. */
    private static final File DEV_TOOLS = new File(HOME, ".termux/dev-tools");
    private static final File DISTRO = new File(HOME, ".termux/distro");

    private enum State { INSTALLING, WELCOME, SETUP, NEED_X11, KILLER_WARNING, BOOTING, RUNNING, FAILED, LOGS }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private State mState = State.INSTALLING;
    private boolean mAutoLaunched = false;
    private boolean mSetupConfirmed = false;
    private boolean mLand;

    private TextView mTitle, mStatus, mLog;
    private ScrollView mLogScroll;
    private View mProgress, mLogCard, mDevCard, mDistroCard, mShape, mBackdrop, mContent;
    private LinearLayout mRoot;
    private MaterialCardView mHero;
    private ImageView mLogo;
    private MaterialSwitch mDevSwitch, mDistroSwitch;
    private Button mPrimary, mSecondary, mLogsButton;
    private MorphDrawable mMorph;
    private ObjectAnimator mSpin, mDrift;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        buildUi();

        TermuxInstaller.setupBootstrapIfNeeded(this, this::showInstallProgress, () -> {
            new Thread(() -> {
                installScripts();
                runOnUiThread(this::next);
            }).start();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the Termux:X11 installer or from the desktop.
        if (mState == State.NEED_X11 || mState == State.RUNNING || mState == State.KILLER_WARNING) next();
    }

    // The desktop runs in this process, so the endless animations must not tick behind it.
    @Override
    protected void onStart() {
        super.onStart();
        if (mDrift != null) mDrift.resume();
        if (mSpin != null) mSpin.resume();
    }

    @Override
    protected void onStop() {
        mDrift.pause();
        mSpin.pause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Decides what to do next based on what is installed. */
    private void next() {
        if (!new File(PREFIX, "bin/bash").exists()) return; // bootstrap not done yet

        if (!READY.exists()) {
            if (!mSetupConfirmed && !DesktopService.sSetupRunning) {
                setState(State.WELCOME, "Welcome", getString(com.termux.R.string.app_display_name) + " downloads and installs a full Linux desktop. This takes 10-20 minutes.");
                showButtons("Start setup", v -> { mSetupConfirmed = true; next(); }, null, null);
                return;
            }
            mSetupConfirmed = true;
            setState(State.SETUP, "Setting up " + getString(com.termux.R.string.app_display_name), "First start: downloading and installing the desktop. This takes 10-20 minutes.");
            if (!DesktopService.sSetupRunning) {
                SETUP_EXIT.delete();
                applyDevTools();
                startDesktopService(DesktopService.ACTION_SETUP);
            }
            pollSetup();
            return;
        }

        if (!isX11Installed()) {
            setState(State.NEED_X11, "One more step", "Install the Termux:X11 display app. Android will ask for permission.");
            showButtons("Install display app", v -> installX11(), "Retry setup", v -> retrySetup());
            return;
        }

        // No phantom-killer gate: the slim desktop stays under Android's process limit, and the
        // watchdog restarts anything that still gets killed. The Logs screen shows the status.

        requestBatteryExemptionOnce();

        if (!mAutoLaunched) {
            mAutoLaunched = true;
            setState(State.BOOTING, "Booting", "Starting the desktop...");
            startDesktopService(DesktopService.ACTION_START);
            mHandler.postDelayed(this::openDesktop, 2500);
        } else {
            // Back from the desktop: make sure everything is alive (repairs a black screen).
            startDesktopService(DesktopService.ACTION_START);
            setState(State.RUNNING, getString(com.termux.R.string.app_display_name) + " is running", "The desktop keeps running in the background.");
            showButtons("Open desktop", v -> ensureAndOpenDesktop(), "Shut down", v -> shutDown());
        }
    }

    /** Android 12+ kills child processes of apps in the background unless this is turned off. */
    private boolean phantomKillerActive() {
        if (Build.VERSION.SDK_INT < 31) return false;
        String v = Settings.Global.getString(getContentResolver(), "settings_enable_monitor_phantom_procs");
        return !"false".equals(v);
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        }
    }

    private void requestBatteryExemptionOnce() {
        if (Build.VERSION.SDK_INT < 23 || getPrefs().getBoolean("battery_asked", false)) return;
        getPrefs().edit().putBoolean("battery_asked", true).apply();
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    private android.content.SharedPreferences getPrefs() {
        return getSharedPreferences("desktop", MODE_PRIVATE);
    }

    private void ensureAndOpenDesktop() {
        startDesktopService(DesktopService.ACTION_START);
        setState(State.BOOTING, "Booting", "Checking the desktop...");
        mHandler.postDelayed(this::openDesktop, 1500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Opening the app from the launcher again: go straight back into the desktop.
        if (mState == State.RUNNING) {
            mAutoLaunched = false;
            next();
        }
    }

    private void pollSetup() {
        mHandler.postDelayed(() -> {
            if (mState != State.SETUP) return;
            showLog(tail(SETUP_LOG, 6000));

            if (SETUP_EXIT.exists() && !DesktopService.sSetupRunning) {
                String code = read(SETUP_EXIT).trim();
                if ("0".equals(code) && READY.exists()) {
                    next();
                } else {
                    setState(State.FAILED, "Setup failed", "Check your internet connection and try again. The log is below.");
                    mLog.setText(tail(SETUP_LOG, 6000));
                    showButtons("Retry", v -> retrySetup(), null, null);
                }
                return;
            }
            pollSetup();
        }, 700);
    }

    private void retrySetup() {
        READY.delete();
        SETUP_EXIT.delete();
        mAutoLaunched = false;
        mSetupConfirmed = true;
        next();
    }

    /** The optional packs chosen on the boot screen, read by firstrun.sh. */
    private void applyDevTools() {
        setFlag(DEV_TOOLS, mDevSwitch.isChecked());
        setFlag(DISTRO, mDistroSwitch.isChecked());
    }

    private static void setFlag(File flag, boolean on) {
        try {
            if (on) {
                //noinspection ResultOfMethodCallIgnored
                flag.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                flag.createNewFile();
            } else {
                //noinspection ResultOfMethodCallIgnored
                flag.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private void openDesktop() {
        try {
            Intent i = new Intent().setComponent(new ComponentName(getPackageName(), X11_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            setState(State.RUNNING, getString(com.termux.R.string.app_display_name) + " is running", "The desktop keeps running in the background.");
            showButtons("Open desktop", v -> ensureAndOpenDesktop(), "Shut down", v -> shutDown());
        } catch (Exception e) {
            setState(State.NEED_X11, "Display app missing", "Termux:X11 could not be opened.");
            showButtons("Install display app", v -> installX11(), null, null);
        }
    }

    private void shutDown() {
        startDesktopService(DesktopService.ACTION_STOP);
        finishAndRemoveTask();
    }

    private void installX11() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        if (!X11_APK.exists()) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/termux/termux-x11/releases/tag/nightly")));
            return;
        }
        Uri uri = UriUtils.getContentUri(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, X11_APK.getAbsolutePath());
        Intent i = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** The display is built into this app now (Termux:X11's lorie library). */
    private boolean isX11Installed() {
        return true;
    }

    private void startDesktopService(String action) {
        Intent i = new Intent(this, DesktopService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    /** Copies the bundled scripts into the system on every start, so app updates also update them. */
    private void installScripts() {
        try {
            String[] bins = getAssets().list("desktop/bin");
            File binDir = new File(PREFIX, "bin");
            if (bins != null) for (String name : bins) copyAsset("desktop/bin/" + name, new File(binDir, name));
            File libexec = new File(PREFIX, "libexec/desktop");
            libexec.mkdirs();
            copyAsset("desktop/firstrun.sh", new File(libexec, "firstrun.sh"));
            File share = new File(PREFIX, "share/pocket-linux");
            share.mkdirs();
            copyAsset("desktop/share/menu-icon.svg", new File(share, "menu-icon.svg"));
            // apt hook that moves every package to this app's data directory (see Relocator).
            copyAsset("desktop/relocate-debs", new File(libexec, "relocate-debs"));
            // glibc preload library for .NET (redirects its hardcoded /tmp); set up by bin/dotnet.
            try { copyAsset("desktop/libtmp-redirect.so", new File(libexec, "libtmp-redirect.so")); }
            catch (Exception ignored) { } // built without the cross compiler
            // Preload library that shows the phone's name instead of u0_a123; set up by bin/desktop.
            try { copyAsset("desktop/libdevice-name.so", new File(libexec, "libdevice-name.so")); }
            catch (Exception ignored) { } // built without the NDK
            File aptConf = new File(PREFIX, "etc/apt/apt.conf.d");
            aptConf.mkdirs();
            copyAsset("desktop/apt-relocate.conf", new File(aptConf, "99-pocket-relocate.conf"));
            new File(HOME, ".termux").mkdirs();
            new File(PREFIX, "tmp").mkdirs();
        } catch (Exception ignored) {
        }
    }

    /** Copies a bundled file, moving its Termux paths (shebangs etc.) to this app's ID. */
    private void copyAsset(String asset, File target) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (InputStream in = getAssets().open(asset)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
        }
        byte[] data = bytes.toByteArray();
        Relocator.relocate(data, data.length);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(data);
        }
        //noinspection ResultOfMethodCallIgnored
        target.setExecutable(true, true);
    }

    // ---- Logs -----------------------------------------------------------------------------

    private void showLogs() {
        State before = mState;
        setState(State.LOGS, "Logs", "Tap Share to send them.");
        String text = collectLogs();
        mLog.setText(text);
        mLogScroll.post(() -> mLogScroll.scrollTo(0, mLog.getBottom()));
        showButtons("Share", v -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(com.termux.R.string.app_display_name) + " logs")
                .putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(send, "Share logs"));
        }, "Back", v -> {
            if (before == State.SETUP || before == State.FAILED) { mState = before; retryOrPoll(before); }
            else { mAutoLaunched = true; mState = State.RUNNING; next(); }
        });
    }

    private void retryOrPoll(State before) {
        if (before == State.SETUP) next();
        else {
            setState(State.FAILED, "Setup failed", "Check your internet connection and try again. The log is below.");
            mLog.setText(tail(SETUP_LOG, 6000));
            showButtons("Retry", v -> retrySetup(), null, null);
        }
    }

    private String collectLogs() {
        String tmp = PREFIX + "/tmp/";
        StringBuilder b = new StringBuilder();
        b.append("== device ==\n")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            .append("phantom killer: ").append(phantomKillerActive() ? "ON (desktop will get killed)" : "off").append('\n');
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= 23)
            b.append("battery optimisation exempt: ").append(pm.isIgnoringBatteryOptimizations(getPackageName())).append('\n');
        b.append("setup done: ").append(READY.exists()).append(", display app installed: ").append(isX11Installed()).append('\n');
        b.append("watchdog says running: ").append(DesktopService.sDesktopRunning).append("\n\n");

        b.append("== running processes ==\n").append(listOwnProcesses()).append('\n');
        b.append("== desktop-service.log ==\n").append(tail(new File(tmp + "desktop-service.log"), 4000)).append('\n');
        b.append("== xfce.log ==\n").append(tail(new File(tmp + "xfce.log"), 8000)).append('\n');
        b.append("== termux-x11.log ==\n").append(tail(new File(tmp + "termux-x11.log"), 4000)).append('\n');
        b.append("== setup log (end) ==\n").append(tail(SETUP_LOG, 3000)).append('\n');
        return b.toString();
    }

    /** Processes of this app (children included), read from /proc. */
    private static String listOwnProcesses() {
        StringBuilder b = new StringBuilder();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return "(unavailable)\n";
        for (File p : procs) {
            if (!p.getName().matches("\\d+")) continue;
            String cmd = read(new File(p, "cmdline"), 200).replace('\0', ' ').trim();
            if (cmd.isEmpty()) continue;
            b.append(p.getName()).append("  ").append(cmd).append('\n');
        }
        return b.length() == 0 ? "(none)\n" : b.toString();
    }

    // ---- UI -------------------------------------------------------------------------------

    @SuppressWarnings("deprecation")   // bar colors, still needed below Android 15
    private void buildUi() {
        setContentView(R.layout.activity_boot);
        mRoot = findViewById(R.id.boot_root);
        mHero = findViewById(R.id.boot_hero);
        mContent = findViewById(R.id.boot_content);
        mBackdrop = findViewById(R.id.boot_backdrop);
        mShape = findViewById(R.id.boot_shape);
        mLogo = findViewById(R.id.boot_logo);
        mTitle = findViewById(R.id.boot_title);
        mStatus = findViewById(R.id.boot_status);
        mProgress = findViewById(R.id.boot_progress);
        mDevCard = findViewById(R.id.boot_dev_card);
        mDevSwitch = findViewById(R.id.boot_dev_switch);
        mDistroCard = findViewById(R.id.boot_distro_card);
        mDistroSwitch = findViewById(R.id.boot_distro_switch);
        mLogCard = findViewById(R.id.boot_log_card);
        mLogScroll = findViewById(R.id.boot_log_scroll);
        mLog = findViewById(R.id.boot_log);
        mLogsButton = findViewById(R.id.boot_logs);
        mSecondary = findViewById(R.id.boot_secondary);
        mPrimary = findViewById(R.id.boot_primary);
        mLogsButton.setOnClickListener(v -> showLogs());
        mDevSwitch.setChecked(DEV_TOOLS.exists());
        mDistroSwitch.setChecked(DISTRO.exists());

        int surface = MaterialColors.getColor(mRoot, com.google.android.material.R.attr.colorSurface);
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);

        for (LinearLayout l : new LinearLayout[]{mRoot, (LinearLayout) mContent}) {
            l.setLayoutTransition(new LayoutTransition());
            l.getLayoutTransition().enableTransitionType(LayoutTransition.CHANGING);
        }
        mHero.setClipToOutline(true);
        mMorph = new MorphDrawable();
        mMorph.setColor(MaterialColors.getColor(mRoot, androidx.appcompat.R.attr.colorPrimary));
        mShape.setBackground(mMorph);
        mBackdrop.setBackground(MaterialShapes.createShapeDrawable(MaterialShapes.SUNNY));
        mSpin = ObjectAnimator.ofFloat(mShape, View.ROTATION, 0, 360).setDuration(9000);
        mSpin.setRepeatCount(ValueAnimator.INFINITE);
        mSpin.setInterpolator(new LinearInterpolator());
        mDrift = ObjectAnimator.ofFloat(mBackdrop, View.ROTATION, 0, 360).setDuration(60000);
        mDrift.setRepeatCount(ValueAnimator.INFINITE);
        mDrift.setInterpolator(new LinearInterpolator());
        mDrift.start();

        applyOrientation(getResources().getConfiguration());
        setState(State.INSTALLING, "Preparing", "Installing the base system...");
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        applyOrientation(config);
    }

    private void applyOrientation(Configuration config) {
        mLand = config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        mRoot.setOrientation(mLand ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        mContent.setPadding(dp(mLand ? 24 : 8), dp(mLand ? 8 : 24), dp(8), 0);
        relayout();
    }

    /** Hero on the left in landscape. In portrait it sits on top and fills the space when no log is shown. */
    private void relayout() {
        boolean log = mLogCard.getVisibility() == View.VISIBLE;
        int match = LinearLayout.LayoutParams.MATCH_PARENT, wrap = LinearLayout.LayoutParams.WRAP_CONTENT;
        if (mLand) {
            mHero.setLayoutParams(new LinearLayout.LayoutParams(0, match, 0.8f));
            mContent.setLayoutParams(new LinearLayout.LayoutParams(0, match, 1.2f));
        } else {
            mHero.setLayoutParams(new LinearLayout.LayoutParams(match, log ? dp(224) : 0, log ? 0 : 1f));
            mContent.setLayoutParams(new LinearLayout.LayoutParams(match, log ? 0 : wrap, log ? 1f : 0));
        }
    }

    /** Real progress while the system downloads and unpacks (other states keep the endless wave). */
    private void showInstallProgress(String status, int percent) {
        runOnUiThread(() -> {
            if (mState != State.INSTALLING) return;
            mStatus.setText(status);
            LinearProgressIndicator bar = (LinearProgressIndicator) mProgress;
            if (bar.isIndeterminate()) bar.setIndeterminate(false);
            bar.setProgressCompat(percent, true);
        });
    }

    private void setState(State state, String title, String status) {
        if (state != mState) ((LinearProgressIndicator) mProgress).setIndeterminate(true);
        mState = state;
        if (!title.contentEquals(mTitle.getText())) {
            spring(mTitle, DynamicAnimation.TRANSLATION_Y, dp(32), 0);
            spring(mStatus, DynamicAnimation.TRANSLATION_Y, dp(48), 0);
            mTitle.setAlpha(0);
            mStatus.setAlpha(0);
            mTitle.animate().alpha(1).setDuration(250);
            mStatus.animate().alpha(1).setDuration(350);
        }
        mTitle.setText(title);
        mStatus.setText(status);
        boolean busy = state == State.INSTALLING || state == State.SETUP || state == State.BOOTING;
        mProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        boolean log = state == State.SETUP || state == State.FAILED || state == State.LOGS;
        mLogCard.setVisibility(log ? View.VISIBLE : mLand ? View.INVISIBLE : View.GONE);
        relayout();
        int packs = state == State.WELCOME || state == State.FAILED ? View.VISIBLE : View.GONE;
        mDevCard.setVisibility(packs);
        mDistroCard.setVisibility(packs);
        mLogsButton.setVisibility(state == State.LOGS || state == State.INSTALLING || state == State.WELCOME ? View.INVISIBLE : View.VISIBLE);
        if (busy) showButtons(null, null, null, null);
        applyLook(state, busy);
    }

    /** Each state gets its own shape and color. The hero shape springs into it and spins while busy. */
    private void applyLook(State state, boolean busy) {
        RoundedPolygon shape;
        int container = com.google.android.material.R.attr.colorPrimaryContainer;
        int color = androidx.appcompat.R.attr.colorPrimary;
        int onColor = com.google.android.material.R.attr.colorOnPrimary;
        switch (state) {
            case RUNNING: shape = MaterialShapes.SOFT_BURST; break;
            case WELCOME: shape = MaterialShapes.FLOWER; break;
            case NEED_X11: case KILLER_WARNING: shape = MaterialShapes.PUFFY_DIAMOND; break;
            case LOGS:
                shape = MaterialShapes.CLOVER_4;
                container = com.google.android.material.R.attr.colorTertiaryContainer;
                color = com.google.android.material.R.attr.colorTertiary;
                onColor = com.google.android.material.R.attr.colorOnTertiary;
                break;
            case FAILED:
                shape = MaterialShapes.SOFT_BOOM;
                container = com.google.android.material.R.attr.colorErrorContainer;
                color = androidx.appcompat.R.attr.colorError;
                onColor = com.google.android.material.R.attr.colorOnError;
                break;
            default: shape = MaterialShapes.COOKIE_9;
        }
        if (mMorph.morphTo(shape)) {
            spring(mShape, DynamicAnimation.SCALE_X, 0.7f, 1);
            spring(mShape, DynamicAnimation.SCALE_Y, 0.7f, 1);
        }

        int c0 = mHero.getCardBackgroundColor().getDefaultColor(), s0 = mMorph.mPaint.getColor();
        int c1 = MaterialColors.getColor(mHero, container), s1 = MaterialColors.getColor(mHero, color);
        ArgbEvaluator argb = new ArgbEvaluator();
        ValueAnimator fade = ValueAnimator.ofFloat(0, 1).setDuration(450);
        fade.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            int s = (int) argb.evaluate(f, s0, s1);
            mHero.setCardBackgroundColor((int) argb.evaluate(f, c0, c1));
            mMorph.setColor(s);
            ((ShapeDrawable) mBackdrop.getBackground()).getPaint().setColor(MaterialColors.compositeARGBWithAlpha(s, 56));
            mBackdrop.invalidate();
        });
        fade.start();
        mLogo.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(mHero, onColor)));

        if (busy && !mSpin.isStarted()) {
            mSpin.setFloatValues(mShape.getRotation(), mShape.getRotation() + 360);
            mSpin.start();
        } else if (!busy && mSpin.isStarted()) {
            mSpin.cancel();
            float r = mShape.getRotation() % 360;
            spring(mShape, DynamicAnimation.ROTATION, r, r > 180 ? 360 : 0);
        }
    }

    private static void spring(View v, DynamicAnimation.ViewProperty property, float from, float to) {
        SpringAnimation s = new SpringAnimation(v, property, to).setStartValue(from);
        s.getSpring().setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY).setStiffness(SpringForce.STIFFNESS_LOW);
        s.start();
    }

    /** Draws a Material shape that morphs into the next one with a bouncy spring. */
    private static final class MorphDrawable extends Drawable {
        final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final Matrix mMatrix = new Matrix();
        private RoundedPolygon mTarget = MaterialShapes.COOKIE_9;
        private Morph mMorph = new Morph(mTarget, mTarget);
        private float mProgress = 1;
        private final SpringAnimation mSpring = new SpringAnimation(new FloatValueHolder(1));

        MorphDrawable() {
            mSpring.setSpring(new SpringForce(1).setDampingRatio(0.45f).setStiffness(SpringForce.STIFFNESS_LOW));
            mSpring.addUpdateListener((a, value, velocity) -> { mProgress = value; invalidateSelf(); });
        }

        /** Returns false when already showing that shape. */
        boolean morphTo(RoundedPolygon shape) {
            if (shape == mTarget) return false;
            mMorph = new Morph(mTarget, shape);
            mTarget = shape;
            mSpring.cancel();
            mSpring.setStartValue(0);
            mSpring.animateToFinalPosition(1);
            return true;
        }

        void setColor(int color) {
            mPaint.setColor(color);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            mPath.rewind();
            Shapes_androidKt.toPath(mMorph, mProgress, mPath);
            // Material shapes live in a unit square.
            mMatrix.setScale(b.width(), b.height());
            mMatrix.postTranslate(b.left, b.top);
            mPath.transform(mMatrix);
            canvas.drawPath(mPath, mPaint);
        }

        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { mPaint.setColorFilter(cf); }
        @SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private void showButtons(String primary, View.OnClickListener p, String secondary, View.OnClickListener s) {
        mPrimary.setVisibility(primary == null ? View.GONE : View.VISIBLE);
        mPrimary.setText(primary);
        mPrimary.setOnClickListener(p);
        mSecondary.setVisibility(secondary == null ? View.GONE : View.VISIBLE);
        mSecondary.setText(secondary);
        mSecondary.setOnClickListener(s);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- file helpers ---------------------------------------------------------------------

    private static String read(File f) {
        return read(f, 64);
    }

    private static String read(File f, int max) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[max];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readUnused(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) Math.min(raf.length(), 64)];
            raf.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Updates the live log only when it changed, and follows new lines only while the view is
     * at the bottom, so it neither jumps around nor pulls away from what the user scrolled to.
     */
    private void showLog(String text) {
        if (text.contentEquals(mLog.getText())) return;
        boolean atBottom = !mLogScroll.canScrollVertically(1);
        mLog.setText(text);
        if (atBottom) mLogScroll.post(() -> mLogScroll.scrollTo(0, mLog.getBottom()));
    }

    /** Last bytes of the log, cleaned of colour codes and progress-bar carriage returns. */
    private static String tail(File f, int maxBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            byte[] b = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8).replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
            StringBuilder out = new StringBuilder();
            for (String line : s.split("\n")) {
                int cr = line.lastIndexOf('\r', line.length() - 2);
                out.append(cr >= 0 ? line.substring(cr + 1) : line).append('\n');
            }
            return out.toString().replace("\r", "");
        } catch (Exception e) {
            return "";
        }
    }
}
