/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.io.File;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.Toast;

/**
 * Headless activity that shares the newest saved crash report through a
 * FileProvider-backed ACTION_SEND chooser. Safe to invoke even when the main
 * browser UI never came up.
 */
public class CrashReportShareActivity extends Activity {
    private static final String LOGTAG = "CrashReportShare";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            File report = AppCrashLogger.latestReport(this);
            if (report == null) {
                Toast.makeText(this, "No crash report found.", Toast.LENGTH_LONG).show();
                return;
            }
            share(report);
        } catch (Throwable t) {
            Log.e(LOGTAG, "Failed to share crash report", t);
            Toast.makeText(this, "Could not open the crash report.", Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }

    private void share(File report) {
        Uri uri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", report);

        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "New Moon crash report")
                .putExtra(Intent.EXTRA_TEXT,
                        "New Moon crash report: " + report.getName())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 16) {
            send.setClipData(ClipData.newRawUri("crash report", uri));
        }

        try {
            startActivity(Intent.createChooser(send, "Open crash report with"));
            return;
        } catch (ActivityNotFoundException e) {
            // Fall through to a direct ACTION_VIEW attempt.
        } catch (Throwable t) {
            Log.e(LOGTAG, "Chooser failed", t);
        }

        try {
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= 16) {
                view.setClipData(ClipData.newRawUri("crash report", uri));
            }
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this,
                    "No app can view the report. It is saved at "
                            + report.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.e(LOGTAG, "ACTION_VIEW failed", t);
            Toast.makeText(this,
                    "No app can view the report. It is saved at "
                            + report.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
