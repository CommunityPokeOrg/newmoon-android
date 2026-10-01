/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.palemoon.crashhelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.regex.Pattern;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/**
 * Streams `logcat` (allowed only after an adb `pm grant` of READ_LOGS) into
 * a capture file for a bounded time, keeping lines that mention the watched
 * package plus every FATAL/crash/signal/SELinux line. Runs on a daemon
 * thread; the file is usable by the viewer when done.
 */
final class LogcatMonitor {
    interface Callback {
        void onDone(File file, int lines);
    }

    private static final Pattern KEEP = Pattern.compile(
        "palemoon|FATAL|crash_dump|DEBUG.*signal|libc.*signal|avc: denied|"
        + "AndroidRuntime|Process.*died|am_crash|ANR in",
        Pattern.CASE_INSENSITIVE);

    private LogcatMonitor() { }

    static void capture(final Context context, final String pkg,
                        final File out, final long maxMs,
                        final Callback callback) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Process process = null;
                BufferedReader reader = null;
                FileOutputStream fos = null;
                int lines = 0;
                try {
                    fos = new FileOutputStream(out);
                    fos.write(("# New Moon Crash Helper logcat capture — "
                               + new java.util.Date() + "\n").getBytes("UTF-8"));
                    process = Runtime.getRuntime().exec(new String[] {
                        "logcat", "-b", "main", "-b", "system", "-b", "crash",
                        "-v", "threadtime" });
                    reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                    long deadline = SystemClock.uptimeMillis() + maxMs;
                    String line;
                    while (SystemClock.uptimeMillis() < deadline
                           && (line = reader.readLine()) != null) {
                        if (line.contains(pkg) || KEEP.matcher(line).find()) {
                            fos.write((line + "\n").getBytes("UTF-8"));
                            lines++;
                        }
                    }
                } catch (Throwable tr) {
                    Log.w("LogcatMonitor", "capture failed", tr);
                    try {
                        if (fos != null) {
                            fos.write(("\n(capture error: " + tr + ")\n")
                                      .getBytes("UTF-8"));
                        }
                    } catch (Throwable t2) { }
                } finally {
                    if (reader != null) {
                        try { reader.close(); } catch (Throwable t2) { }
                    }
                    if (process != null) {
                        process.destroy();
                    }
                    if (fos != null) {
                        try { fos.close(); } catch (Throwable t2) { }
                    }
                }
                callback.onDone(out, lines);
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
