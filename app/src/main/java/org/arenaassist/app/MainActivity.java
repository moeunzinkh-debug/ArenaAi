package org.arenaassist.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.URLUtilCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ArenaAssist – a lightweight WebView wrapper around https://arena.ai/
 *
 * Architecture is modelled on duckAssist (fork of gptAssist), but every piece of
 * duck.ai‑specific logic (handoff URLs, SVG‑path selectors, voice chat, RTL resolver,
 * IndexedDB chat dumps) has been replaced with arena.ai equivalents or removed.
 */
public class MainActivity extends Activity {

    // ---------------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------------
    public static final String HOME_URL = "https://arena.ai/";
    public static final String PREFS_NAME = "arena_assist_prefs";
    public static final String FALLBACK_URL = "file:///android_asset/chats_viewer.html";
    private static final String[] ALLOWED_HOSTS = {
            "arena.ai", "www.arena.ai", "lmarena.ai", "www.lmarena.ai"
    };

    /** Extra used internally to pass shared text to be injected into the chat input. */
    public static final String EXTRA_INJECT_TEXT = "org.arenaassist.app.extra.INJECT_TEXT";

    private final String TAG = "ArenaAssist";

    private WebView chatWebView;
    private ProgressBar progressBar;
    private float currentZoomLevel = 100f;

    private ValueCallback<Uri[]> mUploadMessage;
    private final static int FILE_CHOOSER_REQUEST_CODE = 1;
    private final static int CAMERA_REQUEST_CODE = 2;
    private final static int CAMERA_PERMISSION_REQUEST_CODE = 124;
    private static final int DOWNLOAD_PERMISSION_REQUEST_CODE = 456;
    private Uri cameraImageUri = null;

    /** Text waiting to be injected into the arena.ai chat input once the page is ready. */
    private String pendingInjectText = null;
    private boolean pendingAutoFocus = false;
    private Uri pendingSharedFileUri = null;
    private static boolean isSafeMode = false;

    // Pending standard download (waiting for runtime permission)
    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadContentDisposition;
    private String pendingDownloadMimetype;
    private long pendingDownloadContentLength;

    // Pending blob download (waiting for runtime permission)
    private boolean isPendingBlob = false;
    private String pendingBlobData;
    private String pendingBlobMimetype;
    private String pendingBlobContentDisposition;
    private String pendingBlobCurrentUrl;

