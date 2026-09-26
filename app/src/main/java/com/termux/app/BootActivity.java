package com.termux.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

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

    private enum State { INSTALLING, SETUP, NEED_X11, KILLER_WARNING, BOOTING, RUNNING, FAILED, LOGS }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private State mState = State.INSTALLING;
    private boolean mAutoLaunched = false;

    private TextView mTitle, mStatus, mLog;
    private ScrollView mLogScroll;
    private ProgressBar mProgress;
    private Button mPrimary, mSecondary, mLogsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        buildUi();

        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
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

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Decides what to do next based on what is installed. */
    private void next() {
        if (!new File(PREFIX, "bin/bash").exists()) return; // bootstrap not done yet

        if (!READY.exists()) {
            setState(State.SETUP, "Setting up " + getString(com.termux.R.string.app_display_name), "First start: downloading and installing the desktop. This takes 10-20 minutes.");
            if (!DesktopService.sSetupRunning) {
                SETUP_EXIT.delete();
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

        if (phantomKillerActive() && !getPrefs().getBoolean("killer_warning_dismissed", false)) {
            setState(State.KILLER_WARNING, "Android will kill the desktop",
                "Android stops the desktop's processes while you use it (black screen, disconnects).\n\n"
                + "Fix it once:\n"
                + "1. Settings > About phone > Software information > tap Build number 7 times\n"
                + "2. Settings > Developer options > turn ON \"Disable child process restrictions\"\n"
                + "3. Come back here");
            showButtons("Open Developer options", v -> openDeveloperOptions(),
                "Continue anyway", v -> {
                    getPrefs().edit().putBoolean("killer_warning_dismissed", true).apply();
                    next();
                });
            return;
        }

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
            mLog.setText(tail(SETUP_LOG, 6000));
            mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));

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
        next();
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
            new File(HOME, ".termux").mkdirs();
            new File(PREFIX, "tmp").mkdirs();
        } catch (Exception ignored) {
        }
    }

    private void copyAsset(String asset, File target) throws Exception {
        try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
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
        mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
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

    private void buildUi() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0D10"));
        root.setPadding(pad, dp(64), pad, pad);

        TextView logo = mTitle = new TextView(this);
        logo.setText(getString(com.termux.R.string.app_display_name));
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(40);
        logo.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        root.addView(logo);

        mStatus = new TextView(this);
        mStatus.setTextColor(Color.parseColor("#9AA4B2"));
        mStatus.setTextSize(15);
        mStatus.setPadding(0, dp(8), 0, dp(16));
        root.addView(mStatus);

        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setIndeterminate(true);
        root.addView(mProgress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));

        mLogScroll = new ScrollView(this);
        mLog = new TextView(this);
        mLog.setTextColor(Color.parseColor("#6B7686"));
        mLog.setTextSize(10);
        mLog.setTypeface(Typeface.MONOSPACE);
        mLog.setPadding(0, dp(16), 0, dp(16));
        mLogScroll.addView(mLog);
        root.addView(mLogScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        mLogsButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        mLogsButton.setText("Logs");
        mLogsButton.setTextColor(Color.parseColor("#6B7686"));
        mLogsButton.setOnClickListener(v -> showLogs());
        buttons.addView(mLogsButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mSecondary = new Button(this, null, android.R.attr.borderlessButtonStyle);
        mSecondary.setTextColor(Color.parseColor("#9AA4B2"));
        mPrimary = new Button(this);
        buttons.addView(mSecondary);
        buttons.addView(mPrimary);
        root.addView(buttons);

        setContentView(root);
        setState(State.INSTALLING, "Preparing", "Installing the base system...");
    }

    private void setState(State state, String title, String status) {
        mState = state;
        mTitle.setText(title);
        mStatus.setText(status);
        boolean busy = state == State.INSTALLING || state == State.SETUP || state == State.BOOTING;
        mProgress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        mLogScroll.setVisibility(state == State.SETUP || state == State.FAILED || state == State.LOGS ? View.VISIBLE : View.INVISIBLE);
        mLogsButton.setVisibility(state == State.LOGS || state == State.INSTALLING ? View.INVISIBLE : View.VISIBLE);
        if (busy) showButtons(null, null, null, null);
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
