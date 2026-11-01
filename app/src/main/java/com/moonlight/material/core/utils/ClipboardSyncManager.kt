package com.moonlight.material.core.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.moonlight.material.core.models.Host
import com.moonlight.material.networking.SunshineHTTPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bidirectional clipboard synchronization between Android and Sunshine/Apollo host.
 * Matches ClipboardSyncManager.swift:6-180
 */
class ClipboardSyncManager(
    private val httpClient: SunshineHTTPClient
) {
    private var activeHost: Host? = null
    private var sendRetry = ClipboardRetryState()
    private var pullRetry = ClipboardRetryState()
    private var lastSyncedClipboard: String = ""

    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var clipboardManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private var isSending = false
    private var isPulling = false

    fun start(context: Context, host: Host) {
        stop()

        activeHost = host
        sendRetry = ClipboardRetryState()
        pullRetry = ClipboardRetryState()
        lastSyncedClipboard = ""

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager = cm

        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            val primaryClip = cm?.primaryClip
            if (primaryClip != null && primaryClip.itemCount > 0) {
                val text = primaryClip.getItemAt(0).text?.toString()
                if (!text.isNullOrEmpty() && text != lastSyncedClipboard) {
                    syncClipboardToHost(text)
                }
            }
        }
        cm?.addPrimaryClipChangedListener(clipListener)

        // Initial sync from host
        syncClipboardFromHost()

        // Background polling for host clipboard updates
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000L)
                syncClipboardFromHost()
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        clipListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        clipListener = null
        clipboardManager = null
        activeHost = null
        isSending = false
        isPulling = false
    }

    private fun syncClipboardToHost(text: String) {
        val host = activeHost ?: return
        val now = System.currentTimeMillis()
        if (isSending || !sendRetry.canAttempt(now)) return

        isSending = true
        scope.launch(Dispatchers.IO) {
            try {
                httpClient.sendClipboardContent(host, text)
                sendRetry = sendRetry.succeeded()
                lastSyncedClipboard = text
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    sendRetry = sendRetry.failed(e, System.currentTimeMillis())
                }
            } finally {
                isSending = false
            }
        }
    }

    private fun syncClipboardFromHost() {
        val host = activeHost ?: return
        val now = System.currentTimeMillis()
        if (isPulling || !pullRetry.canAttempt(now)) return

        isPulling = true
        scope.launch(Dispatchers.IO) {
            try {
                val content = httpClient.getClipboardContent(host)
                pullRetry = pullRetry.succeeded()
                if (content.isNotEmpty() && content != lastSyncedClipboard) {
                    lastSyncedClipboard = content
                    scope.launch(Dispatchers.Main) {
                        try {
                            val clip = ClipData.newPlainText("Sunshine", content)
                            clipboardManager?.setPrimaryClip(clip)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    pullRetry = pullRetry.failed(e, System.currentTimeMillis())
                }
            } finally {
                isPulling = false
            }
        }
    }
}
