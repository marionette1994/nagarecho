package jp.marionette.nagarecho;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.provider.CalendarContract;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.OutputStream;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_SAVE = 2;
    private static final int REQ_LINK = 3;
    private static final int REQ_PERM_CAL = 10;
    private long calFrom;
    private long calTo;
    private static final String PREF = "nagarecho";
    private static final String PREF_URI = "driveUri";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("file:///android_asset/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_PICK);
                } catch (Exception e) {
                    fileCallback = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }
        });

        if (savedInstanceState == null) {
            web.loadUrl("file:///android_asset/index.html");
        } else {
            web.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.evaluateJavascript("window.onAndroidPause && window.onAndroidPause()", null);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (!"true".equals(v)) moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK) {
            if (fileCallback == null) return;
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                r = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(r);
            fileCallback = null;
        } else if (req == REQ_SAVE) {
            String content = pendingContent;
            pendingContent = null;
            if (res != RESULT_OK || data == null || data.getData() == null || content == null) return;
            try {
                OutputStream os = getContentResolver().openOutputStream(data.getData());
                os.write(content.getBytes("UTF-8"));
                os.close();
                msg("保存しました");
            } catch (Exception e) {
                msg("保存に失敗しました");
            }
        } else if (req == REQ_LINK) {
            String content = pendingContent;
            pendingContent = null;
            if (res != RESULT_OK || data == null || data.getData() == null || content == null) return;
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                // 永続権限を出さない保存先でも、今回の書き込みは試す
            }
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(PREF_URI, uri.toString()).apply();
            writeAsync(uri, content);
        }
    }

    private void writeAsync(final Uri uri, final String content) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String[] modes = {"wt", "rwt", "w"};
                for (int i = 0; i < modes.length && !ok; i++) {
                    try {
                        OutputStream os = getContentResolver().openOutputStream(uri, modes[i]);
                        os.write(content.getBytes("UTF-8"));
                        os.close();
                        ok = true;
                    } catch (Exception e) {
                        ok = false;
                    }
                }
                final boolean result = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript("window.onAndroidSync && window.onAndroidSync(" + result + ")", null);
                    }
                });
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_PERM_CAL) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            queryCalendar();
        } else {
            calReply("{\"ok\":false,\"error\":\"denied\"}");
        }
    }

    private void calReply(final String json) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("window.onAndroidCal && window.onAndroidCal(" + json + ")", null);
            }
        });
    }

    // 端末に同期されているカレンダーから、指定期間の予定とカレンダー一覧を読む
    private void queryCalendar() {
        final long from = calFrom;
        final long to = calTo;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject out = new JSONObject();
                    JSONArray cals = new JSONArray();
                    Cursor c = getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                            new String[]{CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                                    CalendarContract.Calendars.ACCOUNT_NAME}, null, null, null);
                    if (c != null) {
                        while (c.moveToNext()) {
                            JSONObject o = new JSONObject();
                            o.put("id", c.getLong(0));
                            o.put("name", c.getString(1) == null ? "" : c.getString(1));
                            o.put("account", c.getString(2) == null ? "" : c.getString(2));
                            cals.put(o);
                        }
                        c.close();
                    }
                    JSONArray evs = new JSONArray();
                    Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
                    ContentUris.appendId(b, from);
                    ContentUris.appendId(b, to);
                    Cursor e = getContentResolver().query(b.build(),
                            new String[]{CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_ID,
                                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                                    CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.STATUS},
                            null, null, CalendarContract.Instances.BEGIN + " ASC");
                    if (e != null) {
                        while (e.moveToNext()) {
                            JSONObject o = new JSONObject();
                            o.put("id", e.getLong(0));
                            o.put("title", e.getString(1) == null ? "" : e.getString(1));
                            o.put("begin", e.getLong(2));
                            o.put("end", e.getLong(3));
                            o.put("allDay", e.getInt(4) != 0);
                            o.put("calId", e.getLong(5));
                            o.put("calName", e.getString(6) == null ? "" : e.getString(6));
                            o.put("self", e.getInt(7));
                            o.put("status", e.getInt(8));
                            evs.put(o);
                        }
                        e.close();
                    }
                    out.put("ok", true);
                    out.put("calendars", cals);
                    out.put("events", evs);
                    calReply(out.toString());
                } catch (Exception ex) {
                    calReply("{\"ok\":false,\"error\":\"error\"}");
                }
            }
        }).start();
    }

    private String linkedUri() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getString(PREF_URI, "");
    }

    private void msg(String m) {
        web.evaluateJavascript("window.onAndroidMsg && window.onAndroidMsg('" + m + "')", null);
    }

    private class Bridge {
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String content) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingContent = content;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(mime);
                    i.putExtra(Intent.EXTRA_TITLE, name);
                    try {
                        startActivityForResult(i, REQ_SAVE);
                    } catch (Exception e) {
                        pendingContent = null;
                        msg("保存先を開けませんでした");
                    }
                }
            });
        }

        @JavascriptInterface
        public void linkFile(final String name, final String mime, final String content) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingContent = content;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(mime);
                    i.putExtra(Intent.EXTRA_TITLE, name);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        startActivityForResult(i, REQ_LINK);
                    } catch (Exception e) {
                        pendingContent = null;
                        msg("保存先を開けませんでした");
                    }
                }
            });
        }

        @JavascriptInterface
        public void syncFile(final String content) {
            final String u = linkedUri();
            if (u.length() == 0) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript("window.onAndroidSync && window.onAndroidSync(false)", null);
                    }
                });
                return;
            }
            writeAsync(Uri.parse(u), content);
        }

        @JavascriptInterface
        public String linkName() {
            String u = linkedUri();
            if (u.length() == 0) return "";
            String name = "流帖_backup.json";
            try {
                Cursor c = getContentResolver().query(Uri.parse(u), new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (c != null) {
                    if (c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
                    c.close();
                }
            } catch (Exception e) {
                // 名前が取れなくても連携は有効として扱う
            }
            return name;
        }

        @JavascriptInterface
        public void unlink() {
            String u = linkedUri();
            if (u.length() > 0) {
                try {
                    getContentResolver().releasePersistableUriPermission(Uri.parse(u),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception e) {
                    // 既に失効している場合は何もしない
                }
            }
            getSharedPreferences(PREF, MODE_PRIVATE).edit().remove(PREF_URI).apply();
        }

        @JavascriptInterface
        public void calRequest(final String from, final String to) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        calFrom = Long.parseLong(from);
                        calTo = Long.parseLong(to);
                    } catch (NumberFormatException e) {
                        calReply("{\"ok\":false,\"error\":\"error\"}");
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                        queryCalendar();
                    } else {
                        requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQ_PERM_CAL);
                    }
                }
            });
        }

        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "共有"));
                }
            });
        }

        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("流帖", text));
                }
            });
        }
    }
}
