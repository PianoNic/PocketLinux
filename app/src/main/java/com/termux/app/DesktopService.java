package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the Linux system alive. Runs the one-time setup and the desktop as child processes,
 * holds a foreground notification + wake lock so Android does not kill them while the
 * Termux:X11 window is in front.
 */
public class DesktopService extends Service {

    public static final String ACTION_SETUP = "com.termux.desktop.SETUP";
    public static final String ACTION_START = "com.termux.desktop.START";
    public static final String ACTION_STOP = "com.termux.desktop.STOP";

    private static final String CHANNEL_ID = "desktop";
    private static final int NOTIFICATION_ID = 1337;

    public static volatile boolean sSetupRunning = false;
    public static volatile boolean sDesktopRunning = false;

    private PowerManager.WakeLock mWakeLock;
    private final List<Process> mProcesses = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification("Starting Linux..."));
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linux:desktop");
        mWakeLock.acquire();
        // Tell scripts started by hand (XFCE terminal) where the built-in X server lives.
        try (java.io.FileWriter w = new java.io.FileWriter(
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/desktop-apk"))) {
            w.write(getApplicationInfo().sourceDir);
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ACTION_SETUP.equals(action)) {
            if (!sSetupRunning) runSetup();
        } else {
            startDesktop();
        }
        return START_STICKY;
    }

    private void runSetup() {
        sSetupRunning = true;
        updateNotification("Setting up Linux (first start)...");
        new Thread(() -> {
            int code = run(TermuxConstants.TERMUX_LIBEXEC_PREFIX_DIR_PATH + "/desktop/firstrun.sh");
            sSetupRunning = false;
            updateNotification(code == 0 ? "Setup finished" : "Setup failed, open the app");
        }).start();
    }

    private volatile Thread mWatchdog;
    private volatile boolean mStopping = false;

    /**
     * Starts the desktop and keeps it alive: every few seconds the watchdog re-runs the
     * idempotent `desktop --no-launch`, which restarts only the parts that died (e.g. XFCE
     * killed by Android's phantom process killer). The watchdog is a Java thread inside the
     * app, so the killer cannot take it down with the child processes.
     */
    private final Object mKick = new Object();

    private synchronized void startDesktop() {
        mStopping = false;
        updateNotification("Desktop running");
        if (mWatchdog != null && mWatchdog.isAlive()) {
            // Wake the watchdog for an immediate check. Never interrupt it: that would abandon a
            // running check while its script keeps going, and two copies would start two desktops.
            synchronized (mKick) { mKick.notifyAll(); }
            return;
        }
        mWatchdog = new Thread(() -> {
            int restartsInWindow = 0;
            long windowStart = System.currentTimeMillis();
            while (!mStopping) {
                // Exit code 3 = the script had to (re)start something.
                int code = runUninterruptibly(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/desktop", "--no-launch");
                sDesktopRunning = code == 0 || code == 3;

                long now = System.currentTimeMillis();
                if (now - windowStart > 120_000) { windowStart = now; restartsInWindow = 0; }
                if (code != 0) restartsInWindow++;
                boolean crashing = restartsInWindow > 5;
                updateNotification(crashing ? "Desktop keeps crashing, retrying in 1 min" : "Desktop running");
                synchronized (mKick) {
                    try { mKick.wait(crashing ? 60_000 : 5_000); } catch (InterruptedException ignored) { }
                }
            }
        }, "desktop-watchdog");
        mWatchdog.start();
    }

    /** Like run(), but keeps waiting for the process even if the thread gets interrupted. */
    private int runUninterruptibly(String script, String... args) {
        return run(script, args);
    }

    private void stopEverything() {
        mStopping = true;
        synchronized (mKick) { mKick.notifyAll(); }
        new Thread(() -> {
            synchronized (mProcesses) {
                for (Process p : mProcesses) p.destroy();
                mProcesses.clear();
            }
            run(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/desktop", "--stop");
            sDesktopRunning = false;
            stopForeground(true);
            stopSelf();
        }).start();
    }

    /** Runs a script with the Termux environment and waits for it. Background children keep running. */
    private int run(String script, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash");
            cmd.add(script);
            for (String a : args) cmd.add(a);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            env.clear();
            HashMap<String, String> termuxEnv = new TermuxShellEnvironment().getEnvironment(this, false);
            env.putAll(termuxEnv);
            // The X server (Termux:X11's CmdEntryPoint) is part of this APK.
            env.put("DESKTOP_APK", getApplicationInfo().sourceDir);
            env.put("DESKTOP_PACKAGE", getPackageName());
            pb.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            File log = new File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, "desktop-service.log");
            log.getParentFile().mkdirs();
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            pb.redirectInput(new File("/dev/null"));

            Process p = pb.start();
            synchronized (mProcesses) { mProcesses.add(p); }
            int code;
            while (true) {
                try { code = p.waitFor(); break; }
                catch (InterruptedException ignored) { } // never abandon a running script
            }
            synchronized (mProcesses) { mProcesses.remove(p); }
            return code;
        } catch (Exception e) {
            return -1;
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, getString(R.string.app_display_name), NotificationManager.IMPORTANCE_LOW));
        }
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, BootActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, DesktopService.class).setAction(ACTION_STOP), piFlags);
        PendingIntent settings = PendingIntent.getActivity(this, 2,
            new Intent(this, com.termux.x11.LoriePreferences.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.app_display_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setColor(getColor(R.color.pocket_amber))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Display settings", settings)
            .addAction(0, "Shut down", stop)
            .build();
    }

    private String mLastNotificationText;

    /** Only re-posts when the text changes, so the notification shade does not keep jumping. */
    private synchronized void updateNotification(String text) {
        if (text.equals(mLastNotificationText)) return;
        mLastNotificationText = text;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
