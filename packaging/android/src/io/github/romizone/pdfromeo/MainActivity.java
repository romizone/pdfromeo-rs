package io.github.romizone.pdfromeo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pdfromeo for Android: the browser build in a WebView.
 *
 * The site (apps/pdfcraft-web, built with trunk) is packed under assets/web and served from a
 * virtual https origin, so ES modules and the wasm fetch work as on the web. A document opened
 * from another app is served at __intent/<n>/<name> and handed to the page through ?file=.
 * Saves arrive through the PdfromeoAndroid bridge (see editing::download in pdfcraft-ui-egui)
 * and are written wherever the user picks in the system's Save dialog.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST + "/";
    private static final String INTENT_PREFIX = "/__intent/";
    private static final int REQ_OPEN = 1;
    private static final int REQ_SAVE = 2;

    private WebView web;
    private ValueCallback<Uri[]> pendingOpen;
    private byte[] pendingSave;
    private volatile Uri intentDocument;
    private int intentCount;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF19191C);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // Keep the canvas clear of the status bar, navigation bar and keyboard (edge-to-edge on Android 15).
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "PdfromeoAndroid");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());

        Uri doc = documentOf(getIntent());
        if (state != null) {
            web.restoreState(state);
        }
        if (state == null || web.getUrl() == null) {
            web.loadUrl(startUrl(doc));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri doc = documentOf(intent);
        if (doc == null) {
            return;
        }
        String name = displayName(doc);
        new AlertDialog.Builder(this)
                .setTitle("Open " + name + "?")
                .setMessage("Pdfromeo restarts to open it. Unsaved changes in open documents will be lost.")
                .setPositiveButton("Open", (d, w) -> web.loadUrl(startUrl(doc)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Quit Pdfromeo?")
                .setMessage("Unsaved changes in open documents will be lost.")
                .setPositiveButton("Quit", (d, w) -> finish())
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    /** The page URL, with ?file= pointing at the incoming document when there is one. */
    private String startUrl(Uri doc) {
        if (doc == null) {
            intentDocument = null;
            return ORIGIN + "index.html";
        }
        intentDocument = doc;
        intentCount++;
        return ORIGIN + "index.html?file=__intent/" + intentCount + "/" + safeName(displayName(doc));
    }

    private static Uri documentOf(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            return intent.getData();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            return stream instanceof Uri ? (Uri) stream : null;
        }
        return null;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (RuntimeException e) {
            // Some providers refuse the query; fall back to the last path segment.
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        return name == null || name.isEmpty() ? "document.pdf" : name;
    }

    /** A path segment the page shows as the tab name: ASCII letters, digits, '.', '-' and '_'. */
    private static String safeName(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length() && b.length() < 120; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            b.append(ok ? c : '_');
        }
        String s = b.toString();
        if (!s.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            s = s + ".pdf";
        }
        return s;
    }

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html");
        MIME.put("js", "text/javascript");
        MIME.put("wasm", "application/wasm");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("ico", "image/x-icon");
    }

    private static String mimeOf(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String m = MIME.get(ext);
        if (m == null) {
            m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
        return m == null ? "application/octet-stream" : m;
    }

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!HOST.equals(url.getHost()) || !"https".equals(url.getScheme())) {
                return null;
            }
            String path = url.getPath() == null ? "/" : url.getPath();
            try {
                if (path.startsWith(INTENT_PREFIX)) {
                    Uri doc = intentDocument;
                    InputStream in = doc == null ? null : getContentResolver().openInputStream(doc);
                    if (in == null) {
                        return notFound();
                    }
                    return new WebResourceResponse("application/pdf", null, in);
                }
                if (path.equals("/")) {
                    path = "/index.html";
                }
                if (path.contains("..")) {
                    return notFound();
                }
                InputStream in = getAssets().open("web" + path);
                WebResourceResponse r = new WebResourceResponse(mimeOf(path), null, in);
                if (path.endsWith(".html")) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-cache");
                    r.setResponseHeaders(headers);
                }
                return r;
            } catch (Exception e) {
                return notFound();
            }
        }

        private WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), new java.io.ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (HOST.equals(url.getHost())) {
                return false;
            }
            // Links in documents and Help open in the user's browser, never inside the app.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(MainActivity.this, "No app can open " + url, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (pendingOpen != null) {
                pendingOpen.onReceiveValue(null);
            }
            pendingOpen = callback;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(i, REQ_OPEN);
            } catch (ActivityNotFoundException e) {
                pendingOpen = null;
                callback.onReceiveValue(null);
                return false;
            }
            return true;
        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Leave Pdfromeo?")
                    .setMessage("Unsaved changes in open documents will be lost.")
                    .setPositiveButton("Leave", (d, w) -> result.confirm())
                    .setNegativeButton("Stay", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }
    }

    /** What the page calls; runs on a WebView background thread. */
    private class Bridge {
        @JavascriptInterface
        public void save(String name, String base64) {
            byte[] bytes;
            try {
                bytes = Base64.decode(base64, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                return;
            }
            String title = name == null || name.isEmpty() ? "document.pdf" : name;
            runOnUiThread(() -> {
                pendingSave = bytes;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mimeOf(title));
                i.putExtra(Intent.EXTRA_TITLE, title);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (ActivityNotFoundException e) {
                    pendingSave = null;
                    Toast.makeText(MainActivity.this, "No app can save files", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_OPEN) {
            ValueCallback<Uri[]> cb = pendingOpen;
            pendingOpen = null;
            if (cb == null) {
                return;
            }
            Uri[] picked = null;
            if (result == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    picked = new Uri[clip.getItemCount()];
                    for (int k = 0; k < clip.getItemCount(); k++) {
                        picked[k] = clip.getItemAt(k).getUri();
                    }
                } else if (data.getData() != null) {
                    picked = new Uri[] {data.getData()};
                }
            }
            cb.onReceiveValue(picked);
        } else if (request == REQ_SAVE) {
            byte[] bytes = pendingSave;
            pendingSave = null;
            if (result != RESULT_OK || data == null || data.getData() == null || bytes == null) {
                return;
            }
            try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (out == null) {
                    throw new java.io.IOException("no output stream");
                }
                out.write(bytes);
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't save: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