    // ---------------------------------------------------------------------------------
    // Safe JS evaluation (Safe Mode fallback – kept from duckAssist)
    // ---------------------------------------------------------------------------------
    private void safeEvaluateJavascript(WebView view, String script) {
        if (isSafeMode || view == null || script == null) return;
        try {
            view.evaluateJavascript(script, null);
        } catch (Throwable t) {
            Log.e(TAG, "Error executing custom tweak script, enabling Safe Mode fallback", t);
            isSafeMode = true;
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Script crash caught! Activating Safe Mode fallback...", Toast.LENGTH_LONG).show();
                if (chatWebView != null) {
                    chatWebView.loadUrl(HOME_URL);
                }
            });
        }
    }

    // ---------------------------------------------------------------------------------
    // JavaScript snippets
    // ---------------------------------------------------------------------------------

    /** Generic: remember every blob URL so DownloadListener can resolve blob: downloads. */
    private final String BLOB_JS = "(function() {" +
            "    if (window.blobHandlerInjected) return;" +
            "    window.blobHandlerInjected = true;" +
            "    window.blobMap = window.blobMap || new Map();" +
            "    const oC = URL.createObjectURL;" +
            "    URL.createObjectURL = function(b) {" +
            "        const u = oC.call(URL, b);" +
            "        if (b instanceof Blob) window.blobMap.set(u, b);" +
            "        console.log('Blob created: ' + u);" +
            "        return u;" +
            "    };" +
            "    console.log('Blob Handler Patch Active');" +
            "})();";

    /** Generic: route navigator.clipboard.writeText through the native clipboard. */
    private final String CLIPBOARD_JS = "(function() {" +
            "    if (window.clipboardPatchInjected) return;" +
            "    window.clipboardPatchInjected = true;" +
            "    if (navigator.clipboard) {" +
            "        navigator.clipboard.writeText = function(text) {" +
            "            return new Promise((resolve, reject) => {" +
            "                try {" +
            "                    Android.copyToClipboard(text);" +
            "                    resolve();" +
            "                } catch(e) {" +
            "                    reject(e);" +
            "                }" +
            "            });" +
            "        };" +
            "    }" +
            "})();";

    /**
     * arena.ai DOM helper library. Installs window.__arena with selector helpers that are
     * reused by AUTO_FOCUS_JS and INJECT_TEXT_JS.
     *
     * Selectors (arena.ai is a Next.js/React app instrumented with Sentry, so
     * data-sentry-* attributes are the most stable hooks; plain fallbacks follow):
     *   chat input     : textarea[data-sentry-element="AutoResizeTextarea"], textarea[name="text"],
     *                    textarea[placeholder], form textarea, [contenteditable="true"]
     *   send button    : form button[type="submit"], button[aria-label*="Send" i], button[aria-label*="Submit" i]
     *   new chat       : a[href="/"] / button whose text is "New Chat", button[aria-label*="New chat" i]
     *   mode switcher  : button[role="combobox"] or button whose text matches
     *                    Battle | Agent | Side by Side | Direct (Battle Mode / Agent Mode / Side by Side / Direct)
     */
    private final String ARENA_DOM_JS = "(function() {" +
            "  if (window.__arena) return;" +
            "  function visible(el) {" +
            "    if (!el) return false;" +
            "    var r = el.getBoundingClientRect();" +
            "    var s = window.getComputedStyle(el);" +
            "    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';" +
            "  }" +
            "  function firstVisible(selectors) {" +
            "    for (var i = 0; i < selectors.length; i++) {" +
            "      var list = document.querySelectorAll(selectors[i]);" +
            "      for (var j = 0; j < list.length; j++) {" +
            "        if (visible(list[j]) && !list[j].disabled) return list[j];" +
            "      }" +
            "    }" +
            "    return null;" +
            "  }" +
            "  function byText(selector, regex) {" +
            "    var list = document.querySelectorAll(selector);" +
            "    for (var i = 0; i < list.length; i++) {" +
            "      var t = (list[i].innerText || list[i].textContent || '').trim();" +
            "      if (regex.test(t) && visible(list[i])) return list[i];" +
            "    }" +
            "    return null;" +
            "  }" +
            "  window.__arena = {" +
            "    INPUT_SELECTORS: [" +
            "      'textarea[data-sentry-element=\"AutoResizeTextarea\"]'," +
            "      'textarea[name=\"text\"]'," +
            "      'form textarea'," +
            "      'textarea[placeholder]'," +
            "      'textarea'," +
            "      'div[contenteditable=\"true\"][role=\"textbox\"]'," +
            "      '[contenteditable=\"true\"]'," +
            "      'input[type=\"text\"]'" +
            "    ]," +
            "    SEND_SELECTORS: [" +
            "      'form button[type=\"submit\"]'," +
            "      'button[type=\"submit\"]'," +
            "      'button[aria-label*=\"Send\" i]'," +
            "      'button[aria-label*=\"Submit\" i]'," +
            "      'button[data-sentry-component*=\"Send\" i]'" +
            "    ]," +
            "    findInput: function() { return firstVisible(this.INPUT_SELECTORS); }," +
            "    findSendButton: function() { return firstVisible(this.SEND_SELECTORS); }," +
            "    findNewChatButton: function() {" +
            "      return firstVisible(['a[aria-label*=\"New chat\" i]', 'button[aria-label*=\"New chat\" i]'])" +
            "          || byText('a[href=\"/\"], a, button', /^new chat$/i);" +
            "    }," +
            "    findModeSwitcher: function() {" +
            "      return byText('button[role=\"combobox\"], button', /^(battle|agent|side by side|direct|auto)( mode)?$/i)" +
            "          || firstVisible(['button[role=\"combobox\"]']);" +
            "    }," +
            "    setInputValue: function(el, text) {" +
            "      if (!el) return false;" +
            "      el.focus();" +
            "      if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {" +
            "        var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;" +
            "        var desc = Object.getOwnPropertyDescriptor(proto, 'value');" +
            "        if (desc && desc.set) { desc.set.call(el, text); } else { el.value = text; }" +
            "        el.dispatchEvent(new Event('input', { bubbles: true }));" +
            "        el.dispatchEvent(new Event('change', { bubbles: true }));" +
            "        try { el.style.height = 'auto'; el.style.height = el.scrollHeight + 'px'; } catch (e) {}" +
            "        try { el.setSelectionRange(text.length, text.length); } catch (e) {}" +
            "      } else {" +
            "        el.textContent = text;" +
            "        try { document.execCommand('insertText', false, ''); } catch (e) {}" +
            "        el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));" +
            "        el.dispatchEvent(new Event('change', { bubbles: true }));" +
            "      }" +
            "      return true;" +
            "    }," +
            "    waitFor: function(finder, onFound, timeoutMs) {" +
            "      var done = false;" +
            "      var el = finder();" +
            "      if (el) { done = true; onFound(el); return; }" +
            "      var obs = null, timer = null, killer = null;" +
            "      function finish(found) {" +
            "        if (done) return;" +
            "        done = true;" +
            "        if (obs) obs.disconnect();" +
            "        if (timer) clearInterval(timer);" +
            "        if (killer) clearTimeout(killer);" +
            "        if (found) onFound(found);" +
            "      }" +
            "      function check() { var e = finder(); if (e) finish(e); }" +
            "      try {" +
            "        obs = new MutationObserver(check);" +
            "        obs.observe(document.documentElement, { childList: true, subtree: true });" +
            "      } catch (e) {}" +
            "      timer = setInterval(check, 400);" +
            "      killer = setTimeout(function() { console.log('__arena.waitFor timeout'); finish(null); }, timeoutMs || 10000);" +
            "    }" +
            "  };" +
            "  console.log('ArenaAssist DOM helpers installed');" +
            "})();";

    /** Focus the arena.ai chat input and open the soft keyboard (no site‑specific shortcuts). */
    private final String AUTO_FOCUS_JS = "(function() {" +
            "  if (!window.__arena) return;" +
            "  if (window.__arenaInjectPending) { console.log('Auto Focus: skipped, text injection pending'); return; }" +
            "  function focusInput(input) {" +
            "    try { input.focus(); input.click(); } catch (e) {}" +
            "    if (window.Android && window.Android.showSoftKeyboard) { window.Android.showSoftKeyboard(); }" +
            "  }" +
            "  window.__arena.waitFor(function() { return window.__arena.findInput(); }, function(input) {" +
            "    console.log('Auto Focus: input found');" +
            "    focusInput(input);" +
            "    setTimeout(function() { focusInput(input); }, 600);" +
            "  }, 10000);" +
            "})();";

    /**
     * Template: inject %TEXT% into the chat input. Retries with MutationObserver + interval
     * for ~10 s until the input appears. %TEXT% is replaced by a JSON string literal.
     */
    private final String INJECT_TEXT_JS_TEMPLATE = "(function() {" +
            "  if (!window.__arena) return;" +
            "  var text = %TEXT%;" +
            "  window.__arenaInjectPending = true;" +
            "  window.__arena.waitFor(function() { return window.__arena.findInput(); }, function(input) {" +
            "    var ok = window.__arena.setInputValue(input, text);" +
            "    console.log('ArenaAssist inject text: ' + ok);" +
            "    setTimeout(function() {" +
            "      var cur = (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') ? input.value : input.textContent;" +
            "      if (!cur || cur.indexOf(text.substring(0, Math.min(20, text.length))) === -1) {" +
            "        var again = window.__arena.findInput() || input;" +
            "        window.__arena.setInputValue(again, text);" +
            "      }" +
            "      window.__arenaInjectPending = false;" +
            "      if (window.Android && window.Android.showSoftKeyboard) { window.Android.showSoftKeyboard(); }" +
            "      if (window.Android && window.Android.onTextInjected) { window.Android.onTextInjected(true); }" +
            "    }, 500);" +
            "  }, 10000);" +
            "  setTimeout(function() {" +
            "    if (window.__arenaInjectPending) {" +
            "      window.__arenaInjectPending = false;" +
            "      if (window.Android && window.Android.onTextInjected) { window.Android.onTextInjected(false); }" +
            "    }" +
            "  }, 11000);" +
            "})();";

    /**
     * Generic (non site specific) dump of localStorage + IndexedDB used to feed the offline
     * chats_viewer.html fallback page. arena.ai keeps chats server side, so this is best effort.
     */
    private final String DUMP_CHATS_JS = "(function() {" +
            "  function dump() {" +
            "    var result = { source: location.host, localStorage: {} };" +
            "    try { for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); result.localStorage[k] = localStorage.getItem(k); } } catch (e) {}" +
            "    var getDbs = (window.indexedDB && window.indexedDB.databases) ? window.indexedDB.databases() : Promise.resolve([]);" +
            "    getDbs.then(async function(dbs) {" +
            "      for (var d = 0; d < (dbs || []).length; d++) {" +
            "        var name = dbs[d].name; if (!name) continue;" +
            "        result[name] = await new Promise(function(resolve) {" +
            "          try {" +
            "            var req = indexedDB.open(name);" +
            "            req.onerror = function() { resolve(null); };" +
            "            req.onsuccess = function(e) {" +
            "              var db = e.target.result; var stores = Array.from(db.objectStoreNames); var out = {}; var left = stores.length;" +
            "              if (!left) { db.close(); resolve(out); return; }" +
            "              stores.forEach(function(s) {" +
            "                try {" +
            "                  var r = db.transaction(s, 'readonly').objectStore(s).getAll();" +
            "                  r.onsuccess = function() { out[s] = r.result; if (--left === 0) { db.close(); resolve(out); } };" +
            "                  r.onerror = function() { if (--left === 0) { db.close(); resolve(out); } };" +
            "                } catch (err) { if (--left === 0) { db.close(); resolve(out); } }" +
            "              });" +
            "            };" +
            "          } catch (err) { resolve(null); }" +
            "        });" +
            "      }" +
            "      if (window.Android && window.Android.onChatsFetched) {" +
            "        try { window.Android.onChatsFetched(JSON.stringify(result)); } catch (e) { window.Android.onChatsFetched(JSON.stringify({error: e.toString()})); }" +
            "      }" +
            "    }).catch(function(err) {" +
            "      if (window.Android && window.Android.onChatsFetched) { window.Android.onChatsFetched(JSON.stringify({error: err.toString()})); }" +
            "    });" +
            "  }" +
            "  setTimeout(dump, 100);" +
            "})();";

    private String lastFetchedChatsJson = "{}";
    private android.app.Dialog chatsViewerDialog = null;
    private boolean isRequestingViewer = false;

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------
    public static boolean isAllowedHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String h : ALLOWED_HOSTS) {
            if (host.equals(h)) return true;
        }
        return false;
    }

    private boolean isArenaUrl(String url) {
        if (url == null) return false;
        try {
            return isAllowedHost(Uri.parse(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private void clearCacheData() {
        if (chatWebView != null) {
            chatWebView.clearCache(true);
        }
        Log.d(TAG, "Cache cleared.");
    }

    private Uri saveUriToTempFile(Uri uri, String extension) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            File tempFile = new File(getCacheDir(), "shared_file_" + System.currentTimeMillis() + extension);
            FileOutputStream fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.flush();
            fos.close();
            is.close();
            return Uri.fromFile(tempFile);
        } catch (Exception e) {
            Log.e(TAG, "Error saving shared file to temp file", e);
            return null;
        }
    }

    /**
     * Build a Chrome‑like user agent from the default WebView UA. reCAPTCHA (and Google
     * sign‑in) reject UAs that advertise the "wv" WebView token, so we strip it.
     */
    private String buildChromeLikeUserAgent(String defaultUa) {
        if (defaultUa == null || defaultUa.isEmpty()) {
            return "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";
        }
        String ua = defaultUa.replace("; wv", "");
        ua = ua.replaceAll("Version/\\d+\\.\\d+\\s*", "");
        return ua;
    }

    protected void setActivityTheme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
    }

    protected int getLayoutResourceId() {
        return R.layout.activity_main;
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------
    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        StrictMode.VmPolicy.Builder strictBuilder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(strictBuilder.build());

        setActivityTheme();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught error/exception intercepted. Activating Safe Mode fallback to pure arena.ai webview", throwable);
            isSafeMode = true;
            runOnUiThread(() -> {
                try {
                    if (chatWebView != null) {
                        chatWebView.loadUrl(HOME_URL);
                    }
                } catch (Throwable ignored) {}
            });
        });

        setContentView(getLayoutResourceId());

        progressBar = findViewById(R.id.progressBar);
        chatWebView = findViewById(R.id.chatWebView);

        View settingsButton = findViewById(R.id.settingsButton);
        if (settingsButton != null) {
            settingsButton.setOnClickListener(v -> showSettingsDialog());
            settingsButton.setOnLongClickListener(v -> {
                if (chatWebView != null) chatWebView.loadUrl(HOME_URL);
                return true;
            });
        }

        WebSettings webSettings = chatWebView.getSettings();
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setSaveFormData(false);
        webSettings.setGeolocationEnabled(false);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(false);
        webSettings.setSupportMultipleWindows(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Chrome-like UA so that reCAPTCHA / Google auth inside the WebView is not blocked.
        webSettings.setUserAgentString(buildChromeLikeUserAgent(webSettings.getUserAgentString()));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        try {
            lastFetchedChatsJson = ChatDatabaseHelper.getInstance(this).getCachedChatsJson();
            if (lastFetchedChatsJson == null || lastFetchedChatsJson.isEmpty()) {
                lastFetchedChatsJson = "{}";
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load cached chats json", t);
            lastFetchedChatsJson = "{}";
        }
        int savedZoom = prefs.getInt("text_zoom", 100);
        currentZoomLevel = (float) savedZoom;
        webSettings.setTextZoom(savedZoom);

        // Cookies: first + third party. arena.ai embeds Google reCAPTCHA (www.google.com /
        // recaptcha.net iframes) which needs third‑party cookies to verify.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(chatWebView, true);

        chatWebView.setWebViewClient(new MyWebViewClient());
        chatWebView.setWebChromeClient(new MyWebChromeClient());

        chatWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("blob:")) {
                String escapedCD = contentDisposition != null ? contentDisposition.replace("'", "\\'") : "";
                chatWebView.evaluateJavascript(
                        "(function() {" +
                                "  var url = '" + url + "';" +
                                "  var blob = window.blobMap ? window.blobMap.get(url) : null;" +
                                "  console.log('Download request for: ' + url + ' (Map found: ' + (window.blobMap !== undefined) + ')');" +
                                "  if (blob) {" +
                                "    var reader = new FileReader();" +
                                "    reader.onloadend = function() {" +
                                "      Android.processBlob(reader.result, blob.type, '" + escapedCD + "', window.location.href);" +
                                "    };" +
                                "    reader.readAsDataURL(blob);" +
                                "  } else {" +
                                "    console.warn('Blob not found in map, trying XHR fallback...');" +
                                "    var xhr = new XMLHttpRequest();" +
                                "    xhr.open('GET', url, true);" +
                                "    xhr.responseType = 'blob';" +
                                "    xhr.onload = function() {" +
                                "      if (this.status == 200) {" +
                                "        var reader = new FileReader();" +
                                "        reader.readAsDataURL(this.response);" +
                                "        reader.onloadend = function() {" +
                                "          Android.processBlob(reader.result, '" + mimetype + "', '" + escapedCD + "', window.location.href);" +
                                "        };" +
                                "      }" +
                                "    };" +
                                "    xhr.onerror = function() { console.error('Blob fetch failed: CSP or not found'); };" +
                                "    xhr.send();" +
                                "  }" +
                                "})();",
                        null);
                return;
            } else if (url.startsWith("data:")) {
                processBlob(url, mimetype, contentDisposition, url);
                return;
            }
            if (checkDownloadPermissions()) {
                startStandardDownload(url, userAgent, contentDisposition, mimetype, contentLength);
            } else {
                pendingDownloadUrl = url;
                pendingDownloadUserAgent = userAgent;
                pendingDownloadContentDisposition = contentDisposition;
                pendingDownloadMimetype = mimetype;
                pendingDownloadContentLength = contentLength;
                isPendingBlob = false;
            }
        });

        chatWebView.addJavascriptInterface(this, "Android");

        ScaleGestureDetector scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scale = detector.getScaleFactor();
                currentZoomLevel = currentZoomLevel * scale;
                currentZoomLevel = Math.max(50f, Math.min(currentZoomLevel, 300f));
                int newZoom = Math.round(currentZoomLevel);
                chatWebView.getSettings().setTextZoom(newZoom);
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt("text_zoom", newZoom).apply();
                return true;
            }
        });
        scaleGestureDetector.setQuickScaleEnabled(false);

        chatWebView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return event.getPointerCount() > 1 || scaleGestureDetector.isInProgress();
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 123);
            }
        }

        boolean restored = false;
        if (savedInstanceState != null) {
            if (chatWebView.restoreState(savedInstanceState) != null) {
                restored = true;
            }
        }
        if (!restored) {
            handleIntent(getIntent(), false);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (chatWebView != null) {
            chatWebView.saveState(outState);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Privacy: purge the HTTP cache when leaving the app, cookies/session are kept.
        clearCacheData();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        clearCacheData();
        if (chatWebView != null) {
            chatWebView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
            if (chatWebView.canGoBack()) {
                chatWebView.goBack();
            } else {
                finish();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------------------------
    // Intent handling ("Ask Arena", shares, assistant)
    // ---------------------------------------------------------------------------------
    private void handleIntent(Intent intent, boolean isResuming) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        Uri data = intent.getData();
        Log.d(TAG, "handleIntent: action = " + action + ", type = " + type + ", data = " + data);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String injectExtra = intent.getStringExtra(EXTRA_INJECT_TEXT);

        if (Intent.ACTION_VIEW.equals(action)) {
            if (injectExtra != null && !injectExtra.isEmpty()) {
                // Internal "Ask Arena" hand‑off from AskActivity
                queueTextInjection(injectExtra);
            } else if (data != null && isArenaUrl(data.toString())) {
                chatWebView.loadUrl(data.toString());
            } else {
                loadHomeIfNeeded();
            }
        } else if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_PROCESS_TEXT.equals(action)) {
            String sharedText = null;
            if (type != null && (type.startsWith("image/") || "application/pdf".equals(type))) {
                Uri streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (streamUri != null) {
                    if (type.startsWith("image/")) {
                        String ext = ".jpg";
                        if (type.contains("png")) ext = ".png";
                        else if (type.contains("webp")) ext = ".webp";
                        else if (type.contains("gif")) ext = ".gif";
                        pendingSharedFileUri = saveUriToTempFile(streamUri, ext);
                    } else {
                        pendingSharedFileUri = saveUriToTempFile(streamUri, ".pdf");
                    }
                }
            } else if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                if (text != null) sharedText = text.toString();
            }

            if (pendingSharedFileUri != null) {
                showCustomBanner("Tap the attach button to add the shared file");
                String docSuffix = prefs.getString("shared_doc_suffix", "");
                if (docSuffix != null && !docSuffix.trim().isEmpty()) {
                    queueTextInjection(docSuffix.trim());
                } else {
                    startNewChat();
                }
            } else if (sharedText != null) {
                String suffix = prefs.getString("ask_arena_suffix", "");
                if (suffix != null && !suffix.trim().isEmpty()) {
                    sharedText = sharedText + "\n\n" + suffix.trim();
                }
                queueTextInjection(sharedText);
            } else {
                loadHomeIfNeeded();
            }
        } else if (Intent.ACTION_ASSIST.equals(action)) {
            Log.d(TAG, "Assist shortcut triggered");
            // duck.ai voice chat is not available on arena.ai; "trigger_voice_assistant" is
            // repurposed as "auto_focus_on_assist": just open/focus the chat input.
            boolean autoFocusOnAssist = prefs.getBoolean("trigger_voice_assistant", true);
            String currentUrl = chatWebView.getUrl();
            if (currentUrl != null && isArenaUrl(currentUrl)) {
                if (autoFocusOnAssist) {
                    safeEvaluateJavascript(chatWebView, ARENA_DOM_JS);
                    safeEvaluateJavascript(chatWebView, AUTO_FOCUS_JS);
                }
            } else {
                pendingAutoFocus = autoFocusOnAssist;
                chatWebView.loadUrl(HOME_URL);
            }
        } else if (Intent.ACTION_MAIN.equals(action) || action == null) {
            boolean autoFocus = prefs.getBoolean("auto_focus_keyboard", true);
            String currentUrl = chatWebView.getUrl();
            if (currentUrl != null && isArenaUrl(currentUrl)) {
                if (autoFocus) {
                    safeEvaluateJavascript(chatWebView, ARENA_DOM_JS);
                    safeEvaluateJavascript(chatWebView, AUTO_FOCUS_JS);
                }
            } else {
                pendingAutoFocus = autoFocus;
                chatWebView.loadUrl(HOME_URL);
            }
            if (prefs.getBoolean("prompt_on_launch", false)) {
                showPromptOnLaunchDialog();
            }
        } else {
            loadHomeIfNeeded();
        }
    }

    private void loadHomeIfNeeded() {
        String url = chatWebView.getUrl();
        if (url == null || url.isEmpty() || url.equals("about:blank")) {
            chatWebView.loadUrl(HOME_URL);
        }
    }

    /** Navigate to a fresh chat (the arena.ai home page is the "new chat" screen). */
    private void startNewChat() {
        chatWebView.loadUrl(HOME_URL);
    }

    /**
     * Load arena.ai home (new chat) and inject the given text once the chat input exists.
     * arena.ai has no "?q=" deep link, so the text is typed in via JavaScript.
     */
    private void queueTextInjection(String text) {
        if (text == null) return;
        pendingInjectText = text;
        // Always (re)load the home page = fresh chat; injection happens in onPageFinished.
        chatWebView.loadUrl(HOME_URL);
    }

    private boolean isPageLoading = false;

    private void injectPendingText(WebView view) {
        if (pendingInjectText == null || view == null) return;
        String text = pendingInjectText;
        pendingInjectText = null;
        String literal = JSONObject.quote(text);
        safeEvaluateJavascript(view, ARENA_DOM_JS);
        safeEvaluateJavascript(view, INJECT_TEXT_JS_TEMPLATE.replace("%TEXT%", literal));
    }

    // ---------------------------------------------------------------------------------
    // JavascriptInterface "Android" bridge
    // ---------------------------------------------------------------------------------
    @JavascriptInterface
    public void showToast(final String message) {
        runOnUiThread(() -> {
            if (message != null && !message.isEmpty()) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void showSoftKeyboard() {
        runOnUiThread(() -> {
            chatWebView.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(chatWebView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    @JavascriptInterface
    public void requestWebViewFocus() {
        runOnUiThread(() -> {
            chatWebView.requestFocus();
            chatWebView.requestFocusFromTouch();
        });
    }

    @JavascriptInterface
    public void onTextInjected(boolean success) {
        if (!success) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Could not find the chat input – please paste manually", Toast.LENGTH_LONG).show());
        }
    }

    @JavascriptInterface
    public String getChatsJson() {
        if (lastFetchedChatsJson == null || lastFetchedChatsJson.isEmpty()) {
            lastFetchedChatsJson = ChatDatabaseHelper.getInstance(MainActivity.this).getCachedChatsJson();
            if (lastFetchedChatsJson == null) lastFetchedChatsJson = "{}";
        }
        return lastFetchedChatsJson;
    }

    @JavascriptInterface
    public void dismissViewer() {
        runOnUiThread(() -> {
            if (chatsViewerDialog != null && chatsViewerDialog.isShowing()) {
                chatsViewerDialog.dismiss();
            } else {
                chatWebView.loadUrl(HOME_URL);
            }
        });
    }

    @JavascriptInterface
    public void copyToClipboard(final String text) {
        runOnUiThread(() -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Copied Text", text);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(MainActivity.this, R.string.url_copied, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void processBlob(String base64Data, String mimetype, String contentDisposition, String currentUrl) {
        if (checkDownloadPermissions()) {
            saveBlobToFile(base64Data, mimetype, contentDisposition, currentUrl);
        } else {
            isPendingBlob = true;
            pendingBlobData = base64Data;
            pendingBlobMimetype = mimetype;
            pendingBlobContentDisposition = contentDisposition;
            pendingBlobCurrentUrl = currentUrl;
        }
    }

    @JavascriptInterface
    public void onChatsFetched(String jsonStr) {
        runOnUiThread(() -> {
            if (jsonStr != null && !jsonStr.isEmpty() && !jsonStr.equals("{}")) {
                try {
                    JSONObject obj = new JSONObject(jsonStr);
                    if (!obj.has("error")) {
                        lastFetchedChatsJson = jsonStr;
                        ChatDatabaseHelper.getInstance(MainActivity.this).saveCachedChatsJson(jsonStr);
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "Fetched chats could not be parsed", e);
                }
            }
            if (isRequestingViewer) {
                isRequestingViewer = false;
                showChatsViewerDialog();
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // Settings dialog (HTML based, see assets/settings.html)
    // ---------------------------------------------------------------------------------
    private JSONObject buildSettingsJson() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        JSONObject obj = new JSONObject();
        try {
            obj.put("use_drawer_assistant", prefs.getBoolean("use_drawer_assistant", true));
            obj.put("use_drawer_shared", prefs.getBoolean("use_drawer_shared", true));
            obj.put("trigger_voice_assistant", prefs.getBoolean("trigger_voice_assistant", true));
            obj.put("auto_focus_keyboard", prefs.getBoolean("auto_focus_keyboard", true));
            obj.put("prompt_on_launch", prefs.getBoolean("prompt_on_launch", false));
            obj.put("ask_arena_suffix", prefs.getString("ask_arena_suffix", ""));
            obj.put("shared_doc_suffix", prefs.getString("shared_doc_suffix", ""));
        } catch (Exception e) {
            Log.e(TAG, "Error generating settings JSON", e);
        }
        return obj;
    }

    private void persistSettingsJson(String jsonStr) throws org.json.JSONException {
        JSONObject obj = new JSONObject(jsonStr);
        boolean autoFocus = obj.optBoolean("auto_focus_keyboard", true);
        boolean promptLaunch = obj.optBoolean("prompt_on_launch", false);
        if (autoFocus) {
            promptLaunch = false;
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("use_drawer_assistant", obj.optBoolean("use_drawer_assistant", true))
                .putBoolean("use_drawer_shared", obj.optBoolean("use_drawer_shared", true))
                .putBoolean("trigger_voice_assistant", obj.optBoolean("trigger_voice_assistant", true))
                .putBoolean("auto_focus_keyboard", autoFocus)
                .putBoolean("prompt_on_launch", promptLaunch)
                .putString("ask_arena_suffix", obj.optString("ask_arena_suffix", ""))
                .putString("shared_doc_suffix", obj.optString("shared_doc_suffix", ""))
                .apply();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @JavascriptInterface
    public void showSettingsDialog() {
        runOnUiThread(() -> {
            android.app.Dialog dialog = new android.app.Dialog(MainActivity.this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            WebView webView = new WebView(MainActivity.this);
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);

            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public String getSettingsJson() {
                    return buildSettingsJson().toString();
                }

                @JavascriptInterface
                public void saveSettings(String jsonStr) {
                    try {
                        persistSettingsJson(jsonStr);
                        runOnUiThread(() -> {
                            dialog.dismiss();
                            Toast.makeText(MainActivity.this, "Settings saved", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error saving settings", e);
                    }
                }

                @JavascriptInterface
                public void saveSettingsAuto(String jsonStr) {
                    try {
                        persistSettingsJson(jsonStr);
                    } catch (Exception e) {
                        Log.e(TAG, "Error auto-saving settings", e);
                    }
                }

                @JavascriptInterface
                public void dismissSettings() {
                    runOnUiThread(dialog::dismiss);
                }

                @JavascriptInterface
                public void openChatsViewer() {
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        fetchChatsAndShowViewer();
                    });
                }

                @JavascriptInterface
                public void openHome() {
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        chatWebView.loadUrl(HOME_URL);
                    });
                }

                @JavascriptInterface
                public void clearBrowsingData() {
                    runOnUiThread(() -> {
                        try {
                            chatWebView.clearCache(true);
                            chatWebView.clearHistory();
                            android.webkit.WebStorage.getInstance().deleteAllData();
                            CookieManager.getInstance().removeAllCookies(null);
                            CookieManager.getInstance().flush();
                        } catch (Throwable t) {
                            Log.e(TAG, "clearBrowsingData failed", t);
                        }
                        dialog.dismiss();
                        Toast.makeText(MainActivity.this, "Site data cleared", Toast.LENGTH_SHORT).show();
                        chatWebView.loadUrl(HOME_URL);
                    });
                }
            }, "AndroidSettings");
            webView.setWebChromeClient(new WebChromeClient()); // enables JS confirm() dialogs

            webView.loadUrl("file:///android_asset/settings.html");
            dialog.setContentView(webView);
            dialog.show();

            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        });
    }

    private void fetchChatsAndShowViewer() {
        runOnUiThread(() -> {
            isRequestingViewer = true;
            String currentUrl = chatWebView.getUrl();
            if (currentUrl != null && isArenaUrl(currentUrl)) {
                chatWebView.evaluateJavascript(DUMP_CHATS_JS, null);
            } else {
                isRequestingViewer = false;
                showChatsViewerDialog();
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showChatsViewerDialog() {
        runOnUiThread(() -> {
            if (chatsViewerDialog != null && chatsViewerDialog.isShowing()) {
                chatsViewerDialog.dismiss();
            }
            chatsViewerDialog = new android.app.Dialog(MainActivity.this);
            chatsViewerDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            WebView webView = new WebView(MainActivity.this);
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);

            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public String getChatsJson() {
                    return lastFetchedChatsJson;
                }

                @JavascriptInterface
                public void dismissViewer() {
                    runOnUiThread(() -> {
                        if (chatsViewerDialog != null) chatsViewerDialog.dismiss();
                    });
                }

                @JavascriptInterface
                public void copyToClipboard(String text) {
                    MainActivity.this.copyToClipboard(text);
                }

                @JavascriptInterface
                public void processBlob(String base64Data, String mimetype, String contentDisposition, String currentUrl) {
                    MainActivity.this.processBlob(base64Data, mimetype, contentDisposition, currentUrl);
                }
            }, "AndroidChatsViewer");

            webView.loadUrl(FALLBACK_URL);
            chatsViewerDialog.setContentView(webView);
            chatsViewerDialog.show();

            Window window = chatsViewerDialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // Prompt-on-launch dialog (assets/prompt_dialog.html)
    // ---------------------------------------------------------------------------------
    private android.app.Dialog promptDialog = null;

    @SuppressLint("SetJavaScriptEnabled")
    public void showPromptOnLaunchDialog() {
        runOnUiThread(() -> {
            if (promptDialog != null && promptDialog.isShowing()) {
                promptDialog.dismiss();
            }
            promptDialog = new android.app.Dialog(MainActivity.this);
            promptDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            WebView webView = new WebView(MainActivity.this);
            webView.setBackgroundColor(Color.TRANSPARENT);
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);

            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void sendPrompt(String text) {
                    runOnUiThread(() -> {
                        if (promptDialog != null && promptDialog.isShowing()) promptDialog.dismiss();
                        if (text == null || text.trim().isEmpty()) return;
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        String suffix = prefs.getString("ask_arena_suffix", "");
                        String promptText = text.trim();
                        if (suffix != null && !suffix.trim().isEmpty()) {
                            promptText = promptText + "\n\n" + suffix.trim();
                        }
                        queueTextInjection(promptText);
                    });
                }

                @JavascriptInterface
                public void dismissPrompt() {
                    runOnUiThread(() -> {
                        if (promptDialog != null && promptDialog.isShowing()) promptDialog.dismiss();
                    });
                }

                @JavascriptInterface
                public void showSoftKeyboard() {
                    runOnUiThread(() -> {
                        webView.requestFocus();
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(webView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        }
                    });
                }
            }, "AndroidPrompt");

            webView.loadUrl("file:///android_asset/prompt_dialog.html");
            promptDialog.setContentView(webView);
            promptDialog.show();

            Window window = promptDialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // Downloads
    // ---------------------------------------------------------------------------------
    private String formatMarkdownFilename(String filename, String mimetype) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

        if (filename == null || filename.isEmpty()) {
            return "Arena_Chat_" + timestamp + ".md";
        }

        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) {
            filename = filename.substring(0, filename.length() - 4) + ".md";
        } else if (lower.endsWith(".text")) {
            filename = filename.substring(0, filename.length() - 5) + ".md";
        } else if (lower.endsWith(".bin")) {
            filename = filename.substring(0, filename.length() - 4) + ".md";
        }

        if (!filename.contains(".")) {
            filename += ".md";
        }

        int dotIndex = filename.lastIndexOf('.');
        String baseName = (dotIndex > 0) ? filename.substring(0, dotIndex).trim() : filename;
        if (filename.endsWith(".md") && (baseName.equalsIgnoreCase("download") || baseName.equalsIgnoreCase("chat")
                || baseName.equalsIgnoreCase("export") || baseName.equalsIgnoreCase("conversation")
                || baseName.equalsIgnoreCase("arena_chat"))) {
            filename = "Arena_Chat_" + timestamp + ".md";
        }
        return filename;
    }

    private void saveBlobToFile(String base64Data, String mimetype, String contentDisposition, String currentUrl) {
        if (base64Data.contains(",")) {
            base64Data = base64Data.split(",")[1];
        }

        String filename = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
        if (filename == null || filename.isEmpty()) {
            filename = URLUtilCompat.guessFileName(currentUrl, contentDisposition, mimetype);
        }

        filename = formatMarkdownFilename(filename, mimetype);
        if (filename.endsWith(".md")) {
            mimetype = "text/markdown";
        }

        final String finalFilename = filename;
        final String finalMimetype = mimetype;
        try {
            Uri fileUri = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimetype);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + "ArenaAssist");

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                        byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                        if (outputStream != null) outputStream.write(data);
                        fileUri = uri;
                    }
                }
            } else {
                File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ArenaAssist");
                if (!path.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    path.mkdirs();
                }
                File file = new File(path, filename);
                try (FileOutputStream os = new FileOutputStream(file)) {
                    byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                    os.write(data);
                    fileUri = Uri.fromFile(file);
                }
                MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, new String[]{mimetype}, null);
            }

            if (fileUri != null) {
                final Uri finalUri = fileUri;
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, getString(R.string.download) + " " + finalFilename, Toast.LENGTH_SHORT).show();
                    showDownloadNotification(finalFilename, finalMimetype, finalUri);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Blob download failed", e);
        }
    }

    private void startStandardDownload(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
        Uri source = Uri.parse(url);
        DownloadManager.Request request = new DownloadManager.Request(source);
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) request.addRequestHeader("Cookie", cookies);
        if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        String filename = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
        if (filename == null)
            filename = URLUtilCompat.guessFileName(url, contentDisposition, mimetype);

        filename = formatMarkdownFilename(filename, mimetype);
        if (filename.endsWith(".md")) {
            mimetype = "text/markdown";
            request.setMimeType(mimetype);
        }

        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ArenaAssist" + File.separator + filename);
        Toast.makeText(this, getString(R.string.download) + " " + filename, Toast.LENGTH_SHORT).show();
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm != null) dm.enqueue(request);
    }

    private boolean checkDownloadPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, DOWNLOAD_PERMISSION_REQUEST_CODE);
                return false;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, DOWNLOAD_PERMISSION_REQUEST_CODE);
                return false;
            }
        }
        return true;
    }

    private void showDownloadNotification(String filename, String mimeType, Uri uri) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = "arena_downloads";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_DEFAULT);
            notificationManager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType != null ? mimeType : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(filename)
                .setContentText("Download completed")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        if (notificationManager != null) {
            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == DOWNLOAD_PERMISSION_REQUEST_CODE) {
            boolean writeGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                    writeGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                }
            }
            if (writeGranted) {
                if (isPendingBlob) {
                    if (pendingBlobData != null) {
                        saveBlobToFile(pendingBlobData, pendingBlobMimetype, pendingBlobContentDisposition, pendingBlobCurrentUrl);
                    }
                } else if (pendingDownloadUrl != null) {
                    startStandardDownload(pendingDownloadUrl, pendingDownloadUserAgent, pendingDownloadContentDisposition, pendingDownloadMimetype, pendingDownloadContentLength);
                }
            } else {
                Toast.makeText(this, "Permission denied. Cannot download file.", Toast.LENGTH_SHORT).show();
            }
            clearPendingDownload();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_SHORT).show();
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }
            }
        }
    }

    private void clearPendingDownload() {
        pendingDownloadUrl = null;
        pendingDownloadUserAgent = null;
        pendingDownloadContentDisposition = null;
        pendingDownloadMimetype = null;
        pendingDownloadContentLength = 0;
        isPendingBlob = false;
        pendingBlobData = null;
        pendingBlobMimetype = null;
        pendingBlobContentDisposition = null;
        pendingBlobCurrentUrl = null;
    }

    // ---------------------------------------------------------------------------------
    // Banner overlay
    // ---------------------------------------------------------------------------------
    private void showCustomBanner(String message) {
        runOnUiThread(() -> {
            View root = findViewById(android.R.id.content);
            if (root instanceof android.view.ViewGroup) {
                android.view.ViewGroup viewGroup = (android.view.ViewGroup) root;

                View oldBanner = viewGroup.findViewWithTag("attention_banner");
                if (oldBanner != null) {
                    viewGroup.removeView(oldBanner);
                }

                float density = getResources().getDisplayMetrics().density;
                android.widget.LinearLayout bannerCard = new android.widget.LinearLayout(this);
                bannerCard.setTag("attention_banner");
                bannerCard.setOrientation(android.widget.LinearLayout.VERTICAL);

                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                shape.setColor(Color.parseColor("#5B3DF5"));
                shape.setCornerRadius(12 * density);
                bannerCard.setBackground(shape);
                bannerCard.setElevation(16 * density);

                android.widget.TextView textView = new android.widget.TextView(this);
                textView.setText(message);
                textView.setTextColor(Color.WHITE);
                textView.setTextSize(16);
                textView.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                int padding = (int) (16 * density);
                textView.setPadding(padding, padding, padding, padding);
                textView.setGravity(android.view.Gravity.CENTER);
                bannerCard.addView(textView);

                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
                int margin = (int) (16 * density);
                lp.setMargins(margin, margin + (int) (24 * density), margin, margin);
                lp.gravity = android.view.Gravity.TOP;

                bannerCard.setTranslationY(-300);
                viewGroup.addView(bannerCard, lp);

                bannerCard.animate()
                        .translationY(0)
                        .setDuration(400)
                        .setInterpolator(new android.view.animation.OvershootInterpolator())
                        .start();

                bannerCard.postDelayed(() -> bannerCard.animate()
                        .translationY(-400)
                        .setDuration(300)
                        .withEndAction(() -> viewGroup.removeView(bannerCard))
                        .start(), 6000);
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // WebViewClient
    // ---------------------------------------------------------------------------------
    private class MyWebViewClient extends WebViewClient {

        private boolean handleUrl(Uri uri) {
            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (scheme != null && (scheme.equals("http") || scheme.equals("https")) && isAllowedHost(host)) {
                return false; // stay inside the app
            }
            if (scheme != null && (scheme.equals("blob") || scheme.equals("data") || scheme.equals("file") || scheme.equals("about") || scheme.equals("javascript"))) {
                return false;
            }
            // Everything else (citations, model provider links, mailto:, intent:, ...) → system browser
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "No activity to open " + uri, e);
                Toast.makeText(MainActivity.this, "No app can open this link", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(Uri.parse(url));
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            isPageLoading = true;
            progressBar.setVisibility(View.VISIBLE);
            if (isSafeMode) return;
            safeEvaluateJavascript(view, BLOB_JS);
            safeEvaluateJavascript(view, CLIPBOARD_JS);
            safeEvaluateJavascript(view, ARENA_DOM_JS);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            isPageLoading = false;
            progressBar.setVisibility(View.GONE);
            CookieManager.getInstance().flush();
            if (isSafeMode) return;
            safeEvaluateJavascript(view, BLOB_JS);
            safeEvaluateJavascript(view, CLIPBOARD_JS);
            safeEvaluateJavascript(view, ARENA_DOM_JS);

            if (!isArenaUrl(url)) return;

            SharedPreferences prefs = view.getContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (pendingInjectText != null) {
                injectPendingText(view);
                pendingAutoFocus = false;
            } else if (pendingAutoFocus || prefs.getBoolean("auto_focus_keyboard", true)) {
                safeEvaluateJavascript(view, AUTO_FOCUS_JS);
                pendingAutoFocus = false;
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            if (request.isForMainFrame()) {
                String url = request.getUrl().toString();
                if (isArenaUrl(url)) {
                    Log.d(TAG, "Connection failed for main frame, replacing with local fallback page: " + error.getDescription());
                    runOnUiThread(() -> view.loadUrl(FALLBACK_URL));
                    return;
                }
            }
            super.onReceivedError(view, request, error);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (isArenaUrl(failingUrl)) {
                Log.d(TAG, "Connection failed (legacy), replacing with local fallback page: " + description);
                runOnUiThread(() -> view.loadUrl(FALLBACK_URL));
                return;
            }
            super.onReceivedError(view, errorCode, description, failingUrl);
        }
    }

    // ---------------------------------------------------------------------------------
    // WebChromeClient
    // ---------------------------------------------------------------------------------
    private class MyWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (isSafeMode) return;
            if (newProgress > 5) {
                safeEvaluateJavascript(view, BLOB_JS);
                safeEvaluateJavascript(view, CLIPBOARD_JS);
                safeEvaluateJavascript(view, ARENA_DOM_JS);
            }
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
            Log.d(TAG, "JS Console: " + consoleMessage.message() + " (Line " + consoleMessage.lineNumber() + ")");
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            MainActivity.this.runOnUiThread(() -> {
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                        request.grant(new String[]{resource});
                        return;
                    }
                }
                request.deny();
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                         WebChromeClient.FileChooserParams fileChooserParams) {
            if (pendingSharedFileUri != null) {
                filePathCallback.onReceiveValue(new Uri[]{pendingSharedFileUri});
                pendingSharedFileUri = null;
                return true;
            }
            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(null);
            }
            mUploadMessage = filePathCallback;

            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("Select Option");
            builder.setItems(new CharSequence[]{"Camera", "File Manager"}, (dialog, which) -> {
                if (which == 0) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
                    } else {
                        openCamera();
                    }
                } else {
                    openFileManager();
                }
            });
            builder.setOnCancelListener(dialog -> {
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }
            });
            builder.show();
            return true;
        }
    }

    private void openCamera() {
        try {
            File photoFile = new File(getExternalCacheDir(), "camera_photo_" + System.currentTimeMillis() + ".jpg");
            cameraImageUri = FileProvider.getUriForFile(this, "org.arenaassist.app.fileprovider", photoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
            startActivityForResult(intent, CAMERA_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Error opening camera", e);
            Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show();
            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(null);
                mUploadMessage = null;
            }
        }
    }

    private void openFileManager() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i, "File Chooser"), FILE_CHOOSER_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (null == mUploadMessage) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && intent != null) {
                String dataString = intent.getDataString();
                if (dataString != null) {
                    result = new Uri[]{Uri.parse(dataString)};
                }
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        } else if (requestCode == CAMERA_REQUEST_CODE) {
            if (null == mUploadMessage) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && cameraImageUri != null) {
                result = new Uri[]{cameraImageUri};
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        }
    }
}
