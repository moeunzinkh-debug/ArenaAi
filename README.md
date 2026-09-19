# ArenaAssist

**ArenaAssist** is a lightweight, privacy-focused Android WebView wrapper designed
specifically for [arena.ai](https://arena.ai/) (AI chat / LLM leaderboard site).

This project is architecturally modeled directly on
[duckAssist](https://github.com/diekaiju/duckAssist) (a fork of
[gptAssist](https://github.com/woheller69/gptAssist), GPLv3), with **all**
duck.ai-specific logic replaced by arena.ai logic.

- Package / applicationId: `org.arenaassist.app`
- Version: 1.0.0 (versionCode 1)
- License: GPLv3 (see [LICENSE](LICENSE))

## Key Features

- 🎯 **Focused Interface**: fullscreen WebView for arena.ai with a native
  horizontal progress bar, pinch-to-zoom with persisted text zoom.
- 💬 **"Ask Arena" Integration**: select text in any app and choose
  **Ask Arena** (or share text/images/PDFs). The app opens in a separate
  window and injects the shared text into the arena.ai chat box via JavaScript.
- 🧲 **Bottom Drawer**: shares and assistant triggers open in a 92%-height
  bottom card with slide-up animation, drag-to-dismiss, and tap-outside-to-dismiss
  (configurable per trigger type).
- 🎙️ **Digital Assistant Integration**: set **ArenaAssist** as the default
  assistant app; the assistant shortcut opens/focuses the arena.ai chat input.
- 🌐 **Smart Link Handling**: only `arena.ai` / `www.arena.ai` / `lmarena.ai` /
  `www.lmarena.ai` stay in-app; citations and all other links open in the system
  browser.
- 🛡️ **Enhanced Privacy**: session cookies persist (login kept), third-party
  cookies enabled for reCAPTCHA, WebView metrics opted out, and the app cache is
  cleared on exit.
- 📁 **Native File Support**: upload via Camera/File Manager chooser, download
  `blob:`/`data:`/standard files to `Downloads/ArenaAssist/` with timestamped
  `Arena_Chat_yyyyMMdd_HHmmss.md` names.
- ⚙️ **HTML Settings**: drawer behavior, auto-focus options, and prompt
  suffixes, opened from a floating native settings button. Includes an offline
  Saved Chats Viewer.

## What changed vs duckAssist

| Area | duckAssist | ArenaAssist |
|---|---|---|
| Package | `org.duckassist.app` | `org.arenaassist.app` (manifest, FileProvider, prefs, DB) |
| Home URL | `https://duck.ai/` | `https://arena.ai/` |
| In-app hosts | `duck.ai`, `duckduckgo.com` | `arena.ai`, `www.arena.ai`, `lmarena.ai`, `www.lmarena.ai` |
| Third-party cookies | `false` | `true` (required by reCAPTCHA) |
| User agent | WebView default | WebView UA minus `; wv`, Chrome fallback UA |
| Share handoff | `?q=` + `handoff` JSON URL (duck.ai API) | Load `https://arena.ai/` + JS injection into chat input |
| Auto focus | duck.ai `Ctrl+Shift+O` shortcut hack | Generic `textarea`/`contenteditable` focus + keyboard |
| Voice trigger | `VOICE_JS` (duck.ai SVG selectors) | Removed; ASSIST now focuses chat input (`trigger_voice_assistant` = auto-focus-on-assist) |
| Continue chat | `CONTINUE_CHAT_JS` (duck.ai SVG selectors) | Removed |
| RTL resolver | `RTL_RESOLVER_JS` | Removed |
| Settings button | Cloned duck.ai web button | Floating native settings button overlay |
| Image-zoom monitor | duck.ai hashed CSS class | Removed |
| Download folder | `Downloads/duck.ai/` | `Downloads/ArenaAssist/` |
| Export filename | `Duck_AI_Chat_<ts>.md` | `Arena_Chat_<ts>.md` |
| Settings keys | `ask_duck_suffix`, `continue_last_chat`, `rtl_resolver` | `ask_arena_suffix` (+ `shared_doc_suffix`, `use_drawer_*`, `trigger_voice_assistant`, `auto_focus_keyboard`, `prompt_on_launch`) |
| Icon / texts | duck branding | Original arena/trophy/bubble icon, `ArenaAssist` branding |
| F-Droid metadata | duck.ai texts, changelogs 13–249 | arena.ai texts, changelog reset (`1.txt`) |

Architecture kept from duckAssist: `MainActivity` WebView core +
`safeEvaluateJavascript` Safe-Mode framework, `AskActivity` router,
`ChatActivity` bottom drawer, `AssistantService`/`AssistantSessionService`/
`AssistantRecognitionService`, `ChatDatabaseHelper` chat cache,
blob/download pipeline, `Android` JS bridge, HTML settings + chats viewer assets.

## arena.ai DOM selectors used

arena.ai is a React app with hashed CSS classes, so selectors are generic with
fallbacks (exposed to every page as `window.ArenaDom` in `MainActivity.java`):

- **Chat textarea**: `textarea[data-sentry-element="AutoResizeTextarea"]`,
  `textarea[placeholder]`, `textarea`, `div[role="textbox"]`,
  `[contenteditable="true"]`, `input[type="text"]`
  (fallback: first visible `textarea`/`input`/`contenteditable`/`role=textbox`)
- **Send button**: `form button[type="submit"]`, `button[type="submit"]`,
  `button[aria-label*="Send message"]`, `button[aria-label*="Send"]`
- **New-chat button**: `a[href="/"]` / `button` whose text contains "New Chat"
- **Mode switcher** (Battle / Agent / Side-by-Side / Direct): buttons/tabs whose
  text contains `battle`, `agent`, `side by side`, or `direct`, plus arena.ai's
  `button[role="combobox"]` model pickers

Shared text is written with the React-safe native value setter
(`HTMLTextAreaElement.prototype.value` + `input`/`change` events, `execCommand`
for `contenteditable`), retried via `MutationObserver` + interval for ~10s until
the input appears. Nothing is auto-sent; the user reviews and taps send.

`textarea[data-sentry-element="AutoResizeTextarea"]`, `button[type="submit"]`,
and `button[role="combobox"]` match selectors used by community lmarena.ai
userscripts (e.g. LMArena-Helper); the rest are defensive fallbacks.

## Build

Requirements: JDK 17, Android SDK (compileSdk/targetSdk 35, minSdk 24).

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

A GitHub Actions workflow (`.github/workflows/build.yml`) builds the debug APK on
every push/PR and uploads it as an artifact.

## Manual test checklist

1. Launcher opens `arena.ai` fullscreen with progress bar.
2. reCAPTCHA checkbox/verification renders inside the WebView.
3. External links/citations open in the system browser.
4. Select text → **Ask Arena** opens the drawer with text injected + keyboard shown.
5. Image/PDF share → red banner, then attach flow feeds the file to the chooser.
6. File download (incl. `blob:`) lands in `Downloads/ArenaAssist/`.
7. App can be set as the default assistant app; shortcut focuses chat input.
8. Back button navigates WebView history, then exits.

## Contributing / Donate

Issues and pull requests are welcome. If you like ArenaAssist, please star the
repository. Upstream thanks to the duckAssist / gptAssist authors.
