package com.moonlight.material

import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.utils.GameShortcutManager
import com.moonlight.material.core.utils.ShortcutLaunchRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ShortcutLaunchTest {
    private val uuid = "ed713cab-1c4c-4f08-8cb3-961da3210001"
    private val request = ShortcutLaunchRequest("old-local-id", "server-id", 12, uuid, "Old title")
    @Test fun hostIsResolvedByStableServerIdentityAfterAddressChanges() {
        val current = Host(name = "PC", address = "192.168.2.10", serverId = "SERVER-ID")
        assertEquals(current, GameShortcutManager.findHost(request, listOf(current)))
    }
    @Test fun applicationUuidSurvivesTitleAndNumericIdChanges() {
        val current = StreamApp(id = 99, title = "Renamed game", uuid = uuid)
        assertEquals(current, GameShortcutManager.findApp(request, listOf(current)))
    }
    @Test(expected = IOException::class) fun removedUuidDoesNotLaunchAnotherAppWithReusedId() {
        GameShortcutManager.findApp(request, listOf(StreamApp(id = 12, title = "Old title")))
    }
    @Test(expected = IOException::class) fun removedHostDoesNotUseAStaleEmbeddedAddress() {
        GameShortcutManager.findHost(request, emptyList())
    }
}
