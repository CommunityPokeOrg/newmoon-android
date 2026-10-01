/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.io.File;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Watchdog living in a dedicated :reporter process, spawned from
 * AppCrashLogger.install() inside Application.attachBaseContext — the
 * earliest Java hook — so it exists before Gecko/Goanna or the UI can die.
 *
 * While the main process is alive it idles (polls every 750 ms). When the
 * watched pid dies it:
 *   - waits up to ~8 s for the system's ApplicationExitInfo record
 *     (API 30+; reason, description, and tombstone trace when available),
 *   - writes a watch-*.txt report including the same-uid logcat tail
 *     (logcat run from this process sees every log entry our uid produced,
 *     including the dying process's own "libc Fatal signal" line; it cannot
 *     see system/other-uid entries without READ_LOGS, which is declared but
 *     inert unless granted via adb),
 *   - mirrors the report to the user-reachable locations
 *     (Downloads/NewMoon/, Android/data/…), posts a notification, and tries
 *     to open CrashReportViewerActivity (best effort — background activity
 *     starts may be blocked after the main process is gone).
 *
 * Clearly-benign exits (user close, OOM, freezer, package update, …)
 * produce no report. The service stops itself after a report or after a
 * bounded watch window; it is restarted on every app launch.
 *
 * Honest limits: a death before attachBaseContext runs (dex verify /
 * package scan) happens before any app code — nothing can be spawned to
 * watch it; and AMS cleanup after a crash can kill this process too, so it
 * polls quickly and writes the report file before trying any UI.
 */
public class CrashWatchService extends Service {
    static final String EXTRA_MAIN_PID = "org.mozilla.gecko.MAIN_PID";

    private static final String LOGTAG = "CrashWatch";
    private static final long POLL_MS = 750;
    private static final long MAX_WATCH_MS = 150 * 1000;
    private static final long EXIT_RECORD_WAIT_MS = 8 * 1000;

