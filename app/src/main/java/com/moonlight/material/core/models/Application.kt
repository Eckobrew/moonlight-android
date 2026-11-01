package com.moonlight.material.core.models

import kotlinx.serialization.Serializable

@Serializable
enum class AppControl(val id: Int) {
    RESUME(1),
    DISCONNECT_MONITOR(2),
    DISCONNECT_INPUT(3),
    TERMINATE(4),
    MONITOR(5),
    INPUT(6),
    RUNNING_GAME(7);

    companion object {
        fun fromId(id: Int): AppControl? = entries.find { it.id == id }
    }
}

@Serializable
data class StreamApp(
    val id: Int,
    val title: String,
    val isAppCollectorGame: Boolean = false,
    val boxArtUrl: String? = null,
    val uuid: String? = null,
    val hostOrder: Int? = null,
    val artVersion: String? = null
) {
    val stableIdentity: String
        get() = uuid?.takeIf { it.isNotEmpty() }?.lowercase() ?: id.toString()

    val control: AppControl?
        get() {
            val roles = mapOf(
                2147483501 to AppControl.RESUME,
                2147483502 to AppControl.DISCONNECT_MONITOR,
                2147483503 to AppControl.DISCONNECT_INPUT,
                2147483504 to AppControl.TERMINATE,
                2147483505 to AppControl.MONITOR,
                2147483506 to AppControl.INPUT,
                2147483507 to AppControl.RUNNING_GAME,
                2147483511 to AppControl.RESUME,
                2147483514 to AppControl.TERMINATE,
                2147483515 to AppControl.MONITOR,
                2147483516 to AppControl.INPUT
            )
            roles[id]?.let { return it }
            if (id in 2147483601..2147483606) {
                return AppControl.fromId(id - 2147483600)
            }
            if (uuid != null && uuid.lowercase().startsWith("9a1c5a25-58fe-40e0-b9aa-7d3f0000000")) {
                val digit = uuid.lastOrNull()?.digitToIntOrNull()
                if (digit != null) {
                    return AppControl.fromId(digit)
                }
            }
            return null
        }

    val isHostAction: Boolean
        get() = control in listOf(AppControl.DISCONNECT_MONITOR, AppControl.DISCONNECT_INPUT, AppControl.TERMINATE)

    val isRemoteMonitor: Boolean
        get() = control == AppControl.MONITOR

    fun matchesRunning(host: Host): Boolean {
        if (!host.currentAppUUID.isNullOrEmpty() && !uuid.isNullOrEmpty()) {
            return host.currentAppUUID.equals(uuid, ignoreCase = true)
        }
        val hostAppId = host.currentAppId ?: return false
        if (hostAppId == 0) return false
        return hostAppId == id
    }
}
