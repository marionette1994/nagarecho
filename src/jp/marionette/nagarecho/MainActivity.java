package jp.marionette.nagarecho;

import android.app.Activity;
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

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_SAVE = 2;
    private static final int REQ_LINK = 3;
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
