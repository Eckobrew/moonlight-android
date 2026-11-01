package com.moonlight.material.stream.audio

interface AudioOutputBackend {
    val isRunning: Boolean
    val statusMessage: String? get() = null
    fun setMuted(muted: Boolean)
    fun start()
    fun stop()
    fun dispose()
}

/**
 * Serializes audio output lifecycle, routing, and focus operations.
 *
 * Ported from Sources/MoonlightMac/Pipeline/Audio/AudioPlaybackControl.swift.
 * Ensures:
 * - Focus policy and mute state survive engine replacement.
 * - Mute is applied before every start/restart.
 * - Generation checks prevent stale callbacks from restarting disposed engines.
 */
class AudioPlaybackControl {
    private val lock = Any()
    private var backend: AudioOutputBackend? = null
    private var generation: Long = 0
    private var wantsPlayback = false
    private var muteOnFocusLoss = false
    private var appFocused = false

    fun install(makeBackend: (onStateChanged: () -> Unit) -> AudioOutputBackend): Boolean {
        synchronized(lock) {
            invalidate()
            val installedGeneration = generation
            return try {
                val created = makeBackend {
                    synchronized(lock) {
                        if (generation == installedGeneration) {
                            recover()
                        }
                    }
                }
                backend = created
                created.setMuted(muteOnFocusLoss && !appFocused)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun configure(muteOnFocusLoss: Boolean, appFocused: Boolean) {
        synchronized(lock) {
            this.muteOnFocusLoss = muteOnFocusLoss
            this.appFocused = appFocused
            backend?.setMuted(muteOnFocusLoss && !appFocused)
        }
    }

    fun updateFocus(focused: Boolean) {
        synchronized(lock) {
            this.appFocused = focused
            backend?.setMuted(muteOnFocusLoss && !focused)
        }
    }

    fun start() {
        synchronized(lock) {
            if (backend == null) return
            wantsPlayback = true
            recover()
        }
    }

    fun stop() {
        synchronized(lock) {
            wantsPlayback = false
            backend?.stop()
        }
    }

    fun cleanup() {
        synchronized(lock) {
            invalidate()
        }
    }

    fun synchronize() {
        synchronized(lock) {}
    }

    private fun invalidate() {
        generation++
        wantsPlayback = false
        backend?.dispose()
        backend = null
    }

    private fun recover() {
        val currentBackend = backend ?: return
        if (!wantsPlayback) return

        try {
            currentBackend.setMuted(muteOnFocusLoss && !appFocused)
            if (!currentBackend.isRunning) {
                currentBackend.start()
            }
        } catch (ignored: Exception) {}
    }
}
