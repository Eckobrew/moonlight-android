package com.moonlight.material.core.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServiceHealth(
    val serviceName: String,
    val isRunning: Boolean,
    val lastError: String? = null,
    val details: String? = null
)

/**
 * Tracks runtime health and execution status of application subsystems.
 * Ported from Sources/MoonlightMac/Core/Utils/RuntimeServiceStatus.swift.
 */
object RuntimeServiceStatus {
    private val _statuses = MutableStateFlow<Map<String, ServiceHealth>>(emptyMap())
    val statuses: StateFlow<Map<String, ServiceHealth>> = _statuses.asStateFlow()

    fun updateStatus(serviceName: String, isRunning: Boolean, lastError: String? = null, details: String? = null) {
        val current = _statuses.value.toMutableMap()
        current[serviceName] = ServiceHealth(serviceName, isRunning, lastError, details)
        _statuses.value = current
    }

    fun getStatus(serviceName: String): ServiceHealth? {
        return _statuses.value[serviceName]
    }

    fun isServiceHealthy(serviceName: String): Boolean {
        val status = _statuses.value[serviceName] ?: return false
        return status.isRunning && status.lastError == null
    }

    fun clear() {
        _statuses.value = emptyMap()
    }
}
