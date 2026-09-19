# ArenaAssist

**ArenaAssist** is a lightweight, privacy‑focused Android WebView wrapper for [arena.ai](https://arena.ai/)
(the AI chat & LLM leaderboard site, formerly LMArena).

It is modelled directly on the architecture of [duckAssist](https://github.com/diekaiju/duckAssist)
(itself a fork of [gptAssist](https://github.com/woheller69/gptAssist)) – same activities, same bottom‑drawer
window, same digital‑assistant hooks, same JavaScript injection framework – but every piece of duck.ai‑specific
logic has been replaced with arena.ai logic.

* Package / applicationId: `org.arenaassist.app`
* versionCode `1`, versionName `1.0.0`
* Java 11 · minSdk 24 · targetSdk 35 · compileSdk 35
* Dependencies: `androidx.webkit:webkit`, `androidx.swiperefreshlayout` (FreeDroidWarn dropped)

## Features

| Feature | How |
|---|---|
| Fullscreen arena.ai WebView + progress bar | `MainActivity` / `activity_main.xml` |
| Stay in app only for `arena.ai`, `www.arena.ai`, `lmarena.ai`, `www.lmarena.ai`; every other link → system browser | `MyWebViewClient.shouldOverrideUrlLoading` |
| reCAPTCHA support | third‑party cookies **enabled** (`setAcceptThirdPartyCookies(webview, true)`) + Chrome‑like UA (the `; wv` WebView token is stripped) |
| Session persistence, cache purged on stop/exit | `onStop()` → `clearCache(true)` + `CookieManager.flush()` |
| "Ask Arena" (share text / text‑selection `PROCESS_TEXT`) | `AskActivity` → `ChatActivity` (bottom drawer) → JS injection into chat box |
| Share image / PDF | saved to cache, banner "Tap the attach button…", auto‑fed to `onShowFileChooser` via `pendingSharedFileUri` |
| Digital assistant (`ACTION_ASSIST`, `VoiceInteractionService`) | `AssistantService`, `AssistantSessionService`, `AssistantRecognitionService`, `res/xml/assistant_service.xml` |
| Bottom drawer (92 % height, slide‑up 300 ms, drag/tap to dismiss) | `ChatActivity` |
| Offline fallback page on main‑frame error | `file:///android_asset/chats_viewer.html` |
| Downloads (`blob:`/`data:` via `FileReader` → `processBlob`, others via `DownloadManager`) | saved to `Downloads/ArenaAssist/`, markdown exports named `Arena_Chat_yyyyMMdd_HHmmss.md` |
| HTML settings dialog | `assets/settings.html`, opened from a native floating gear button |
| `android.webkit.WebView.MetricsOptOut=true` | manifest |

## What changed vs. duckAssist

| Area | duckAssist | ArenaAssist |
|---|---|---|
| Package | `org.duckassist.app` | `org.arenaassist.app` (namespace, manifest, FileProvider authority `org.arenaassist.app.fileprovider`) |
| Home URL | `https://duck.ai/` | `https://arena.ai/` |
| Allowed hosts | `*.duck.ai`, `*.duckduckgo.com` | exact match `arena.ai`, `www.arena.ai`, `lmarena.ai`, `www.lmarena.ai` |
| Third‑party cookies | `false` | `true` (needed by reCAPTCHA) |
| User agent | default WebView UA | Chrome‑like (WebView token removed) |
| Shared text | `duck.ai/chat?q=…&handoff={aiChatPrompt…}` deep link | load `https://arena.ai/`, then **inject** the text with JS (`INJECT_TEXT_JS_TEMPLATE`), retrying with `MutationObserver` + interval for ~10 s |
| Assistant | triggers duck.ai "voice chat" via SVG‑path selectors | just focuses the chat box (`trigger_voice_assistant` key kept, semantics = *auto‑focus on assist*) |
| JS removed | `VOICE_JS`, `CONTINUE_CHAT_JS`, `RTL_RESOLVER_JS`/`RTL_CLEANUP_JS`, `IMAGE_ZOOM_MONITOR_JS`, `SWIPE_SCROLL_JS`, `SETTINGS_INJECT_JS` (cloned web button), IndexedDB "savedAIChatData" dump | — |
| JS kept (generic) | `BLOB_JS`, `CLIPBOARD_JS` | unchanged |
| JS new | — | `ARENA_DOM_JS` (selector helpers), `AUTO_FOCUS_JS` (generic focus + `showSoftKeyboard`, no Ctrl+Shift+O), `INJECT_TEXT_JS_TEMPLATE`, generic `DUMP_CHATS_JS` |
| Settings button | cloned duck.ai settings button in the web page | native `ImageButton` overlay (bottom‑right) |
| Settings keys | + `continue_last_chat`, `rtl_resolver`, `ask_duck_suffix` | `use_drawer_assistant`, `use_drawer_shared`, `trigger_voice_assistant`, `auto_focus_keyboard`, `prompt_on_launch`, `ask_arena_suffix`, `shared_doc_suffix` (+ "Clear site data" action) |
| Download folder / filename | `Downloads/duck.ai`, `Duck_AI_Chat_*.md` | `Downloads/ArenaAssist`, `Arena_Chat_yyyyMMdd_HHmmss.md` |
| Icon | duck | original trophy‑in‑chat‑bubble adaptive icon (vector + generated PNGs, no official logo used) |
| SharedPreferences | `duck_assist_prefs` | `arena_assist_prefs` |

## arena.ai DOM selectors used

arena.ai is a Next.js/React app instrumented with Sentry, so `data-sentry-*` attributes are the most stable hooks.
All selectors live in `ARENA_DOM_JS` (`MainActivity.java`) and are tried in order, first *visible* match wins:

```text
chat input      textarea[data-sentry-element="AutoResizeTextarea"]
                textarea[name="text"]
                form textarea
                textarea[placeholder]
                textarea
                div[contenteditable="true"][role="textbox"]
                [contenteditable="true"]
                input[type="text"]

send button     form button[type="submit"]
                button[type="submit"]
                button[aria-label*="Send" i]
                button[aria-label*="Submit" i]
                button[data-sentry-component*="Send" i]

new chat        a/button[aria-label*="New chat" i]   or   a[href="/"] / button whose text == "New Chat"

mode switcher   button[role="combobox"]   or   button whose text matches
                /^(battle|agent|side by side|direct|auto)( mode)?$/i
```

Text is written into the textarea through the native `HTMLTextAreaElement.prototype.value` setter followed by
`input` + `change` events so React's controlled input picks it up. Helpers are exposed on `window.__arena`
(`findInput`, `findSendButton`, `findNewChatButton`, `findModeSwitcher`, `setInputValue`, `waitFor`) and can be
inspected from Chrome remote debugging (`chrome://inspect`, debugging is enabled in debug builds).

> If arena.ai changes its DOM, only `ARENA_DOM_JS` needs updating.

## Building

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

A GitHub Actions workflow (`.github/workflows/main.yml`) builds the debug APK on every push and uploads it as the
`ArenaAssist-debug` artifact.

## Test checklist

1. Launcher opens `https://arena.ai/` fullscreen.
2. reCAPTCHA checkbox/verification renders inside the WebView.
3. External links (citations, X/LinkedIn/YouTube, model provider pages) open in the system browser.
4. Select text anywhere → "Ask Arena" → drawer opens with the text typed into the chat box.
5. Share an image/PDF → banner shown → tap attach in arena.ai → shared file is used.
6. Export/download a file → lands in `Downloads/ArenaAssist/`.
7. Settings → Apps → Default apps → Digital assistant app → ArenaAssist; assistant gesture opens the drawer.
8. Back button navigates WebView history, closes app at root.

## License

GPLv3 – same as duckAssist / gptAssist. Parts derived from GMaps WV (GPLv3).
