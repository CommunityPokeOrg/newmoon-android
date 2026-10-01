/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.io.File;
import java.io.FileInputStream;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Self-contained in-app crash report viewer. Built entirely in code (no
 * layout/theme resources) so it works even when Gecko UI never came up.
 * Shows the newest saved report with Share (FileProvider + chooser),
 * Copy-to-clipboard, and Delete actions; navigates between reports.
 */
public class CrashReportViewerActivity extends Activity {
    private static final String LOGTAG = "CrashReportViewer";

    private TextView mTitle;
    private TextView mBody;
    private TextView mNav;
    private File[] mReports = new File[0];
    private int mIndex;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("New Moon crash report");

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        mTitle = new TextView(this);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(mTitle);

        mNav = new TextView(this);
        mNav.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(mNav);

        ScrollView scroll = new ScrollView(this);
        mBody = new TextView(this);
        mBody.setTypeface(Typeface.MONOSPACE);
        mBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mBody.setTextIsSelectable(true);
        scroll.addView(mBody);
        LinearLayout.LayoutParams scrollParams =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        addButton(buttons, "Share", new View.OnClickListener() {
            @Override public void onClick(View v) { shareCurrent(); }
        });
        addButton(buttons, "Copy", new View.OnClickListener() {
            @Override public void onClick(View v) { copyCurrent(); }
        });
        addButton(buttons, "Delete all", new View.OnClickListener() {
            @Override public void onClick(View v) { deleteAll(); }
        });
        addButton(buttons, "Close", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(buttons);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        addButton(nav, "< Older", new View.OnClickListener() {
            @Override public void onClick(View v) { showReport(mIndex - 1); }
        });
        addButton(nav, "Newer >", new View.OnClickListener() {
            @Override public void onClick(View v) { showReport(mIndex + 1); }
        });
        root.addView(nav);

        setContentView(root);
        reload();
    }

    private void addButton(LinearLayout parent, String label,
                           View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(listener);
        parent.addView(b, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                                               value, getResources().getDisplayMetrics());
    }

    private void reload() {
        mReports = AppCrashLogger.listReports(this);
        if (mReports.length == 0) {
            mTitle.setText("No crash reports found.");
            mNav.setText("");
            mBody.setText("");
            return;
        }
        showReport(mReports.length - 1);
    }

    private void showReport(int index) {
        if (index < 0 || index >= mReports.length) {
            return;
        }
        mIndex = index;
        File report = mReports[mIndex];
        mTitle.setText(report.getName());
        StringBuilder meta = new StringBuilder();
        meta.append("Report ").append(mIndex + 1).append(" of ").append(mReports.length)
            .append("  ·  ").append(report.length()).append(" bytes\n")
            .append("Internal copy: ").append(report.getAbsolutePath()).append('\n')
            .append("User-visible copy: ")
            .append(AppCrashLogger.userVisibleDescription(this));
        mNav.setText(meta.toString());
        mBody.setText(readFile(report));
    }

    private String readFile(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 4 * 1024 * 1024)];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, "UTF-8") : "(empty report)";
        } catch (Throwable t) {
            return "Could not read " + f.getAbsolutePath() + ": " + t;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable t) { }
            }
        }
    }

    private void shareCurrent() {
        if (mReports.length == 0) {
            return;
        }
        File report = mReports[mIndex];
        try {
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
            startActivity(Intent.createChooser(send, "Open crash report with"));
        } catch (ActivityNotFoundException e) {
            viewFallback(report);
        } catch (Throwable t) {
            Log.e(LOGTAG, "Share failed", t);
            viewFallback(report);
        }
    }

    private void viewFallback(File report) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", report);
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= 16) {
                view.setClipData(ClipData.newRawUri("crash report", uri));
            }
            startActivity(view);
        } catch (Throwable t) {
            Toast.makeText(this,
                    "No app can view the report. Copy it or find it at "
                            + AppCrashLogger.userVisibleDescription(this),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void copyCurrent() {
        if (mReports.length == 0) {
            return;
        }
        try {
            ClipboardManager cm =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(
                    "New Moon crash report", readFile(mReports[mIndex])));
            Toast.makeText(this, "Report copied to clipboard.",
                           Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Copy failed: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private void deleteAll() {
        for (File f : mReports) {
            try {
                f.delete();
            } catch (Throwable t) {
                // Best effort.
            }
        }
        reload();
    }
}
