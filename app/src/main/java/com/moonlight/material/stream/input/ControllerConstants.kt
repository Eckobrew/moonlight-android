package com.moonlight.material.stream.input

/**
 * Standard controller button flags from Limelight.h (lines 60-75).
 */
object ControllerButtonFlags {
    const val UP = 0x0001
    const val DOWN = 0x0002
    const val LEFT = 0x0004
    const val RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LS_CLK = 0x0040
    const val RS_CLK = 0x0080
    const val LB = 0x0100
    const val RB = 0x0200
    const val SPECIAL = 0x0400
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000
}

/**
 * Standard mouse button codes from Limelight.h / LiSendMouseButtonEvent.
 */
object MouseButtonCodes {
    const val BUTTON_LEFT: Byte = 0x01
    const val BUTTON_MIDDLE: Byte = 0x02
    const val BUTTON_RIGHT: Byte = 0x03
}
