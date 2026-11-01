package com.moonlight.material.input

import com.moonlight.material.stream.input.RemoteKeyboardState
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteKeyboardStateTest {
    @Test fun composingDoesNotSendPartialTextOrDuplicateCommittedText() {
        val sent = mutableListOf<String>()
        val keyboard = RemoteKeyboardState({ sent += it }, {}, {})
        keyboard.compose("n")
        keyboard.compose("に")
        assertEquals(emptyList<String>(), sent)
        keyboard.commit("日本語 🎮")
        keyboard.finishComposition()
        assertEquals(listOf("日本語 🎮"), sent)
    }
    @Test fun backspaceOnEmptyInputReachesTheHost() {
        val sent = mutableListOf<String>()
        val keyboard = RemoteKeyboardState({ sent += it }, { sent += "BACK" }, { sent += "DELETE" })
        keyboard.deleteSurrounding(2, 1)
        assertEquals(listOf("BACK", "BACK", "DELETE"), sent)
    }
    @Test fun deletingCompositionDoesNotDeleteRemoteText() {
        val sent = mutableListOf<String>()
        val keyboard = RemoteKeyboardState({ sent += it }, { sent += "BACK" }, {})
        keyboard.compose("ab")
        keyboard.deleteSurrounding(1, 0)
        keyboard.finishComposition()
        assertEquals(listOf("a"), sent)
    }
}
