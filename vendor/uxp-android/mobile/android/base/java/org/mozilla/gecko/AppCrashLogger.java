/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Minimal, dependency-free crash logger installed at the very top of
 * Application.onCreate, before Gecko/Goanna or any UI is initialized.
 *
 * - Uncaught Java exceptions are written to files/crash-reports/crash-*.txt
 *   with a full stack trace, device/build/ABI/app metadata, a recent logcat
 *   tail, and (on API 30+) ActivityManager.ApplicationExitInfo records.
 * - A persistent "session-started" marker is written at every app start and
 *   cleared by markUiReady() once an activity resumes. If the marker is still
 *   present at the next launch, the previous run died before the UI came up
 *   (typical for a native library/startup crash), and a startup-*.txt report
 *   is generated that includes any available exit reasons/tombstones.
 * - If any report exists at launch, a notification offers sharing it via
 *   CrashReportShareActivity (FileProvider + ACTION_SEND chooser).
 *
 * The handler must never throw or recurse: everything is wrapped in
 * try/catch(Throwable) and the previous handler is always invoked at the end.
 */
public final class AppCrashLogger implements Thread.UncaughtExceptionHandler {
    private static final String LOGTAG = "AppCrashLogger";

    private static final String REPORT_DIR = "crash-reports";
    private static final String MARKER_FILE = "session-started";
    private static final String CHANNEL_ID = "crash_reports";
    private static final int NOTIFICATION_ID = 0x43524153; // "CRAS"

    private static final int MAX_LOGCAT_BYTES = 192 * 1024;
    private static final int MAX_TOMBSTONE_BYTES = 96 * 1024;

    private static volatile boolean sHandling;

    private final Context mContext;
    private final Thread.UncaughtExceptionHandler mPrevious;

    private AppCrashLogger(Context context, Thread.UncaughtExceptionHandler previous) {
        mContext = context.getApplicationContext();
        mPrevious = previous;
    }

    /**
     * Call as the first statement of Application.onCreate().
     */
    public static synchronized void install(Context context) {
        Context appContext = context.getApplicationContext();
        File dir = getReportDir(appContext);

        // If the previous session never reached UI-ready, the marker survives.
        File marker = new File(dir, MARKER_FILE);
        boolean previousRunIncomplete = marker.exists();
        String previousMarkerContent = previousRunIncomplete ? readSmallFile(marker) : null;

        // Mark the start of this session. Cleared by markUiReady().
        writeSmallFile(marker, "started " + nowIso() + " uptimeMs=" + SystemClock.uptimeMillis());

        if (previousRunIncomplete) {
            writeStartupFailureReport(appContext, previousMarkerContent);
        }

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (!(previous instanceof AppCrashLogger)) {
            Thread.setDefaultUncaughtExceptionHandler(new AppCrashLogger(appContext, previous));
        }

        notifyIfReportsExist(appContext);
    }

