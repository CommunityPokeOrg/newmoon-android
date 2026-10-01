/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.palemoon.crashhelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Simple report viewer: shows a text document (SAF uri or file) and offers
 * Copy and Share-as-text — no FileProvider needed since we share the text
 * itself rather than the file.
 */
public class ReportViewActivity extends Activity {
    static final String EXTRA_NAME = "name";

    private String mText = "";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        Uri uri = getIntent().getData();
        mText = readAll(uri);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density + 0.5f);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(name != null ? name : "(report)");
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(mText);
        body.setTextSize(11);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons);
        addButton(buttons, "Copy", new Runnable() {
            public void run() { copy(); }
        });
        addButton(buttons, "Share", new Runnable() {
            public void run() { share(); }
        });
        addButton(buttons, "Close", new Runnable() {
            public void run() { finish(); }
        });
        setContentView(root);
    }

    private void addButton(LinearLayout parent, String label, final Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
        parent.addView(b, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    private String readAll(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return "(could not open report)";
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 512 * 1024) {
                bos.write(buf, 0, n);
                total += n;
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "(failed to read report: " + t + ")";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable t) { }
            }
        }
    }

    private void copy() {
        ClipboardManager cm =
            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("crash report", mText));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, mText);
        try {
            startActivity(Intent.createChooser(send, "Share crash report"));
        } catch (Throwable t) {
            Toast.makeText(this, "No app can share text", Toast.LENGTH_SHORT)
                 .show();
        }
    }
}
