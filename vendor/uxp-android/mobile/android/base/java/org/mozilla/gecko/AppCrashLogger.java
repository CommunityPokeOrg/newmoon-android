/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

/**
 * Minimal, dependency-free crash logger installed in
 * Application.attachBaseContext (immediately after multidex, before any
 * Gecko/Goanna/UI code can run).
 *
 * - Every app start writes a persistent "session-started" marker, cleared by
 *   markUiReady() once an activity resumes. A stale marker at next launch
 *   means the previous run died before the UI came up (native/startup crash)
 *   and produces a startup-*.txt report.
 * - Uncaught Java exceptions produce crash-*.txt: stack trace, device/build/
 *   ABI/app metadata, timestamps, a bounded logcat tail, and (API 30+)
 *   ActivityManager.ApplicationExitInfo records including native tombstones.
 *   The report head (header + stack) is written before slow diagnostics so a
 *   concurrent process death still leaves a usable file.
 * - Reports live in files/crash-reports/ and are mirrored to a user-reachable
 *   location: MediaStore Downloads/NewMoon/ (API 29+, no permission needed) and
 *   the app external-files dir (Android/data/<pkg>/files/crash-reports).
 * - On next launch, unsurfaced reports auto-open CrashReportViewerActivity —
 *   an in-app viewer with the report text plus Share/Copy actions that does
 *   not depend on external text apps or the notification permission (which
 *   needs a runtime grant on API 33+). A notification is also posted where
 *   possible.
 *
 * Deaths before attachBaseContext (dex verification, package scan failure)
 * cannot self-report — no app code has run yet; only system logcat covers
 * that. Everything else is caught either here or by the marker.
 *
 * The handler never throws and never recurses: all bodies are wrapped in
 * try/catch(Throwable) and the previous handler is always invoked at the end.
 */
public final class AppCrashLogger implements Thread.UncaughtExceptionHandler {
    private static final String LOGTAG = "AppCrashLogger";

    private static final String REPORT_DIR = "crash-reports";
    private static final String MARKER_FILE = "session-started";
    private static final String SURFACED_FILE = "surfaced";
    private static final String EXIT_SEEN_FILE = "exit-seen";
    private static final String REPORTER_SUFFIX = ":reporter";
    private static final String CHANNEL_ID = "crash_reports";
    private static final int NOTIFICATION_ID = 0x43524153; // "CRAS"
    private static final String DOWNLOADS_SUBDIR = "NewMoon";

    private static final int MAX_LOGCAT_BYTES = 192 * 1024;
    private static final int MAX_TOMBSTONE_BYTES = 96 * 1024;

    private static volatile boolean sInstalled;
    private static volatile boolean sHandling;

    private final Context mContext;
    private final Thread.UncaughtExceptionHandler mPrevious;

    private AppCrashLogger(Context context, Thread.UncaughtExceptionHandler previous) {
        mContext = applicationOrSelf(context);
        mPrevious = previous;
    }

