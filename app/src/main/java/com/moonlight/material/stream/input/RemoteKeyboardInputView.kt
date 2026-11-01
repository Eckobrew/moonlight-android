package com.moonlight.material.stream.input

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** Keeps IME pre-edit local and sends committed Unicode text exactly once. */
class RemoteKeyboardState(
    private val sendText: (String) -> Unit,
    private val backspace: () -> Unit,
    private val delete: () -> Unit
) {
    private var composing = ""
    fun compose(text: String) { composing = text }
    fun commit(text: String) {
        composing = ""
        if (text.isNotEmpty()) sendText(text)
    }
    fun finishComposition() {
        val text = composing
        composing = ""
        if (text.isNotEmpty()) sendText(text)
    }
    fun deleteSurrounding(before: Int, after: Int) {
        if (composing.isNotEmpty()) {
            composing = composing.dropLast(before.coerceAtLeast(0))
        } else {
            repeat(before.coerceIn(0, 100)) { backspace() }
        }
        repeat(after.coerceIn(0, 100)) { delete() }
    }
}

/** A real IME endpoint; no local text buffer or Send button is required. */
class RemoteKeyboardInputView(
    context: Context,
    sendText: (String) -> Unit,
    sendKey: (Short) -> Unit,
    private val handleKey: (KeyEvent) -> Boolean
) : View(context) {
    private val state = RemoteKeyboardState(sendText,
        { sendKey(KeycodeMappings.VK_BACK) }, { sendKey(KeycodeMappings.VK_DELETE) })
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Remote keyboard input"
    }
    override fun onCheckIsTextEditor() = true
    override fun onKeyDown(keyCode: Int, event: KeyEvent) = handleKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent) = handleKey(event) || super.onKeyUp(keyCode, event)
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                state.commit(text?.toString().orEmpty()); return true
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                state.compose(text?.toString().orEmpty()); return true
            }
            override fun finishComposingText(): Boolean { state.finishComposition(); return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                state.deleteSurrounding(beforeLength, afterLength); return true
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
                deleteSurroundingText(beforeLength, afterLength)
            override fun sendKeyEvent(event: KeyEvent) = handleKey(event)
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
            override fun getSelectedText(flags: Int): CharSequence? = null
            override fun getCursorCapsMode(reqModes: Int) = 0
        }
    }
}
