package com.moonlight.material.core.utils

import com.moonlight.material.core.models.Host
import com.moonlight.material.core.models.StreamApp
import com.moonlight.material.core.security.CryptoUtils

/**
 * Computes deterministic shortcut bundle identifiers for pinned home screen launchers.
 * Matches ShortcutBundleStore.swift:5-18
 */
object ShortcutBundleStore {

    fun identifier(host: Host, appID: Int): String {
        val identity = "${host.serverId?.lowercase() ?: host.address.lowercase()}|$appID"
        val digest = CryptoUtils.hexString(CryptoUtils.sha256(identity.toByteArray(Charsets.UTF_8)))
        return "com.moonlight-stream.shortcut.$digest"
    }

    fun identifier(host: Host, app: StreamApp): String {
        if (app.uuid == null) return identifier(host, app.id)
        val identity = "${host.serverId?.lowercase() ?: host.address.lowercase()}|${app.stableIdentity}"
        val digest = CryptoUtils.hexString(CryptoUtils.sha256(identity.toByteArray(Charsets.UTF_8)))
        return "com.moonlight-stream.shortcut.$digest"
    }
}
