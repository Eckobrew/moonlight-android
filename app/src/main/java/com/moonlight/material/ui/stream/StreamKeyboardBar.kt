package com.moonlight.material.ui.stream

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.viewinterop.AndroidView
import com.moonlight.material.core.state.StreamSessionController
import com.moonlight.material.stream.input.RemoteKeyboardInputView

/** Invisible IME endpoint: all typing goes directly to the PC. */
@Composable
fun StreamKeyboardBar(session: StreamSessionController) {
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    AndroidView(
        factory = { context ->
            RemoteKeyboardInputView(context,
                sendText = { session.sendUtf8Text(it) },
                sendKey = { session.sendHostKeyPress(it) },
                handleKey = { session.handleKeyEvent(it) }
            ).apply {
                postDelayed({
                    if (!isAttachedToWindow) return@postDelayed
                    requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }, 150)
            }
        },
        modifier = Modifier.size(1.dp)
    )
}
