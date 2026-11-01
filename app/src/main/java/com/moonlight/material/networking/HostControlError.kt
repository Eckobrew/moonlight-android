package com.moonlight.material.networking

class HostControlError(
    val code: Int,
    override val message: String
) : Exception(message)
