package org.arenaassist.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Transparent router for "Ask Arena" shares.
 *
 * Unlike duck.ai, arena.ai supports no "?q=" / handoff URL scheme, so shared
 * text is forwarded untouched to {@link ChatActivity}, which loads
 * https://arena.ai/ and injects the text into the chat box via JavaScript
 * (see MainActivity.buildArenaInjectJs). The configured suffix
 * ("ask_arena_suffix") is appended exactly once, in MainActivity.handleIntent.
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

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if (type.startsWith("image/") || "application/pdf".equals(type)) {
                Uri streamUri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (streamUri != null) {
                    Intent chatIntent = new Intent(this, ChatActivity.class);
                    chatIntent.setAction(Intent.ACTION_SEND);
                    chatIntent.setType(type);
                    chatIntent.putExtra(Intent.EXTRA_STREAM, streamUri);
                    chatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chatIntent);
                    return;
                }
            }
        }

        String sharedText = null;

        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (text != null) {
                sharedText = text.toString();
            }
        }

        if (sharedText != null) {
            Log.d(TAG, "Forwarding shared text to ChatActivity (" + sharedText.length() + " chars)");
            Intent chatIntent = new Intent(this, ChatActivity.class);
            chatIntent.setAction(Intent.ACTION_SEND);
            chatIntent.setType("text/plain");
            chatIntent.putExtra(Intent.EXTRA_TEXT, sharedText);
            chatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chatIntent);
        }
    }
}