    /**
     * Called when real UI is up (first activity resume): the session marker is
     * removed so a later exit is not blamed on startup failure.
     */
    public static void markUiReady(final Context context) {
        // Trivial I/O, but StrictMode debug builds complain about main-thread
        // disk access, so drop it to a background thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new File(getReportDir(context.getApplicationContext()),
                             MARKER_FILE).delete();
                } catch (Throwable t) {
                    // Never propagate.
                }
            }
        }).start();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        if (!sHandling) {
            sHandling = true;
            File report = null;
            try {
                // Fast path first: header + stack trace via direct file I/O,
                // so a report exists even if the process dies below.
                report = writeCrashReportHead(mContext, thread, throwable);
            } catch (Throwable t) {
                Log.e(LOGTAG, "Crash reporting failed", t);
            }
            try {
                // Slow best-effort diagnostics appended second; losing these
                // to a concurrent native crash leaves the head intact.
                if (report != null) {
                    appendDiagnostics(mContext, report);
                }
            } catch (Throwable t) {
                // Ignore.
            }
            if (report != null) {
                Log.e(LOGTAG, "Crash report written to " + report.getAbsolutePath());
            }
            sHandling = false;
        }
        // Always chain: keep default behaviour (log to logcat, process death).
        if (mPrevious != null) {
            mPrevious.uncaughtException(thread, throwable);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    /**
     * Newest report file (crash-*.txt or startup-*.txt), or null.
     */
    public static File latestReport(Context context) {
        File dir = getReportDir(context.getApplicationContext());
        File[] files = dir.listFiles();
        File latest = null;
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if ((name.startsWith("crash-") || name.startsWith("startup-"))
                        && name.endsWith(".txt")) {
                    if (latest == null || f.lastModified() > latest.lastModified()) {
                        latest = f;
                    }
                }
            }
        }
        return latest;
    }

    static File getReportDir(Context context) {
        File dir = new File(context.getFilesDir(), REPORT_DIR);
        if (!dir.isDirectory()) {
            try {
                dir.mkdirs();
            } catch (Throwable t) {
                // Best effort only.
            }
        }
        return dir;
    }

    // ---------- reports ----------

    private static File writeCrashReportHead(Context context, Thread thread,
                                             Throwable throwable) {
        StringBuilder body = new StringBuilder();
        appendHeader(context, body);
        body.append('\n');
        body.append("=== Uncaught Java exception ===\n");
        body.append("Thread: ").append(thread.getName())
            .append(" (id=").append(thread.getId()).append(")\n\n");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        body.append(sw.toString());
        return writeReport(context, "crash", body.toString());
    }

    private static void appendDiagnostics(Context context, File report) {
        StringBuilder body = new StringBuilder();
        body.append('\n');
        body.append("=== Process exit history ===\n");
        appendExitInfo(context, body);
        body.append('\n');
        body.append("=== Logcat tail ===\n");
        body.append(readLogcat());
        appendToReport(report, body.toString());
    }

    private static void writeStartupFailureReport(Context context, String markerContent) {
        StringBuilder body = new StringBuilder();
        appendHeader(context, body);
        body.append('\n');
        body.append("=== Previous run did not reach the UI ===\n");
        body.append("The app exited without any activity resuming; this usually means a ")
            .append("native (Goanna/linker) crash or an early process kill.\n");
        if (markerContent != null) {
            body.append("Previous session marker: ").append(markerContent).append('\n');
        }
        appendDiagnostics(context, body);
        writeReport(context, "startup", body.toString());
    }

    private static void appendHeader(Context context, StringBuilder body) {
        body.append("New Moon crash report\n");
        body.append("=====================\n");
        body.append("Reported at: ").append(nowIso()).append('\n');
        body.append("Uptime: ").append(SystemClock.uptimeMillis()).append(" ms\n");
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            body.append("App: ").append(pi.packageName)
                .append(" versionName=").append(pi.versionName)
                .append(" versionCode=").append(pi.versionCode).append('\n');
        } catch (PackageManager.NameNotFoundException e) {
            body.append("App: ").append(context.getPackageName()).append('\n');
        }
        body.append('\n');
        body.append("=== Device ===\n");
        body.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        body.append("Brand/Model: ").append(Build.BRAND).append(' ').append(Build.MODEL).append('\n');
        body.append("Device/Product: ").append(Build.DEVICE).append(' ').append(Build.PRODUCT).append('\n');
        body.append("Board: ").append(Build.BOARD).append('\n');
        body.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT)
            .append(", ").append(Build.VERSION.INCREMENTAL).append(")\n");
        body.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n');
        if (Build.VERSION.SDK_INT >= 21) {
            body.append("ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
            body.append("64-bit ABIs: ").append(Arrays.toString(Build.SUPPORTED_64_BIT_ABIS)).append('\n');
        } else {
            body.append("ABI: ").append(Build.CPU_ABI).append('/').append(Build.CPU_ABI2).append('\n');
        }
    }

    private static void appendDiagnostics(Context context, StringBuilder body) {
        body.append('\n');
        body.append("=== Process exit history ===\n");
        appendExitInfo(context, body);
        body.append('\n');
        body.append("=== Logcat tail ===\n");
        body.append(readLogcat());
    }

    private static void appendToReport(File report, String text) {
        FileWriter w = null;
        try {
            w = new FileWriter(report, true);
            w.write(text);
        } catch (Throwable t) {
            // Best effort only.
        } finally {
            if (w != null) {
                try { w.close(); } catch (Throwable t) { }
            }
        }
    }

    /**
     * ApplicationExitInfo (API 30+): reason codes and, for native crashes, the
     * system tombstone for our own process. Unavailable/absent on older APIs.
     */
    private static void appendExitInfo(Context context, StringBuilder body) {
        if (Build.VERSION.SDK_INT < 30) {
            body.append("ApplicationExitInfo requires API 30+\n");
            return;
        }
        try {
            ActivityManager am =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<?> exits =
                am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
            if (exits == null || exits.isEmpty()) {
                body.append("No recorded process exits.\n");
                return;
            }
            for (Object o : exits) {
                android.app.ApplicationExitInfo info = (android.app.ApplicationExitInfo) o;
                body.append("- ").append(iso(info.getTimestamp()))
                    .append(" process=").append(info.getProcessName())
                    .append(" pid=").append(info.getPid())
                    .append(" reason=").append(info.getReason())
                    .append('(').append(exitReasonName(info.getReason())).append(')')
                    .append(" status=").append(info.getStatus())
                    .append(" importance=").append(info.getImportance())
                    .append('\n');
                String desc = info.getDescription();
                if (desc != null) {
                    body.append("  description: ").append(desc).append('\n');
                }
                if (info.getReason() == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                        || info.getReason() == android.app.ApplicationExitInfo.REASON_CRASH
                        || info.getReason() == android.app.ApplicationExitInfo.REASON_ANR) {
                    String trace = readTraceStream(info);
                    if (trace != null && trace.length() > 0) {
                        body.append("  --- trace/tombstone ---\n");
                        body.append(trace).append('\n');
                    }
                }
            }
        } catch (Throwable t) {
            body.append("Exit info unavailable: ").append(t).append('\n');
        }
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 14: return "FREEZER";
            default: return "reason_" + reason;
        }
    }

    private static String readTraceStream(android.app.ApplicationExitInfo info) {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        java.io.InputStream in = null;
        try {
            in = info.getTraceInputStream();
            if (in == null) {
                return null;
            }
            byte[] buf = new byte[4096];
            int total = 0;
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0 && total < MAX_TOMBSTONE_BYTES) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                total += n;
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable t) { }
            }
        }
    }

    /**
     * Apps may read their own log records without any permission since API 16;
     * other buffers simply come back empty. Bounded tail only.
     */
    private static String readLogcat() {
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {
                "logcat", "-d", "-b", "main", "-b", "system", "-b", "crash",
                "-t", "300", "-v", "threadtime"
            });
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > MAX_LOGCAT_BYTES) {
                    sb.delete(0, sb.length() - MAX_LOGCAT_BYTES);
                }
            }
            return sb.length() == 0 ? "(empty)\n" : sb.toString();
        } catch (Throwable t) {
            return "(logcat unavailable: " + t + ")\n";
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable t) { }
            }
            if (process != null) {
                try { process.destroy(); } catch (Throwable t) { }
            }
        }
    }

    // ---------- next-launch surfacing ----------

    private static void notifyIfReportsExist(Context context) {
        try {
            File latest = latestReport(context);
            if (latest == null) {
                return;
            }
            Intent intent = new Intent(context, CrashReportShareActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pending = PendingIntent.getActivity(
                context, 0, intent, flags);

            // API 33+ needs a runtime permission for notifications, and the
            // user may have declined or never been asked: also open the share
            // activity directly when the newest report has not been surfaced
            // yet. The chooser outlives the app process, which matters in a
            // startup crash loop.
            File watermark = new File(getReportDir(context), "surfaced");
            long shown = 0;
            try {
                shown = Long.parseLong(readSmallFile(watermark).trim());
            } catch (Throwable t) {
                // No usable watermark.
            }
            if (latest.lastModified() > shown) {
                try {
                    context.startActivity(intent);
                    writeSmallFile(watermark,
                        String.valueOf(latest.lastModified()));
                } catch (Throwable t) {
                    // Could not open the activity; leave unsurfaced so the
                    // next launch retries.
                }
            }

            Notification.Builder builder;
            NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, "Crash reports", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }
            // Must be a drawable inside our own package; android.R IDs resolve
            // against the app package and get silently dropped at post time.
            builder.setSmallIcon(context.getApplicationInfo().icon)
                .setContentTitle("New Moon crashed")
                .setContentText("A crash report was saved. Tap to view or share it.")
                .setContentIntent(pending)
                .setAutoCancel(true);
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            Log.w(LOGTAG, "Could not post crash notification", t);
        }
    }

    // ---------- small helpers ----------

    private static File writeReport(Context context, String prefix, String text) {
        FileWriter w = null;
        try {
            String name = prefix + "-" + System.currentTimeMillis() + ".txt";
            File f = new File(getReportDir(context), name);
            w = new FileWriter(f);
            w.write(text);
            w.flush();
            return f;
        } catch (Throwable t) {
            Log.e(LOGTAG, "Could not write crash report", t);
            return null;
        } finally {
            if (w != null) {
                try { w.close(); } catch (Throwable t) { }
            }
        }
    }

    private static void writeSmallFile(File file, String text) {
        FileWriter w = null;
        try {
            w = new FileWriter(file);
            w.write(text);
        } catch (Throwable t) {
            // Marker is best-effort.
        } finally {
            if (w != null) {
                try { w.close(); } catch (Throwable t) { }
            }
        }
    }

    private static String readSmallFile(File file) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[(int) Math.min(file.length(), 4096)];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable t) { }
            }
        }
    }

    private static String nowIso() {
        return iso(System.currentTimeMillis());
    }

    private static String iso(long epochMs) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(epochMs));
    }
}