    private volatile int mMainPid = -1;
    private volatile boolean mWatching;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int pid = intent.getIntExtra(EXTRA_MAIN_PID, -1);
            if (pid > 0) {
                mMainPid = pid;
            }
        }
        synchronized (this) {
            if (!mWatching) {
                mWatching = true;
                mThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        watchLoop();
                    }
                });
                mThread.setDaemon(true);
                mThread.start();
            }
        }
        return START_NOT_STICKY;
    }

    private Thread mThread;

    private void watchLoop() {
        final long deadline = SystemClock.uptimeMillis() + MAX_WATCH_MS;
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                int pid = mMainPid;
                if (pid > 0 && !pidAlive(pid)) {
                    handleMainDeath(pid);
                    return;
                }
                sleep(POLL_MS);
            }
        } catch (Throwable t) {
            Log.w(LOGTAG, "Watcher failed", t);
        } finally {
            mWatching = false;
            try {
                stopSelf();
            } catch (Throwable t) {
                // Never propagate.
            }
        }
    }

    private boolean pidAlive(int pid) {
        // /proc entry disappears on death; same-uid, no permission needed.
        return new File("/proc/" + pid).exists();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleMainDeath(int pid) {
        android.app.ApplicationExitInfo info = null;
        if (Build.VERSION.SDK_INT >= 30) {
            // Tombstone writing takes a moment after the process dies.
            long until = SystemClock.uptimeMillis() + EXIT_RECORD_WAIT_MS;
            while (SystemClock.uptimeMillis() < until) {
                info = findExitRecord(pid);
                if (info != null) {
                    break;
                }
                sleep(500);
            }
            if (info != null && AppCrashLogger.isBenignExitReason(info.getReason())) {
                Log.i(LOGTAG, "Main process exited benignly (reason "
                        + info.getReason() + "); no report.");
                return;
            }
        }

        StringBuilder body = new StringBuilder();
        AppCrashLogger.appendHeader(this, body);
        body.append('\n');
        body.append("=== Main-process death observed by :reporter watchdog ===\n");
        body.append("Observed at: ").append(AppCrashLogger.nowIso()).append('\n');
        body.append("Watched pid: ").append(pid).append('\n');
        if (info == null) {
            body.append("No ApplicationExitInfo record arrived within ~")
                .append(EXIT_RECORD_WAIT_MS / 1000).append(" s")
                .append(Build.VERSION.SDK_INT < 30
                        ? " (ApplicationExitInfo needs API 30+)" : "")
                .append(" — death cause unknown; see logcat tail below.\n");
        } else {
            body.append("Time: ").append(AppCrashLogger.iso(info.getTimestamp())).append('\n');
            body.append("Process: ").append(info.getProcessName()).append('\n');
            body.append("Reason: ").append(info.getReason())
                .append('(').append(AppCrashLogger.exitReasonName(info.getReason()))
                .append(')').append(" status=").append(info.getStatus())
                .append(" importance=").append(info.getImportance()).append('\n');
            String desc = info.getDescription();
            if (desc != null) {
                body.append("Description: ").append(desc).append('\n');
            }
            String trace = AppCrashLogger.readTraceStream(info);
            if (trace != null && trace.length() > 0) {
                body.append("\n=== Trace / tombstone ===\n");
                body.append(trace).append('\n');
            }
        }
        String marker = AppCrashLogger.readSmallFile(
            new File(AppCrashLogger.getReportDir(this), "session-started"));
        if (marker != null) {
            body.append("\nSession marker: ").append(marker).append('\n');
        }
        body.append('\n');
        body.append("=== Recent process exits (all) ===\n");
        AppCrashLogger.appendExitInfo(this, body);
        body.append('\n');
        body.append("=== Logcat tail (own uid) ===\n");
        body.append(AppCrashLogger.readLogcat());

        File report = AppCrashLogger.writeReport(this, "watch", body.toString());
        if (report == null) {
            return;
        }
        Log.e(LOGTAG, "Main process died; report at " + report.getAbsolutePath());
        try {
            AppCrashLogger.mirrorReportToUserDir(this, report);
        } catch (Throwable t) {
            // Best effort.
        }
        notifyAndOpen(report);
    }

    private android.app.ApplicationExitInfo findExitRecord(int pid) {
        try {
            ActivityManager am =
                (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<?> exits =
                am.getHistoricalProcessExitReasons(getPackageName(), 0, 10);
            if (exits == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            for (Object o : exits) {
                android.app.ApplicationExitInfo info = (android.app.ApplicationExitInfo) o;
                // Match the pid we were watching, and only fresh records.
                if (info.getPid() == pid && now - info.getTimestamp() < 60000) {
                    return info;
                }
            }
        } catch (Throwable t) {
            // Fall through.
        }
        return null;
    }

    private void notifyAndOpen(File report) {
        try {
            Intent intent = new Intent(this, CrashReportViewerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pending = PendingIntent.getActivity(this, 0, intent, flags);

            NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        "crash_reports", "Crash reports",
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
                builder = new Notification.Builder(this, "crash_reports");
            } else {
                builder = new Notification.Builder(this);
            }
            builder.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("New Moon crashed: " + report.getName())
                .setContentText("Saved to "
                        + AppCrashLogger.userVisibleDescription(this)
                        + " — tap to view or share.")
                .setStyle(new Notification.BigTextStyle()
                        .bigText(reportHead(report)))
                .setContentIntent(pending)
                .setAutoCancel(true);
            nm.notify(0x43524154, builder.build()); // "CRAT"
        } catch (Throwable t) {
            Log.w(LOGTAG, "Watch notification failed", t);
        }
        try {
            // May be blocked by background-activity-start rules; harmless.
            startActivity(new Intent(this, CrashReportViewerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Log.w(LOGTAG, "Could not open viewer from watchdog", t);
        }
    }

    private static String reportHead(File report) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(report);
            byte[] buf = new byte[4000];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, "UTF-8") : report.getName();
        } catch (Throwable t) {
            return report.getName();
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable t) { }
            }
        }
    }
}