    // getApplicationContext() is null until the Application is attached, so
    // callers in attachBaseContext must fall back to the context itself.
    private static Context applicationOrSelf(Context context) {
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    /**
     * Idempotent; safe to call from attachBaseContext and again in onCreate.
     */
    public static synchronized void install(Context context) {
        Context appContext = applicationOrSelf(context);
        if (sInstalled) {
            ensureOutermost(appContext);
            return;
        }
        if (isReporterProcess(appContext)) {
            // The :reporter process exists only to watch the main process; it
            // must not start the watcher again or write session markers.
            sInstalled = true;
            ensureOutermost(appContext);
            return;
        }
        try {
            File dir = getReportDir(appContext);

            // If the previous session never reached UI-ready, the marker survives.
            File marker = new File(dir, MARKER_FILE);
            boolean previousRunIncomplete = marker.exists();
            String previousMarkerContent =
                previousRunIncomplete ? readSmallFile(marker) : null;

            // Mark the start of this session. Cleared by markUiReady().
            String markerText = "started " + nowIso()
                                + " uptimeMs=" + SystemClock.uptimeMillis();
            writeSmallFile(marker, markerText);
            // Shared copy the standalone Crash Helper app can see: a stale
            // newmoon-session-started file means the last run died before
            // the UI came up even if no report was written.
            writeSharedMarker(appContext, markerText);

            if (previousRunIncomplete) {
                writeStartupFailureReport(appContext, previousMarkerContent);
            }
        } catch (Throwable t) {
            Log.w(LOGTAG, "Startup bookkeeping failed", t);
        }

        sInstalled = true;
        ensureOutermost(appContext);

        // Start the :reporter watchdog BEFORE anything else can kill us: it
        // lives in its own process and observes the main process's death
        // (pid liveness plus ApplicationExitInfo on API 30+), writing a
        // report even when this process never gets as far as onCreate.
        try {
            Intent watch = new Intent(appContext, CrashWatchService.class)
                .putExtra(CrashWatchService.EXTRA_MAIN_PID,
                          android.os.Process.myPid());
            appContext.startService(watch);
        } catch (Throwable t) {
            Log.w(LOGTAG, "Could not start crash watch service", t);
        }

        try {
            // Even without a stale marker (post-UI native death), surface any
            // crash-reason process exit recorded since last seen.
            reportNewCrashExits(appContext);
        } catch (Throwable t) {
            Log.w(LOGTAG, "Exit-history check failed", t);
        }

        try {
            mirrorReportsToUserDir(appContext);
        } catch (Throwable t) {
            Log.w(LOGTAG, "Report mirroring failed", t);
        }
        surfaceReportsIfAny(appContext);
    }

    /**
     * True when running inside the :reporter watchdog process.
     */
    static boolean isReporterProcess(Context context) {
        try {
            String expected = context.getPackageName() + REPORTER_SUFFIX;
            String name = null;
            if (Build.VERSION.SDK_INT >= 28) {
                name = android.app.Application.getProcessName();
            } else {
                FileInputStream in = null;
                try {
                    in = new FileInputStream("/proc/self/cmdline");
                    byte[] buf = new byte[256];
                    int n = in.read(buf);
                    if (n > 0) {
                        int len = 0;
                        while (len < n && buf[len] != 0) {
                            len++;
                        }
                        name = new String(buf, 0, len, "UTF-8");
                    }
                } finally {
                    if (in != null) {
                        try { in.close(); } catch (Throwable t) { }
                    }
                }
            }
            return expected.equals(name);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * On launch, convert any crash-reason ApplicationExitInfo record newer
     * than the last-seen watermark into an exit-*.txt report. Covers deaths
     * that happen after the session marker was cleanly cleared.
     */
    private static void reportNewCrashExits(Context context) {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        ActivityManager am =
            (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<?> exits =
            am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 10);
        if (exits == null || exits.isEmpty()) {
            return;
        }
        long seen = 0;
        String w = readSmallFile(new File(getReportDir(context), EXIT_SEEN_FILE));
        if (w != null) {
            try {
                seen = Long.parseLong(w.trim());
            } catch (NumberFormatException e) {
                // Treat as no watermark.
            }
        }
        long latest = seen;
        for (Object o : exits) {
            android.app.ApplicationExitInfo info = (android.app.ApplicationExitInfo) o;
            if (info.getTimestamp() > latest) {
                latest = info.getTimestamp();
            }
            if (info.getTimestamp() <= seen || isBenignExitReason(info.getReason())) {
                continue;
            }
            writeExitReport(context, info);
        }
        writeSmallFile(new File(getReportDir(context), EXIT_SEEN_FILE),
                       String.valueOf(latest));
    }

    static boolean isBenignExitReason(int reason) {
        switch (reason) {
            case 1:  // EXIT_SELF
            case 3:  // LOW_MEMORY
            case 8:  // PERMISSION_CHANGE
            case 9:  // EXCESSIVE_RESOURCE_USAGE
            case 10: // USER_REQUESTED
            case 11: // USER_STOPPED
            case 14: // FREEZER
            case 15: // PACKAGE_STATE
            case 16: // PACKAGE_UPDATED
                return true;
            default:
                return false;
        }
    }

    /**
     * Report file for a process exit recorded by the system.
     */
    static void writeExitReport(Context context, android.app.ApplicationExitInfo info) {
        StringBuilder body = new StringBuilder();
        appendHeader(context, body);
        body.append('\n');
        body.append("=== Process exit recorded by the system ===\n");
        body.append("Process: ").append(info.getProcessName())
            .append(" pid=").append(info.getPid()).append('\n')
            .append("Time: ").append(iso(info.getTimestamp())).append('\n')
            .append("Reason: ").append(info.getReason())
            .append('(').append(exitReasonName(info.getReason())).append(')')
            .append(" status=").append(info.getStatus())
            .append(" importance=").append(info.getImportance()).append('\n');
        String desc = info.getDescription();
        if (desc != null) {
            body.append("Description: ").append(desc).append('\n');
        }
        String trace = readTraceStream(info);
        if (trace != null && trace.length() > 0) {
            body.append("\n=== Trace / tombstone ===\n");
            body.append(trace).append('\n');
        }
        body.append('\n');
        body.append("=== Logcat tail ===\n");
        body.append(readLogcat());
        File report = writeReport(context, "exit", body.toString());
        if (report != null) {
            try {
                mirrorReportToUserDir(context, report);
            } catch (Throwable t) {
                // Best effort.
            }
        }
    }

    /**
     * Called when real UI is up (first activity resume): the session marker is
     * removed so a later exit is not blamed on startup failure.
     */
    public static void markUiReady(final Context context) {
        final Context appContext = applicationOrSelf(context);
        new Thread(new Runnable() {
            @Override
            public void run() {
                // A second, late surfacing attempt: the attach-time
                // startActivity can lose if the process dies before the
                // activity transaction lands, so retry once real UI exists.
                // The watermark suppresses duplicates.
                try {
                    ensureOutermost(appContext);
                    surfaceReportsIfAny(appContext);
                } catch (Throwable t) {
                    // Never propagate.
                }
                try {
                    new File(getReportDir(appContext),
                             MARKER_FILE).delete();
                    deleteSharedMarker(appContext);
                } catch (Throwable t) {
                    // Never propagate.
                }
            }
        }).start();
    }

    /**
     * Gecko's CrashHandler registers itself later (when the Gecko thread
     * starts) and becomes the outermost handler; its slow reporting path can
     * then lose a race to a concurrent native crash before our write happens.
     * Re-wrap whenever we get the chance so our fast head-write runs first.
     */
    private static synchronized void ensureOutermost(Context context) {
        Thread.UncaughtExceptionHandler previous =
            Thread.getDefaultUncaughtExceptionHandler();
        if (!(previous instanceof AppCrashLogger)) {
            Thread.setDefaultUncaughtExceptionHandler(
                new AppCrashLogger(applicationOrSelf(context), previous));
        }
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
            try {
                if (report != null) {
                    mirrorReportToUserDir(mContext, report);
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
     * All report files, oldest first; never null.
     */
    public static File[] listReports(Context context) {
        File[] files = getReportDir(applicationOrSelf(context)).listFiles();
        java.util.ArrayList<File> reports = new java.util.ArrayList<File>();
        if (files != null) {
            for (File f : files) {
                if (isReportName(f.getName())) {
                    reports.add(f);
                }
            }
        }
        File[] result = reports.toArray(new File[reports.size()]);
        Arrays.sort(result, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        return result;
    }

    /**
     * Newest report file (crash-*.txt or startup-*.txt), or null.
     */
    public static File latestReport(Context context) {
        File[] reports = listReports(context);
        return reports.length == 0 ? null : reports[reports.length - 1];
    }

    static boolean isReportName(String name) {
        return (name.startsWith("crash-") || name.startsWith("startup-")
                || name.startsWith("watch-") || name.startsWith("exit-"))
            && name.endsWith(".txt");
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

    /**
     * Path the user can browse to, for display in reports/UI.
     */
    static String userVisibleDescription(Context context) {
        if (Build.VERSION.SDK_INT >= 29) {
            return "Downloads/" + DOWNLOADS_SUBDIR + "/";
        }
        File ext = context.getExternalFilesDir(REPORT_DIR);
        return ext != null ? ext.getAbsolutePath()
                           : context.getFilesDir() + "/" + REPORT_DIR;
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

    private static void appendDiagnostics(Context context, StringBuilder body) {
        body.append('\n');
        body.append("=== Process exit history ===\n");
        appendExitInfo(context, body);
        body.append('\n');
        body.append("=== Logcat tail ===\n");
        body.append(readLogcat());
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
        File report = writeReport(context, "startup", body.toString());
        if (report != null) {
            try {
                mirrorReportToUserDir(context, report);
            } catch (Throwable t) {
                // Best effort.
            }
        }
    }

    static void appendHeader(Context context, StringBuilder body) {
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

    /**
     * ApplicationExitInfo (API 30+): reason codes and, for native crashes, the
     * system tombstone for our own process. Unavailable/absent on older APIs.
     */
    static void appendExitInfo(Context context, StringBuilder body) {
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

    static String exitReasonName(int reason) {
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

    static String readTraceStream(android.app.ApplicationExitInfo info) {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        java.io.InputStream in = null;
        try {
            in = info.getTraceInputStream();
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && raw.size() < MAX_TOMBSTONE_BYTES) {
                raw.write(buf, 0, n);
            }
            byte[] bytes = raw.toByteArray();
            if (looksBinary(bytes)) {
                // Android 14+ stores tombstones as protobuf; keep the
                // printable runs (signal, symbols, lib paths) readable.
                return "(binary tombstone — printable strings extracted)\n"
                       + extractPrintable(bytes);
            }
            return new String(bytes, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable t) { }
            }
        }
    }

    private static boolean looksBinary(byte[] bytes) {
        int check = Math.min(bytes.length, 2048);
        int bad = 0;
        for (int i = 0; i < check; i++) {
            byte b = bytes[i];
            if (b != '\n' && b != '\r' && b != '\t' && (b < 0x20 || b > 0x7e)) {
                bad++;
            }
        }
        return check > 0 && bad * 10 > check;
    }

    private static String extractPrintable(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length / 2);
        StringBuilder run = new StringBuilder();
        for (byte b : bytes) {
            if (b >= 0x20 && b <= 0x7e) {
                run.append((char) b);
            } else {
                if (run.length() >= 4) {
                    out.append(run).append('\n');
                }
                run.setLength(0);
            }
        }
        if (run.length() >= 4) {
            out.append(run);
        }
        return out.toString();
    }

    /**
     * Apps may read their own log records without any permission since API 16;
     * other buffers simply come back empty. Bounded tail only.
     */
    static String readLogcat() {
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

    // ---------- user-reachable copies ----------

    private static void mirrorReportsToUserDir(Context context) {
        for (File report : listReports(context)) {
            mirrorReportToUserDir(context, report);
        }
    }

    static void mirrorReportToUserDir(Context context, File report) {
        // API 29+: MediaStore Downloads — no permission needed, universally
        // reachable from Files, Termux (with storage setup), MTP, etc.
        if (Build.VERSION.SDK_INT >= 29) {
            exportToDownloads(context, report);
        }
        // Any API: app-specific external files dir, browsable via file
        // managers and adb as Android/data/<pkg>/files/crash-reports/.
        try {
            File ext = context.getExternalFilesDir(REPORT_DIR);
            if (ext != null) {
                File out = new File(ext, report.getName());
                if (!out.exists() || out.length() != report.length()) {
                    ext.mkdirs();
                    copyFile(report, out);
                }
            }
        } catch (Throwable t) {
            // External storage absent or unwritable; internal copy stands.
        }
    }

    private static final String SHARED_MARKER_NAME = "newmoon-session-started.txt";

    private static void writeSharedMarker(Context context, String text) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver cr = context.getContentResolver();
                deleteDownloadsRow(cr, SHARED_MARKER_NAME);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, SHARED_MARKER_NAME);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                           Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOADS_SUBDIR);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream out = null;
                    try {
                        out = cr.openOutputStream(uri);
                        out.write(text.getBytes("UTF-8"));
                    } finally {
                        if (out != null) {
                            try { out.close(); } catch (Throwable t) { }
                        }
                    }
                }
                return;
            } catch (Throwable t) {
                // Fall through to the legacy path.
            }
        }
        // API <29 or MediaStore failure: plain file in public Downloads
        // (Fennec declares WRITE_EXTERNAL_STORAGE).
        try {
            File dir = new File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DOWNLOADS_SUBDIR);
            dir.mkdirs();
            writeSmallFile(new File(dir, SHARED_MARKER_NAME), text);
        } catch (Throwable t) {
            // External storage absent; internal marker still stands.
        }
    }

    private static void deleteSharedMarker(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                deleteDownloadsRow(context.getContentResolver(),
                                   SHARED_MARKER_NAME);
            }
            File legacy = new File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS),
                DOWNLOADS_SUBDIR + "/" + SHARED_MARKER_NAME);
            legacy.delete();
        } catch (Throwable t) {
            // Best effort.
        }
    }

    private static void deleteDownloadsRow(ContentResolver cr, String name) {
        try {
            cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                      MediaStore.Downloads.DISPLAY_NAME + "=?",
                      new String[] { name });
        } catch (Throwable t) {
            // Best effort.
        }
    }

    private static void exportToDownloads(Context context, File report) {
        try {
            ContentResolver cr = context.getContentResolver();
            String name = "newmoon-" + report.getName();

            // Skip if a row with the same name already exists.
            android.database.Cursor c = null;
            try {
                c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                             new String[] { MediaStore.Downloads._ID },
                             MediaStore.Downloads.DISPLAY_NAME + "=?",
                             new String[] { name }, null);
                if (c != null && c.moveToFirst()) {
                    return;
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                       Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOADS_SUBDIR);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return;
            }
            OutputStream out = null;
            InputStream in = null;
            try {
                out = cr.openOutputStream(uri);
                in = new FileInputStream(report);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Throwable t) { }
                }
                if (out != null) {
                    try { out.close(); } catch (Throwable t) { }
                }
            }
        } catch (Throwable t) {
            Log.w(LOGTAG, "MediaStore export failed", t);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    // ---------- next-launch surfacing ----------

    static void surfaceReportsIfAny(Context context) {
        try {
            File latest = latestReport(context);
            if (latest == null) {
                return;
            }
            Intent intent = new Intent(context, CrashReportViewerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Notification first: it survives process death, carries the head
            // of the report inline (expandable BigText), and taps through to
            // the viewer. Needs POST_NOTIFICATIONS on API 33+ so it is never
            // the only surface.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pending = PendingIntent.getActivity(
                context, 0, intent, flags);
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
                .setContentTitle("New Moon crashed: " + latest.getName())
                .setContentText("Saved to " + userVisibleDescription(context)
                        + " — tap to view or share.")
                .setStyle(new Notification.BigTextStyle()
                        .bigText(reportHead(latest)))
                .setContentIntent(pending)
                .setAutoCancel(true);
            nm.notify(NOTIFICATION_ID, builder.build());

            // Then auto-open the in-app viewer for reports never shown. It
            // runs inside this process, so a fast startup crash can still
            // kill it — hence the mirrored files and notification above.
            File watermark = new File(getReportDir(context), SURFACED_FILE);
            long shown = 0;
            try {
                String w = readSmallFile(watermark);
                if (w != null) {
                    shown = Long.parseLong(w.trim());
                }
            } catch (Throwable t) {
                // No usable watermark.
            }
            if (latest.lastModified() > shown) {
                try {
                    context.startActivity(intent);
                    writeSmallFile(watermark,
                        String.valueOf(latest.lastModified()));
                } catch (Throwable t) {
                    Log.w(LOGTAG, "Could not open crash report viewer", t);
                }
            }
        } catch (Throwable t) {
            Log.w(LOGTAG, "Could not surface crash report", t);
        }
    }

    private static String reportHead(File report) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(report);
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

    // ---------- small helpers ----------

    static File writeReport(Context context, String prefix, String text) {
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

    static void writeSmallFile(File file, String text) {
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

    static String readSmallFile(File file) {
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

    static String nowIso() {
        return iso(System.currentTimeMillis());
    }

    static String iso(long epochMs) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(epochMs));
    }
}
