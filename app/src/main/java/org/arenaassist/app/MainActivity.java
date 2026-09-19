package org.arenaassist.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.app.AlertDialog;
import android.content.DialogInterface;
import androidx.core.content.FileProvider;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.File;
import android.Manifest;
import android.graphics.Canvas;
import android.graphics.Color;
import java.io.InputStream;
import android.content.pm.PackageManager;
import android.webkit.PermissionRequest;

import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.StrictMode;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.webkit.URLUtilCompat;

import org.woheller69.freeDroidWarn.FreeDroidWarn;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class MainActivity extends Activity {

    private WebView chatWebView;
    private float currentZoomLevel = 100f;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> mUploadMessage;
    private final static int FILE_CHOOSER_REQUEST_CODE = 1;
    private final static int CAMERA_REQUEST_CODE = 2;
    private final static int CAMERA_PERMISSION_REQUEST_CODE = 124;
    private Uri cameraImageUri = null;
    private final String TAG = "ArenaAssist";
    private final boolean restricted = false;
    private boolean pendingAutoFocus = false;
    private String pendingSharedText = null;
    private Uri pendingSharedFileUri = null;
    private static boolean isSafeMode = false;

    /** Base URL of the wrapped web app. */
    private static final String ARENA_HOME = "https://arena.ai/";
    /** Hosts that stay inside the WebView. Everything else opens in the system browser. */
    private static final String[] INTERNAL_HOSTS = {
            "arena.ai", "www.arena.ai", "lmarena.ai", "www.lmarena.ai"
    };
    private static final String PREFS_NAME = "arena_assist_prefs";
    private static final String FILEPROVIDER_AUTH = "org.arenaassist.app.fileprovider";
    private static final String DOWNLOAD_DIR = "ArenaAssist";
    /** Full Chrome-like UA used when the default WebView UA is not Chrome-based
     *  (arena.ai uses reCAPTCHA, which may block bare WebView user agents). */
    private static final String CHROME_FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UP1A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private static boolean isInternalHost(String host) {
        if (host == null) return false;
        for (String h : INTERNAL_HOSTS) {
            if (h.equalsIgnoreCase(host)) return true;
        }
        return false;
    }

    private static boolean isArenaUrl(String url) {
        if (url == null) return false;
        try {
            return isInternalHost(Uri.parse(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

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
                    chatWebView.loadUrl(ARENA_HOME);
                }
            });
        }
    }

    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadContentDisposition;
    private String pendingDownloadMimetype;
    private long pendingDownloadContentLength;

    private boolean isPendingBlob = false;
    private String pendingBlobData;
    private String pendingBlobMimetype;
    private String pendingBlobContentDisposition;
    private String pendingBlobCurrentUrl;

    private static final int DOWNLOAD_PERMISSION_REQUEST_CODE = 456;
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

    /**
     * arena.ai / lmarena.ai DOM helpers.
     *
     * Selectors below were written for the live arena.ai DOM (a React app) and are
     * intentionally generic + layered with fallbacks, since arena.ai ships hashed
     * CSS class names that change between releases:
     *
     * - Chat input : textarea[data-sentry-element="AutoResizeTextarea"], textarea[placeholder],
     *                textarea, div[role="textbox"], [contenteditable="true"], input[type="text"]
     * - Send button: form button[type="submit"], button[type="submit"],
     *                button[aria-label*="Send message"], button[aria-label*="Send"]
     * - New chat   : a[href="/"] / button whose text contains "New Chat"
     * - Mode switch: buttons whose text contains Battle / Agent / Side by Side / Direct
     *                (plus button[role="combobox"] model pickers used by arena.ai)
     */
    private final String ARENA_DOM_JS = "(function() {" +
            "    if (window.ArenaDom) return;" +
            "    var INPUT_SELECTORS = [" +
            "        'textarea[data-sentry-element=\"AutoResizeTextarea\"]'," +
            "        'textarea[placeholder]'," +
            "        'textarea'," +
            "        'div[role=\"textbox\"]'," +
            "        '[contenteditable=\"true\"]'," +
            "        'input[type=\"text\"]'" +
            "    ];" +
            "    var SEND_SELECTORS = [" +
            "        'form button[type=\"submit\"]'," +
            "        'button[type=\"submit\"]'," +
            "        'button[aria-label*=\"Send message\"]'," +
            "        'button[aria-label*=\"Send\"]'," +
            "        'button[aria-label*=\"send\"]'" +
            "    ];" +
            "    function isVisible(el) {" +
            "        if (!el || !el.getBoundingClientRect) return false;" +
            "        var r = el.getBoundingClientRect();" +
            "        return r.width > 0 && r.height > 0 && el.offsetParent !== null;" +
            "    }" +
            "    function findInput() {" +
            "        for (var i = 0; i < INPUT_SELECTORS.length; i++) {" +
            "            var els = document.querySelectorAll(INPUT_SELECTORS[i]);" +
            "            for (var j = 0; j < els.length; j++) {" +
            "                if (isVisible(els[j]) && !els[j].disabled && !els[j].readOnly) return els[j];" +
            "            }" +
            "        }" +
            "        var fallback = document.querySelectorAll('textarea, input[type=\"text\"], [contenteditable=\"true\"], [role=\"textbox\"]');" +
            "        for (var k = 0; k < fallback.length; k++) {" +
            "            if (isVisible(fallback[k])) return fallback[k];" +
            "        }" +
            "        return null;" +
            "    }" +
            "    function findSend() {" +
            "        for (var i = 0; i < SEND_SELECTORS.length; i++) {" +
            "            var els = document.querySelectorAll(SEND_SELECTORS[i]);" +
            "            for (var j = 0; j < els.length; j++) {" +
            "                if (isVisible(els[j]) && !els[j].disabled) return els[j];" +
            "            }" +
            "        }" +
            "        return null;" +
            "    }" +
            "    function textOf(el) {" +
            "        try { return ((el.innerText || el.textContent || '').toLowerCase()); } catch (e) { return ''; }" +
            "    }" +
            "    function findNewChat() {" +
            "        var els = document.querySelectorAll('a[href=\"/\"], a, button');" +
            "        for (var i = 0; i < els.length; i++) {" +
            "            if (textOf(els[i]).indexOf('new chat') !== -1 && isVisible(els[i])) return els[i];" +
            "        }" +
            "        return null;" +
            "    }" +
            "    function findMode(mode) {" +
            "        mode = (mode || '').toLowerCase();" +
            "        var needles = [];" +
            "        if (mode.indexOf('battle') !== -1) needles = ['battle'];" +
            "        else if (mode.indexOf('agent') !== -1) needles = ['agent'];" +
            "        else if (mode.indexOf('side') !== -1) needles = ['side by side', 'side-by-side', 'sidebyside'];" +
            "        else if (mode.indexOf('direct') !== -1) needles = ['direct'];" +
            "        else return null;" +
            "        var els = document.querySelectorAll('button, [role=\"tab\"], [role=\"combobox\"], a');" +
            "        for (var i = 0; i < els.length; i++) {" +
            "            var t = textOf(els[i]);" +
            "            for (var n = 0; n < needles.length; n++) {" +
            "                if (t.indexOf(needles[n]) !== -1 && isVisible(els[i])) return els[i];" +
            "            }" +
            "        }" +
            "        return null;" +
            "    }" +
            "    function setValue(el, value) {" +
            "        if (!el) return false;" +
            "        try { el.focus(); } catch (e) {}" +
            "        try {" +
            "            if (el.isContentEditable) {" +
            "                el.focus();" +
            "                try { document.execCommand('selectAll', false, null); } catch (e1) {}" +
            "                var inserted = false;" +
            "                try { inserted = document.execCommand('insertText', false, value); } catch (e2) {}" +
            "                if (!inserted) { el.textContent = value; }" +
            "            } else {" +
            "                var proto = (el.tagName === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;" +
            "                var desc = Object.getOwnPropertyDescriptor(proto, 'value');" +
            "                if (desc && desc.set) { desc.set.call(el, value); } else { el.value = value; }" +
            "            }" +
            "        } catch (e) { try { el.value = value; } catch (e3) {} }" +
            "        try { el.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) {}" +
            "        try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}" +
            "        try { el.focus(); } catch (e) {}" +
            "        return true;" +
            "    }" +
            "    window.ArenaDom = { findInput: findInput, findSend: findSend, findNewChat: findNewChat, findMode: findMode, setValue: setValue };" +
            "    console.log('ArenaDom helpers active');" +
            "})();";

    /** Generic auto-focus for the arena.ai chat input (no duck.ai Ctrl+Shift+O shortcut). */
    private final String AUTO_FOCUS_JS = "(function() {" +
            "  console.log('ArenaAssist auto focus: looking for chat input');" +
            "  function focusInput() {" +
            "    var input = null;" +
            "    try {" +
            "      input = (window.ArenaDom && window.ArenaDom.findInput) ? window.ArenaDom.findInput()" +
            "        : document.querySelector('textarea, [contenteditable=\"true\"], input[type=\"text\"]');" +
            "    } catch (e) { input = document.querySelector('textarea'); }" +
            "    if (input) {" +
            "      try { input.focus({ preventScroll: false }); } catch (e) { try { input.focus(); } catch (e2) {} }" +
            "      try { input.click(); } catch (e) {}" +
            "      if (window.Android && window.Android.showSoftKeyboard) { window.Android.showSoftKeyboard(); }" +
            "      return true;" +
            "    }" +
            "    return false;" +
            "  }" +
            "  if (focusInput()) return;" +
            "  var attempts = 0;" +
            "  var timer = setInterval(function() {" +
            "    attempts++;" +
            "    if (focusInput() || attempts >= 20) { clearInterval(timer); }" +
            "  }, 400);" +
            "})();";

    /**
     * Builds the "Ask Arena" injection script: writes the shared text into the
     * arena.ai chat box (React-safe value setter + input/change events + focus),
     * retrying with a MutationObserver + interval fallback for ~10s until the
     * input element appears.
     */
    private String buildArenaInjectJs(String text) {
        String quoted;
        try {
            quoted = org.json.JSONObject.quote(text);
        } catch (Exception e) {
            quoted = "\"\"";
        }
        return "(function() {" +
                "  var TEXT = " + quoted + ";" +
                "  console.log('ArenaAssist: injecting shared text (' + TEXT.length + ' chars)');" +
                "  function findInput() {" +
                "    try {" +
                "      if (window.ArenaDom && window.ArenaDom.findInput) return window.ArenaDom.findInput();" +
                "    } catch (e) {}" +
                "    var els = document.querySelectorAll('textarea, [contenteditable=\"true\"], input[type=\"text\"], [role=\"textbox\"]');" +
                "    for (var i = 0; i < els.length; i++) {" +
                "      if (els[i].offsetParent !== null) return els[i];" +
                "    }" +
                "    return null;" +
                "  }" +
                "  function tryInject() {" +
                "    var input = findInput();" +
                "    if (!input) return false;" +
                "    var ok = false;" +
                "    try {" +
                "      if (window.ArenaDom && window.ArenaDom.setValue) { ok = window.ArenaDom.setValue(input, TEXT); }" +
                "      else {" +
                "        input.focus();" +
                "        try {" +
                "          var proto = (input.tagName === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;" +
                "          var desc = Object.getOwnPropertyDescriptor(proto, 'value');" +
                "          if (desc && desc.set) { desc.set.call(input, TEXT); } else { input.value = TEXT; }" +
                "        } catch (e) { input.value = TEXT; }" +
                "        input.dispatchEvent(new Event('input', { bubbles: true }));" +
                "        input.dispatchEvent(new Event('change', { bubbles: true }));" +
                "        input.focus();" +
                "        ok = true;" +
                "      }" +
                "    } catch (e) { ok = false; }" +
                "    if (ok) {" +
                "      try { input.scrollIntoView({ block: 'nearest' }); } catch (e) {}" +
                "      if (window.Android && window.Android.showSoftKeyboard) { window.Android.showSoftKeyboard(); }" +
                "    }" +
                "    return ok;" +
                "  }" +
                "  if (tryInject()) return;" +
                "  var done = false;" +
                "  function finish(obs, timer) {" +
                "    done = true;" +
                "    try { if (obs) obs.disconnect(); } catch (e) {}" +
                "    try { if (timer) clearInterval(timer); } catch (e) {}" +
                "  }" +
                "  var observer = null;" +
                "  try {" +
                "    observer = new MutationObserver(function() {" +
                "      if (!done && tryInject()) { finish(observer, timer); }" +
                "    });" +
                "    if (document.body) { observer.observe(document.body, { childList: true, subtree: true }); }" +
                "    else if (document.documentElement) { observer.observe(document.documentElement, { childList: true, subtree: true }); }" +
                "  } catch (e) {}" +
                "  var timer = setInterval(function() {" +
                "    if (!done && tryInject()) { finish(observer, timer); }" +
                "  }, 500);" +
                "  setTimeout(function() { finish(observer, timer); }, 10000);" +
                "})();";
    }

    private void injectPendingSharedText() {
        if (pendingSharedText == null || pendingSharedText.isEmpty()) return;
        if (chatWebView == null) return;
        String text = pendingSharedText;
        pendingSharedText = null;
        safeEvaluateJavascript(chatWebView, ARENA_DOM_JS);
        safeEvaluateJavascript(chatWebView, buildArenaInjectJs(text));
    }

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

    private String lastFetchedChatsJson = "{}";
    private android.app.Dialog chatsViewerDialog = null;
    private boolean isRequestingViewer = false;

    private final String DUMP_CHATS_JS = "(function() {" +
            "  function convertBlobs(obj) {" +
            "    return new Promise(function(resolve) {" +
            "      if (!obj) return resolve(obj);" +
            "      if (typeof Date !== 'undefined' && obj instanceof Date) {" +
            "        return resolve(obj.toISOString());" +
            "      }" +
            "      if (typeof Blob !== 'undefined' && (obj instanceof Blob || obj instanceof File)) {" +
            "        try {" +
            "          let reader = new FileReader();" +
            "          reader.onloadend = function() { resolve(reader.result); };" +
            "          reader.onerror = function() { resolve(null); };" +
            "          reader.readAsDataURL(obj);" +
            "        } catch(e) { resolve(null); }" +
            "        return;" +
            "      }" +
            "      if (typeof ArrayBuffer !== 'undefined' && (obj instanceof ArrayBuffer || ArrayBuffer.isView(obj))) {" +
            "        try {" +
            "          let bytes = new Uint8Array(obj.buffer || obj);" +
            "          let binary = '';" +
            "          let len = bytes.byteLength;" +
            "          for (let i = 0; i < len; i++) { binary += String.fromCharCode(bytes[i]); }" +
            "          let base64 = btoa(binary);" +
            "          resolve('data:image/jpeg;base64,' + base64);" +
            "        } catch(e) { resolve(null); }" +
            "        return;" +
            "      }" +
            "      if (Array.isArray(obj)) {" +
            "        var promises = obj.map(function(item) { return convertBlobs(item); });" +
            "        Promise.all(promises).then(resolve).catch(function() { resolve(obj); });" +
            "        return;" +
            "      }" +
            "      if (typeof obj === 'object') {" +
            "        var keys = Object.keys(obj);" +
            "        var promises = keys.map(function(k) { return convertBlobs(obj[k]); });" +
            "        Promise.all(promises).then(function(values) {" +
            "          var newObj = {};" +
            "          keys.forEach(function(k, idx) { newObj[k] = values[idx]; });" +
            "          resolve(newObj);" +
            "        }).catch(function() { resolve(obj); });" +
            "        return;" +
            "      }" +
            "      resolve(obj);" +
            "    });" +
            "  }" +
            "  function dump() {" +
            "    let knownDbs = ['arenaChatData', 'arena-ai-chats', 'arenaChats', 'savedArenaChats', 'aiChatData'];" +
            "    let getDbs = (window.indexedDB && window.indexedDB.databases) ? window.indexedDB.databases() : Promise.resolve([]);" +
            "    getDbs.then(async (dbs) => {" +
            "      let dbNamesSet = new Set((dbs || []).map(d => d.name).filter(Boolean));" +
            "      knownDbs.forEach(k => dbNamesSet.add(k));" +
            "      let dbNames = Array.from(dbNamesSet);" +
            "      let result = {};" +
            "      for (let dbName of dbNames) {" +
            "        let res = await new Promise((resolve) => {" +
            "          try {" +
            "            let req = window.indexedDB.open(dbName);" +
            "            req.onerror = () => resolve(null);" +
            "            req.onsuccess = (e) => {" +
            "              let db = e.target.result;" +
            "              db.onversionchange = () => { try { db.close(); } catch(err){} };" +
            "              let storeNames = Array.from(db.objectStoreNames);" +
            "              if (storeNames.length === 0) {" +
            "                try { db.close(); } catch(err){}" +
            "                resolve(null);" +
            "                return;" +
            "              }" +
            "              let dbData = {};" +
            "              let completed = 0;" +
            "              let hasTimedOut = false;" +
            "              let timeoutTimer = setTimeout(() => {" +
            "                hasTimedOut = true;" +
            "                try { db.close(); } catch(err){}" +
            "                resolve(dbData);" +
            "              }, 15000);" +
            "              storeNames.forEach((storeName) => {" +
            "                try {" +
            "                  let tx = db.transaction(storeName, 'readonly');" +
            "                  let store = tx.objectStore(storeName);" +
            "                  let getAllReq = store.getAll();" +
            "                  getAllReq.onsuccess = async () => {" +
            "                    if (hasTimedOut) return;" +
            "                    try {" +
            "                      dbData[storeName] = await convertBlobs(getAllReq.result);" +
            "                    } catch(err) {" +
            "                      dbData[storeName] = getAllReq.result;" +
            "                    }" +
            "                    completed++;" +
            "                    if (completed === storeNames.length) {" +
            "                      clearTimeout(timeoutTimer);" +
            "                      try { db.close(); } catch(err){}" +
            "                      resolve(dbData);" +
            "                    }" +
            "                  };" +
            "                  getAllReq.onerror = () => {" +
            "                    if (hasTimedOut) return;" +
            "                    completed++;" +
            "                    if (completed === storeNames.length) {" +
            "                      clearTimeout(timeoutTimer);" +
            "                      try { db.close(); } catch(err){}" +
            "                      resolve(dbData);" +
            "                    }" +
            "                  };" +
            "                } catch(err) {" +
            "                  if (hasTimedOut) return;" +
            "                  completed++;" +
            "                  if (completed === storeNames.length) {" +
            "                    clearTimeout(timeoutTimer);" +
            "                    try { db.close(); } catch(e){}" +
            "                    resolve(dbData);" +
            "                  }" +
            "                }" +
            "              });" +
            "            };" +
            "          } catch(err) { resolve(null); }" +
            "        });" +
            "        if (res && Object.keys(res).length > 0) result[dbName] = res;" +
            "      }" +
            "      let localData = {};" +
            "      try {" +
            "        for (let i = 0; i < localStorage.length; i++) {" +
            "          let key = localStorage.key(i);" +
            "          localData[key] = localStorage.getItem(key);" +
            "        }" +
            "      } catch(e){}" +
            "      let blobData = {};" +
            "      if (window.blobMap && window.blobMap instanceof Map) {" +
            "        for (let [bUrl, bObj] of window.blobMap.entries()) {" +
            "          try {" +
            "            blobData[bUrl] = await convertBlobs(bObj);" +
            "          } catch(e) {}" +
            "        }" +
            "      }" +
            "      let domImages = [];" +
            "      try {" +
            "        let imgs = document.querySelectorAll('img');" +
            "        for (let i = 0; i < imgs.length; i++) {" +
            "          let img = imgs[i];" +
            "          let src = img.src || '';" +
            "          if (src) {" +
            "            try {" +
            "              if (img.naturalWidth > 30 && img.naturalHeight > 30) {" +
            "                let canvas = document.createElement('canvas');" +
            "                canvas.width = img.naturalWidth;" +
            "                canvas.height = img.naturalHeight;" +
            "                let ctx = canvas.getContext('2d');" +
            "                ctx.drawImage(img, 0, 0);" +
            "                let b64 = canvas.toDataURL('image/jpeg', 0.9);" +
            "                domImages.push({ src: src, alt: img.alt || '', dataUrl: b64 });" +
            "              } else if (src.startsWith('data:')) {" +
            "                domImages.push({ src: src, alt: img.alt || '', dataUrl: src });" +
            "              }" +
            "            } catch(e) {}" +
            "          }" +
            "        }" +
            "      } catch(e) {}" +
            "      if (typeof Android !== 'undefined' && Android.onChatsFetched) {" +
            "        Android.onChatsFetched(JSON.stringify({" +
            "          indexedDB: result," +
            "          localStorage: localData," +
            "          blobMap: blobData," +
            "          domImages: domImages" +
            "        }));" +
            "      }" +
            "    }).catch(err => {" +
            "      if (typeof Android !== 'undefined' && Android.onChatsFetched) {" +
            "        Android.onChatsFetched(JSON.stringify({error: err.toString()}));" +
            "      }" +
            "    });" +
            "  }" +
            "  try {" +
            "    document.dispatchEvent(new Event('visibilitychange'));" +
            "    window.dispatchEvent(new Event('pagehide'));" +
            "  } catch(e) {}" +
            "  setTimeout(dump, 150);" +
            "})();";

    private void clearCacheData() {
        if (chatWebView != null) {
            chatWebView.clearCache(true);
        }
        Log.d(TAG, "Cache cleared (cookies kept).");
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

    protected void setActivityTheme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
    }

    protected int getLayoutResourceId() {
        return R.layout.activity_main;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        StrictMode.VmPolicy.Builder StrictBuilder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(StrictBuilder.build());

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
                        chatWebView.loadUrl(ARENA_HOME);
                    }
                } catch (Throwable ignored) {}
            });
        });

        setContentView(getLayoutResourceId());

        progressBar = findViewById(R.id.progressBar);
        chatWebView = findViewById(R.id.chatWebView);

        // Floating native settings button (works in both fullscreen + drawer layouts)
        View settingsFab = findViewById(R.id.settingsFab);
        if (settingsFab != null) {
            settingsFab.setOnClickListener(v -> showSettingsDialog());
        }

        WebSettings webSettings = chatWebView.getSettings();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        }
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webSettings.setMediaPlaybackRequiresUserGesture(false);
        }
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setDatabaseEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setSaveFormData(false);
        webSettings.setGeolocationEnabled(false);

        // arena.ai uses reCAPTCHA: strip the WebView marker from the UA so the page
        // sees a full Chrome-like UA; fall back to a fixed Chrome UA if needed.
        try {
            String defaultUA = webSettings.getUserAgentString();
            String fixedUA = defaultUA != null ? defaultUA.replace("; wv", "").replace(" Version/4.0", "") : "";
            if (fixedUA.isEmpty() || !fixedUA.contains("Chrome/")) {
                fixedUA = CHROME_FALLBACK_UA;
            }
            webSettings.setUserAgentString(fixedUA);
        } catch (Exception e) {
            Log.w(TAG, "Could not adjust user agent", e);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        try {
            lastFetchedChatsJson = ChatDatabaseHelper.getInstance(this).getCachedChatsJson();
            if (lastFetchedChatsJson == null || lastFetchedChatsJson.equals("{}") || lastFetchedChatsJson.isEmpty()) {
                String oldPrefsJson = prefs.getString("cached_chats_json", null);
                if (oldPrefsJson != null && !oldPrefsJson.equals("{}") && !oldPrefsJson.isEmpty()) {
                    lastFetchedChatsJson = oldPrefsJson;
                    ChatDatabaseHelper.getInstance(this).saveCachedChatsJson(oldPrefsJson);
                } else {
                    lastFetchedChatsJson = "{}";
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load cached_chats_json from database, resetting to empty", t);
            lastFetchedChatsJson = "{}";
        }
        int savedZoom = prefs.getInt("text_zoom", 100);
        currentZoomLevel = (float) savedZoom;
        webSettings.setTextZoom(savedZoom);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        // IMPORTANT: arena.ai uses reCAPTCHA, which requires third-party cookies.
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
                                "  console.log('Download request for: ' + url + ' (Map found: ' + (window.blobMap !== undefined) + ')');"
                                +
                                "  if (blob) {" +
                                "    var reader = new FileReader();" +
                                "    reader.onloadend = function() {" +
                                "      Android.processBlob(reader.result, blob.type, '" + escapedCD
                                + "', window.location.href);" +
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
                                "          Android.processBlob(reader.result, '" + mimetype + "', '" + escapedCD
                                + "', window.location.href);" +
                                "        };" +
                                "      }" +
                                "    };" +
                                "    xhr.onerror = function() { console.error('Blob fetch failed: CSP or not found'); };"
                                +
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
                // Clamp text zoom between 50% and 300%
                currentZoomLevel = Math.max(50f, Math.min(currentZoomLevel, 300f));
                int newZoom = Math.round(currentZoomLevel);
                chatWebView.getSettings().setTextZoom(newZoom);

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putInt("text_zoom", newZoom).apply();
                return true;
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            scaleGestureDetector.setQuickScaleEnabled(false);
        }

        chatWebView.setOnTouchListener(new View.OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                scaleGestureDetector.onTouchEvent(event);
                return event.getPointerCount() > 1 || scaleGestureDetector.isInProgress();
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, 123);
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
        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);
    }

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
    public String getChatsJson() {
        if (lastFetchedChatsJson == null || lastFetchedChatsJson.equals("{}") || lastFetchedChatsJson.isEmpty()) {
            lastFetchedChatsJson = ChatDatabaseHelper.getInstance(MainActivity.this).getCachedChatsJson();
            if (lastFetchedChatsJson == null || lastFetchedChatsJson.equals("{}") || lastFetchedChatsJson.isEmpty()) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                lastFetchedChatsJson = prefs.getString("cached_chats_json", "{}");
            }
        }
        return lastFetchedChatsJson;
    }

    @JavascriptInterface
    public void dismissViewer() {
        runOnUiThread(() -> {
            if (chatsViewerDialog != null && chatsViewerDialog.isShowing()) {
                chatsViewerDialog.dismiss();
            } else {
                chatWebView.loadUrl(ARENA_HOME);
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
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    org.json.JSONObject obj = new org.json.JSONObject();
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
                    return obj.toString();
                }

                @JavascriptInterface
                public void saveSettings(String jsonStr) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(jsonStr);
                        boolean autoFocus = obj.optBoolean("auto_focus_keyboard", true);
                        boolean promptLaunch = obj.optBoolean("prompt_on_launch", false);
                        if (autoFocus) {
                            promptLaunch = false;
                        } else if (promptLaunch) {
                            autoFocus = false;
                        }
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        prefs.edit()
                             .putBoolean("use_drawer_assistant", obj.optBoolean("use_drawer_assistant", true))
                             .putBoolean("use_drawer_shared", obj.optBoolean("use_drawer_shared", true))
                             .putBoolean("trigger_voice_assistant", obj.optBoolean("trigger_voice_assistant", true))
                             .putBoolean("auto_focus_keyboard", autoFocus)
                             .putBoolean("prompt_on_launch", promptLaunch)
                             .putString("ask_arena_suffix", obj.optString("ask_arena_suffix", ""))
                             .putString("shared_doc_suffix", obj.optString("shared_doc_suffix", ""))
                             .apply();
                        runOnUiThread(() -> {
                            dialog.dismiss();
                            Toast.makeText(MainActivity.this, "Settings saved successfully", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error saving settings", e);
                    }
                }

                @JavascriptInterface
                public void saveSettingsAuto(String jsonStr) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(jsonStr);
                        boolean autoFocus = obj.optBoolean("auto_focus_keyboard", true);
                        boolean promptLaunch = obj.optBoolean("prompt_on_launch", false);
                        if (autoFocus) {
                            promptLaunch = false;
                        } else if (promptLaunch) {
                            autoFocus = false;
                        }
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        prefs.edit()
                             .putBoolean("use_drawer_assistant", obj.optBoolean("use_drawer_assistant", true))
                             .putBoolean("use_drawer_shared", obj.optBoolean("use_drawer_shared", true))
                             .putBoolean("trigger_voice_assistant", obj.optBoolean("trigger_voice_assistant", true))
                             .putBoolean("auto_focus_keyboard", autoFocus)
                             .putBoolean("prompt_on_launch", promptLaunch)
                             .putString("ask_arena_suffix", obj.optString("ask_arena_suffix", ""))
                             .putString("shared_doc_suffix", obj.optString("shared_doc_suffix", ""))
                             .apply();
                    } catch (Exception e) {
                        Log.e(TAG, "Error auto-saving settings", e);
                    }
                }

                @JavascriptInterface
                public void dismissSettings() {
                    runOnUiThread(() -> dialog.dismiss());
                }

                @JavascriptInterface
                public void openChatsViewer() {
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        fetchChatsAndShowViewer();
                    });
                }
            }, "AndroidSettings");

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

    @JavascriptInterface
    public void onChatsFetched(String jsonStr) {
        runOnUiThread(() -> {
            if (jsonStr == null || jsonStr.isEmpty() || jsonStr.equals("{}")) {
                if (isRequestingViewer) {
                    isRequestingViewer = false;
                    showChatsViewerDialog();
                }
                return;
            }
            try {
                org.json.JSONObject obj = new org.json.JSONObject(jsonStr);
                if (obj.has("error")) {
                    Log.w(TAG, "Fetched chats root contains error, skipping overwrite: " + obj.getString("error"));
                    if (isRequestingViewer) {
                        isRequestingViewer = false;
                        showChatsViewerDialog();
                    }
                    return;
                }
            } catch (Throwable e) {
                Log.w(TAG, "Fetched chats could not be parsed or caused OutOfMemoryError, skipping overwrite", e);
                if (isRequestingViewer) {
                    isRequestingViewer = false;
                    showChatsViewerDialog();
                }
                return;
            }
            String finalJsonStr = jsonStr;
            try {
                org.json.JSONObject newObj = new org.json.JSONObject(jsonStr);
                String oldJson = ChatDatabaseHelper.getInstance(MainActivity.this).getCachedChatsJson();
                if (oldJson != null && !oldJson.isEmpty() && !oldJson.equals("{}")) {
                    try {
                        org.json.JSONObject oldObj = new org.json.JSONObject(oldJson);

                        // Merge blobMap
                        org.json.JSONObject oldBlobMap = oldObj.optJSONObject("blobMap");
                        org.json.JSONObject newBlobMap = newObj.optJSONObject("blobMap");
                        if (oldBlobMap != null) {
                            if (newBlobMap == null) {
                                newObj.put("blobMap", oldBlobMap);
                            } else {
                                java.util.Iterator<String> keys = oldBlobMap.keys();
                                while (keys.hasNext()) {
                                    String k = keys.next();
                                    if (!newBlobMap.has(k)) {
                                        newBlobMap.put(k, oldBlobMap.get(k));
                                    }
                                }
                            }
                        }

                        // Merge domImages
                        org.json.JSONArray oldDomImgs = oldObj.optJSONArray("domImages");
                        org.json.JSONArray newDomImgs = newObj.optJSONArray("domImages");
                        if (oldDomImgs != null && oldDomImgs.length() > 0) {
                            if (newDomImgs == null) {
                                newObj.put("domImages", oldDomImgs);
                            } else {
                                java.util.Set<String> existingSrcs = new java.util.HashSet<>();
                                for (int i = 0; i < newDomImgs.length(); i++) {
                                    org.json.JSONObject item = newDomImgs.optJSONObject(i);
                                    if (item != null && item.has("src")) {
                                        existingSrcs.add(item.optString("src"));
                                    }
                                }
                                for (int i = 0; i < oldDomImgs.length(); i++) {
                                    org.json.JSONObject item = oldDomImgs.optJSONObject(i);
                                    if (item != null) {
                                        String src = item.optString("src");
                                        if (src == null || !existingSrcs.contains(src)) {
                                            newDomImgs.put(item);
                                            if (src != null) existingSrcs.add(src);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignore) {}
                }
                finalJsonStr = newObj.toString();
            } catch (Throwable e) {
                Log.w(TAG, "Fetched chats merge error", e);
            }
            lastFetchedChatsJson = finalJsonStr;
            ChatDatabaseHelper.getInstance(MainActivity.this).saveCachedChatsJson(finalJsonStr);
            if (isRequestingViewer || (chatsViewerDialog != null && chatsViewerDialog.isShowing())) {
                isRequestingViewer = false;
                showChatsViewerDialog();
            }
        });
    }

    private void fetchChatsAndShowViewer() {
        runOnUiThread(() -> {
            isRequestingViewer = true;
            String currentUrl = chatWebView.getUrl();
            if (isArenaUrl(currentUrl)) {
                chatWebView.evaluateJavascript(DUMP_CHATS_JS, null);
            } else {
                showChatsViewerDialog();
            }
        });
    }

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
                        if (chatsViewerDialog != null) {
                            chatsViewerDialog.dismiss();
                        }
                    });
                }

                @JavascriptInterface
                public void copyToClipboard(String text) {
                    runOnUiThread(() -> {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("Copied JSON", text);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(clip);
                            Toast.makeText(MainActivity.this, "Copied debug info to clipboard!", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @JavascriptInterface
                public void processBlob(String base64Data, String mimetype, String contentDisposition, String currentUrl) {
                    MainActivity.this.processBlob(base64Data, mimetype, contentDisposition, currentUrl);
                }
            }, "AndroidChatsViewer");

            webView.loadUrl("file:///android_asset/chats_viewer.html");
            chatsViewerDialog.setContentView(webView);
            chatsViewerDialog.show();

            Window window = chatsViewerDialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        });
    }

    private String formatArenaFilename(String filename, String mimetype) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

        if (filename == null || filename.isEmpty()) {
            return "Arena_Chat_" + timestamp + ".md";
        }

        // Change .txt or .text extensions to .md
        if (filename.toLowerCase().endsWith(".txt")) {
            filename = filename.substring(0, filename.length() - 4) + ".md";
        } else if (filename.toLowerCase().endsWith(".text")) {
            filename = filename.substring(0, filename.length() - 5) + ".md";
        } else if (filename.toLowerCase().endsWith(".bin")) {
            filename = filename.substring(0, filename.length() - 4) + ".md";
        }

        // If no extension exists, append .md
        if (!filename.contains(".")) {
            filename += ".md";
        }

        // Replace generic names like download.md, chat.md, export.md with a formatted timestamped name
        int dotIndex = filename.lastIndexOf('.');
        String baseName = (dotIndex > 0) ? filename.substring(0, dotIndex).trim() : filename;
        if (baseName.equalsIgnoreCase("download") || baseName.equalsIgnoreCase("chat") || baseName.equalsIgnoreCase("export") || baseName.equalsIgnoreCase("arena_chat") || baseName.equalsIgnoreCase("arena_ai_chat")) {
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

        filename = formatArenaFilename(filename, mimetype);
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
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + DOWNLOAD_DIR);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                        byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                        outputStream.write(data);
                        fileUri = uri;
                    }
                }
            } else {
                File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR);
                if (!path.exists()) {
                    path.mkdirs();
                }
                File file = new File(path, filename);
                try (FileOutputStream os = new FileOutputStream(file)) {
                    byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                    os.write(data);
                    fileUri = Uri.fromFile(file);
                }
                // Force media scanner to scan the file so it shows in downloads library
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
        request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        String filename = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
        if (filename == null)
            filename = URLUtilCompat.guessFileName(url, contentDisposition, mimetype);

        filename = formatArenaFilename(filename, mimetype);
        if (filename.endsWith(".md")) {
            mimetype = "text/markdown";
            request.setMimeType(mimetype);
        }

        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, DOWNLOAD_DIR + File.separator + filename);
        Toast.makeText(this, getString(R.string.download) + " " + filename, Toast.LENGTH_SHORT).show();
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm != null)
            dm.enqueue(request);
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

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
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
                } else {
                    if (pendingDownloadUrl != null) {
                        startStandardDownload(pendingDownloadUrl, pendingDownloadUserAgent, pendingDownloadContentDisposition, pendingDownloadMimetype, pendingDownloadContentLength);
                    }
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

    private void showCustomBanner(String message) {
        runOnUiThread(() -> {
            View root = findViewById(android.R.id.content);
            if (root instanceof android.view.ViewGroup) {
                android.view.ViewGroup viewGroup = (android.view.ViewGroup) root;

                View oldBanner = viewGroup.findViewWithTag("attention_banner");
                if (oldBanner != null) {
                    viewGroup.removeView(oldBanner);
                }

                android.widget.LinearLayout bannerCard = new android.widget.LinearLayout(this);
                bannerCard.setTag("attention_banner");
                bannerCard.setOrientation(android.widget.LinearLayout.VERTICAL);

                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                shape.setColor(Color.parseColor("#d32f2f"));
                shape.setCornerRadius(12 * getResources().getDisplayMetrics().density);
                bannerCard.setBackground(shape);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    bannerCard.setElevation(16 * getResources().getDisplayMetrics().density);
                }

                android.widget.TextView textView = new android.widget.TextView(this);
                textView.setText(message);
                textView.setTextColor(Color.WHITE);
                textView.setTextSize(16);
                textView.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                int padding = (int) (16 * getResources().getDisplayMetrics().density);
                textView.setPadding(padding, padding, padding, padding);
                textView.setGravity(android.view.Gravity.CENTER);

                bannerCard.addView(textView);

                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                );
                int margin = (int) (16 * getResources().getDisplayMetrics().density);
                lp.setMargins(margin, margin + (int)(24 * getResources().getDisplayMetrics().density), margin, margin);
                lp.gravity = android.view.Gravity.TOP;

                bannerCard.setTranslationY(-300);
                viewGroup.addView(bannerCard, lp);

                bannerCard.animate()
                        .translationY(0)
                        .setDuration(400)
                        .setInterpolator(new android.view.animation.OvershootInterpolator())
                        .start();

                bannerCard.postDelayed(() -> {
                    bannerCard.animate()
                            .translationY(-400)
                            .setDuration(300)
                            .withEndAction(() -> viewGroup.removeView(bannerCard))
                            .start();
                }, 6000);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        runOnUiThread(() -> {
            if (chatWebView != null && isArenaUrl(chatWebView.getUrl())) {
                chatWebView.evaluateJavascript(DUMP_CHATS_JS, null);
            }
        });
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

    private void handleIntent(Intent intent, boolean isResuming) {
        if (intent == null)
            return;
        String action = intent.getAction();
        String type = intent.getType();
        Uri data = intent.getData();
        Log.d(TAG, "handleIntent: action = " + action + ", type = " + type + ", data = " + (data != null ? data.toString() : "null"));
        if (intent.getExtras() != null) {
            for (String key : intent.getExtras().keySet()) {
                Log.d(TAG, "  extra: " + key + " = " + intent.getExtras().get(key));
            }
        }

        if (Intent.ACTION_VIEW.equals(action)) {
            data = intent.getData();
            if (data != null && isInternalHost(data.getHost())) {
                // arena.ai deep link. arena.ai does not support duck.ai's ?q=/handoff
                // scheme, so a ?q= parameter (if any) is injected via JS instead.
                String query = data.getQueryParameter("q");
                if (query != null && !query.isEmpty()) {
                    pendingSharedText = query;
                }
                chatWebView.loadUrl(data.toString());
            } else if (data != null) {
                // Non-arena link: open in the system browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, data));
                } catch (Exception e) {
                    Log.e(TAG, "No browser found for external link", e);
                }
                if (chatWebView.getUrl() == null || chatWebView.getUrl().isEmpty()
                        || chatWebView.getUrl().equals("about:blank")) {
                    chatWebView.loadUrl(ARENA_HOME);
                }
            } else {
                chatWebView.loadUrl(ARENA_HOME);
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
                        pendingSharedFileUri = saveUriToTempFile(streamUri, ext);
                    } else if ("application/pdf".equals(type)) {
                        pendingSharedFileUri = saveUriToTempFile(streamUri, ".pdf");
                    }
                }
            } else if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                if (text != null)
                    sharedText = text.toString();
            }

            if (pendingSharedFileUri != null) {
                showCustomBanner(getString(R.string.attach_shared_file));
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String docSuffix = prefs.getString("shared_doc_suffix", "");
                if (docSuffix != null && !docSuffix.trim().isEmpty()) {
                    pendingSharedText = docSuffix;
                }
                chatWebView.loadUrl(ARENA_HOME);
            } else if (sharedText != null) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String suffix = prefs.getString("ask_arena_suffix", "");
                if (suffix != null && !suffix.trim().isEmpty()) {
                    sharedText = sharedText + "\n\n" + suffix;
                }
                // arena.ai has no ?q= API: load home and inject into the chat box via JS.
                pendingSharedText = sharedText;
                chatWebView.loadUrl(ARENA_HOME);
            } else {
                chatWebView.loadUrl(ARENA_HOME);
            }
        } else if (Intent.ACTION_ASSIST.equals(action)) {
            Log.d(TAG, "Assistance shortcut triggered");
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            // trigger_voice_assistant is repurposed on arena.ai as auto_focus_on_assist:
            // arena.ai has no voice-chat DOM to trigger, so we open/focus the chat input.
            boolean autoFocusOnAssist = prefs.getBoolean("trigger_voice_assistant", true);
            String currentUrl = chatWebView.getUrl();
            if (isArenaUrl(currentUrl)) {
                if (autoFocusOnAssist) {
                    safeEvaluateJavascript(chatWebView, ARENA_DOM_JS);
                    safeEvaluateJavascript(chatWebView, AUTO_FOCUS_JS);
                }
            } else {
                pendingAutoFocus = autoFocusOnAssist;
                chatWebView.loadUrl(ARENA_HOME);
            }
        } else if (Intent.ACTION_MAIN.equals(action) || action == null) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean autoFocus = prefs.getBoolean("auto_focus_keyboard", true);
            if (autoFocus) {
                String currentUrl = chatWebView.getUrl();
                if (isArenaUrl(currentUrl)) {
                    safeEvaluateJavascript(chatWebView, ARENA_DOM_JS);
                    chatWebView.evaluateJavascript(AUTO_FOCUS_JS, null);
                } else {
                    pendingAutoFocus = true;
                    chatWebView.loadUrl(ARENA_HOME);
                }
            } else {
                if (chatWebView.getUrl() == null || chatWebView.getUrl().isEmpty()
                        || chatWebView.getUrl().equals("about:blank")) {
                    chatWebView.loadUrl(ARENA_HOME);
                }
            }
            if (prefs.getBoolean("prompt_on_launch", false)) {
                showPromptOnLaunchDialog();
            }
        } else {
            if (chatWebView.getUrl() == null || chatWebView.getUrl().isEmpty()
                    || chatWebView.getUrl().equals("about:blank")) {
                chatWebView.loadUrl(ARENA_HOME);
            }
        }
    }

    private android.app.Dialog promptDialog = null;

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
                        if (promptDialog != null && promptDialog.isShowing()) {
                            promptDialog.dismiss();
                        }
                        if (text == null || text.trim().isEmpty()) return;

                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        String suffix = prefs.getString("ask_arena_suffix", "");
                        String promptText = text.trim();
                        if (suffix != null && !suffix.trim().isEmpty()) {
                            promptText = promptText + "\n\n" + suffix;
                        }

                        // arena.ai has no ?q= API: inject into the chat box via JS.
                        if (isArenaUrl(chatWebView.getUrl())) {
                            pendingSharedText = promptText;
                            injectPendingSharedText();
                        } else {
                            pendingSharedText = promptText;
                            chatWebView.loadUrl(ARENA_HOME);
                        }
                    });
                }

                @JavascriptInterface
                public void dismissPrompt() {
                    runOnUiThread(() -> {
                        if (promptDialog != null && promptDialog.isShowing()) {
                            promptDialog.dismiss();
                        }
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

    private class MyWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();

            // Stay inside the app ONLY for arena.ai hosts
            if (isInternalHost(host)) {
                return false;
            }

            // Redirect all other external links (citations, etc.) to the default browser
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            progressBar.setVisibility(View.VISIBLE);
            if (isSafeMode) return;
            safeEvaluateJavascript(view, ARENA_DOM_JS);
            safeEvaluateJavascript(view, BLOB_JS);
            safeEvaluateJavascript(view, CLIPBOARD_JS);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);
            if (isSafeMode) return;
            safeEvaluateJavascript(view, ARENA_DOM_JS);
            safeEvaluateJavascript(view, BLOB_JS);
            safeEvaluateJavascript(view, CLIPBOARD_JS);
            SharedPreferences prefs = view.getContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            // "Ask Arena" shared text wins over plain auto-focus (injection focuses too).
            if (pendingSharedText != null && !pendingSharedText.isEmpty()) {
                injectPendingSharedText();
                pendingAutoFocus = false;
            } else if (pendingAutoFocus || prefs.getBoolean("auto_focus_keyboard", true)) {
                safeEvaluateJavascript(view, AUTO_FOCUS_JS);
                pendingAutoFocus = false;
            }
            if (isArenaUrl(url)) {
                safeEvaluateJavascript(view, DUMP_CHATS_JS);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (request.isForMainFrame()) {
                    String url = request.getUrl().toString();
                    if (isArenaUrl(url)) {
                        Log.d(TAG, "Connection failed for main frame, replacing with local chats: " + error.getDescription());
                        runOnUiThread(() -> {
                            view.loadUrl("file:///android_asset/chats_viewer.html");
                        });
                        return; // Prevent calling super, which displays the default "Webpage not available" error page
                    }
                }
            }
            super.onReceivedError(view, request, error);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (isArenaUrl(failingUrl)) {
                Log.d(TAG, "Connection failed (legacy), replacing with local chats: " + description);
                runOnUiThread(() -> {
                    view.loadUrl("file:///android_asset/chats_viewer.html");
                });
                return; // Prevent calling super
            }
            super.onReceivedError(view, errorCode, description, failingUrl);
        }
    }

    private class MyWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (isSafeMode) return;
            if (newProgress > 5) {
                safeEvaluateJavascript(view, ARENA_DOM_JS);
                safeEvaluateJavascript(view, BLOB_JS);
                safeEvaluateJavascript(view, CLIPBOARD_JS);
            }
            if (newProgress == 100) {
                SharedPreferences prefs = view.getContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                if ((pendingSharedText == null || pendingSharedText.isEmpty())
                        && prefs.getBoolean("auto_focus_keyboard", true)) {
                    safeEvaluateJavascript(view, AUTO_FOCUS_JS);
                }
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
                        request.grant(new String[] { resource });
                        return;
                    }
                }
                request.deny();
            });
        }

        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                WebChromeClient.FileChooserParams fileChooserParams) {
            if (pendingSharedFileUri != null) {
                filePathCallback.onReceiveValue(new Uri[] { pendingSharedFileUri });
                pendingSharedFileUri = null;
                return true;
            }
            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(null);
            }
            mUploadMessage = filePathCallback;

            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("Select Option");
            builder.setItems(new CharSequence[]{"Camera", "File Manager"}, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
                        } else {
                            openCamera();
                        }
                    } else {
                        openFileManager();
                    }
                }
            });
            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    if (mUploadMessage != null) {
                        mUploadMessage.onReceiveValue(null);
                        mUploadMessage = null;
                    }
                }
            });
            builder.show();
            return true;
        }
    }

    private void openCamera() {
        try {
            File photoFile = new File(getExternalCacheDir(), "camera_photo_" + System.currentTimeMillis() + ".jpg");
            cameraImageUri = FileProvider.getUriForFile(this, FILEPROVIDER_AUTH, photoFile);
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
            if (null == mUploadMessage)
                return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && intent != null) {
                String dataString = intent.getDataString();
                if (dataString != null) {
                    result = new Uri[] { Uri.parse(dataString) };
                }
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        } else if (requestCode == CAMERA_REQUEST_CODE) {
            if (null == mUploadMessage)
                return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && cameraImageUri != null) {
                result = new Uri[] { cameraImageUri };
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (chatWebView.canGoBack()) {
                    chatWebView.goBack();
                } else {
                    finish();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        // Privacy: clear the WebView cache on exit, but keep cookies (login session).
        clearCacheData();
        if (chatWebView != null) {
            chatWebView.destroy();
        }
        super.onDestroy();
    }
}
