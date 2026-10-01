/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.palemoon.crashhelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Standalone crash-report companion for New Moon (org.palemoon.community).
 *
 * Cross-app reality on modern Android: this app CANNOT read the other app's
 * ApplicationExitInfo (package-scoped), its internal files, or its logcat
 * (READ_LOGS is development-only, grantable via adb). What works reliably:
 *   - New Moon mirrors every report plus a live "session-started" marker
 *     into the shared Download/NewMoon/ folder.
 *   - We read that folder either directly (when storage access is granted)
 *     or through a persisted SAF document-tree grant.
 *   - A leftover "newmoon-session-started" file means the last New Moon run
 *     died before its UI loaded — detected without any report at all.
 *   - Optional launch monitor: with READ_LOGS granted via adb, we launch
 *     New Moon and capture the logcat stream ourselves.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "crashhelper";
    private static final String KEY_TREE = "tree_uri";
    private static final String NM_PACKAGE = "org.palemoon.community";
    private static final String MARKER_PREFIX = "newmoon-session-started";
    private static final int REQ_TREE = 41;
    private static final int REQ_STORAGE = 42;

    private TextView mStatus;
    private ListView mList;
    private final List<Entry> mEntries = new ArrayList<Entry>();

    private static final class Entry {
        final String name;
        final long modified;
        final Uri uri;
        Entry(String name, long modified, Uri uri) {
            this.name = name;
            this.modified = modified;
            this.uri = uri;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        mStatus = new TextView(this);
        mStatus.setTextSize(14);
        root.addView(mStatus);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons);
        addButton(buttons, "Folder access", new Runnable() {
            public void run() { requestTreeAccess(); }
        });
        addButton(buttons, "Refresh", new Runnable() {
            public void run() { refresh(); }
        });
        addButton(buttons, "Launch New Moon", new Runnable() {
            public void run() { launchNewMoon(); }
        });
        addButton(buttons, "Monitor launch", new Runnable() {
            public void run() { monitorLaunch(); }
        });

        mList = new ListView(this);
        root.addView(mList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Entry e = mEntries.get(pos);
                Intent i = new Intent(MainActivity.this, ReportViewActivity.class);
                i.setData(e.uri);
                i.putExtra(ReportViewActivity.EXTRA_NAME, e.name);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            }
        });

        setContentView(root);
        maybeRequestStorage();
        refresh();
    }

    private void addButton(LinearLayout parent, String label, final Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        parent.addView(b, lp);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void maybeRequestStorage() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32
                && checkSelfPermission(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE },
                REQ_STORAGE);
        }
    }

    private void requestTreeAccess() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (Build.VERSION.SDK_INT >= 26) {
            Uri downloads = Uri.parse(
                "content://com.android.externalstorage.documents/document/primary%3ADownload");
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloads);
        }
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Throwable t) {
            toast("No system file picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null) {
            Uri tree = data.getData();
            if (tree != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable t) {
                    // Older devices may not persist; still usable this session.
                }
                prefs().edit().putString(KEY_TREE, tree.toString()).apply();
            }
            refresh();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void refresh() {
        mEntries.clear();
        boolean staleMarker = false;
        String access = null;

        // 1) SAF tree grant (primary, works on every API level).
        String treeStr = prefs().getString(KEY_TREE, null);
        if (treeStr != null) {
            access = "folder grant";
            staleMarker = listViaSaf(Uri.parse(treeStr));
        }
        // 2) Direct filesystem read of public Downloads (needs storage
        //    permission on API 23-32; effectively blocked on API 33+).
        if (mEntries.isEmpty()) {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "NewMoon");
            if (dir.isDirectory()) {
                access = "storage permission";
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(MARKER_PREFIX)) {
                            staleMarker = true;
                            continue;
                        }
                        mEntries.add(new Entry(f.getName(), f.lastModified(),
                                               Uri.fromFile(f)));
                    }
                }
            }
        }

        Collections.sort(mEntries, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                return Long.compare(b.modified, a.modified);
            }
        });

        StringBuilder status = new StringBuilder();
        status.append(nmStatus());
        if (access == null) {
            status.append("\nNo access to Download/NewMoon yet — tap "
                          + "\"Folder access\" and pick the NewMoon folder "
                          + "inside Download. (Or grant storage permission.)");
        } else {
            status.append("\nReport source: ").append(access)
                  .append(" — ").append(mEntries.size()).append(" file(s).");
        }
        if (staleMarker) {
            status.append("\n\nUNCLEAN EXIT: New Moon's session marker is still "
                          + "present — its last run died before the UI loaded.");
        }
        mStatus.setText(status);

        List<String> labels = new ArrayList<String>();
        for (Entry e : mEntries) {
            labels.add(e.name);
        }
        mList.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels));
    }

    private boolean listViaSaf(Uri tree) {
        boolean stale = false;
        ContentResolver cr = getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree));
        Cursor c = null;
        try {
            c = cr.query(children,
                    new String[] { Document.COLUMN_DOCUMENT_ID,
                                   Document.COLUMN_DISPLAY_NAME,
                                   Document.COLUMN_LAST_MODIFIED },
                    null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String docId = c.getString(0);
                    String name = c.getString(1);
                    long mod = c.getLong(2);
                    if (name.startsWith(MARKER_PREFIX)) {
                        stale = true;
                        continue;
                    }
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                    mEntries.add(new Entry(name, mod, doc));
                }
            }
        } catch (Throwable t) {
            toast("Folder listing failed: " + t.getMessage());
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return stale;
    }

    private String nmStatus() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(NM_PACKAGE, 0);
            return "New Moon installed: v" + pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "New Moon is NOT installed (" + NM_PACKAGE + ")";
        }
    }

    private void launchNewMoon() {
        Intent i = getPackageManager().getLaunchIntentForPackage(NM_PACKAGE);
        if (i == null) {
            toast("New Moon is not installed");
            return;
        }
        try {
            startActivity(i);
        } catch (Throwable t) {
            toast("Launch failed: " + t.getMessage());
        }
    }

    private boolean hasReadLogs() {
        if (Build.VERSION.SDK_INT < 23) {
            return checkCallingOrSelfPermission("android.permission.READ_LOGS")
                   == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission("android.permission.READ_LOGS")
               == PackageManager.PERMISSION_GRANTED;
    }

    private void monitorLaunch() {
        if (!hasReadLogs()) {
            new AlertDialog.Builder(this)
                .setTitle("Logcat access not granted")
                .setMessage("Android does not let ordinary apps read another "
                    + "app's logs. One-time setup (no root):\n\n"
                    + "1) Install Termux, then: pkg install android-tools\n"
                    + "2) Settings → Developer options → Wireless debugging ON\n"
                    + "3) Tap \"Pair device with pairing code\"\n"
                    + "4) In Termux: adb pair 127.0.0.1:<port>\n"
                    + "5) Back on the Wireless debugging screen, connect with "
                    + "the OTHER port: adb connect 127.0.0.1:<port>\n"
                    + "6) Grant: adb shell pm grant " + getPackageName()
                    + " android.permission.READ_LOGS\n\n"
                    + "Then reopen this app and tap Monitor launch.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        if (!nmInstalled()) {
            toast("New Moon is not installed");
            return;
        }
        File out = new File(getFilesDir(),
            "logcat-" + System.currentTimeMillis() + ".txt");
        Intent i = getPackageManager().getLaunchIntentForPackage(NM_PACKAGE);
        try {
            startActivity(i);
        } catch (Throwable t) {
            toast("Launch failed: " + t.getMessage());
            return;
        }
        toast("Capturing logcat for up to 45 seconds…");
        LogcatMonitor.capture(this, NM_PACKAGE, out, 45000,
            new LogcatMonitor.Callback() {
                public void onDone(final File file, final int lines) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            toast("Capture finished: " + lines + " lines");
                            Intent v = new Intent(MainActivity.this,
                                                  ReportViewActivity.class);
                            v.setData(Uri.fromFile(file));
                            v.putExtra(ReportViewActivity.EXTRA_NAME,
                                       file.getName());
                            startActivity(v);
                        }
                    });
                }
            });
    }

    private boolean nmInstalled() {
        try {
            getPackageManager().getPackageInfo(NM_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
