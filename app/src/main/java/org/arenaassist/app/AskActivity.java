package org.arenaassist.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Transparent trampoline activity that receives ACTION_SEND / ACTION_PROCESS_TEXT
 * ("Ask Arena") and forwards the payload to {@link ChatActivity} which lives in its
 * own task (bottom drawer window).
 *
 * Unlike duckAssist, no "?q=…&handoff=…" URL is built – arena.ai has no such deep link.
 * The text is passed as an extra and typed into the chat box via JavaScript.
 */
public class AskActivity extends Activity {
    private static final String TAG = "ArenaAssistAsk";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        finish();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null
                && (type.startsWith("image/") || "application/pdf".equals(type))) {
            Uri streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (streamUri != null) {
                Intent chatIntent = new Intent(this, ChatActivity.class);
                chatIntent.setAction(Intent.ACTION_SEND);
                chatIntent.setType(type);
                chatIntent.putExtra(Intent.EXTRA_STREAM, streamUri);
                chatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chatIntent);
                return;
            }
        }

        String sharedText = null;
        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText == null) {
                CharSequence subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
                if (subject != null) sharedText = subject.toString();
            }
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (text != null) sharedText = text.toString();
        }

        if (sharedText != null && !sharedText.trim().isEmpty()) {
            SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
            String suffix = prefs.getString("ask_arena_suffix", "");
            if (suffix != null && !suffix.trim().isEmpty()) {
                sharedText = sharedText + "\n\n" + suffix.trim();
            }
            Intent chatIntent = new Intent(this, ChatActivity.class);
            chatIntent.setAction(Intent.ACTION_VIEW);
            chatIntent.setData(Uri.parse(MainActivity.HOME_URL));
            chatIntent.putExtra(MainActivity.EXTRA_INJECT_TEXT, sharedText);
            chatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chatIntent);
        } else {
            Log.d(TAG, "Nothing to ask – ignoring intent " + action);
        }
    }
}
