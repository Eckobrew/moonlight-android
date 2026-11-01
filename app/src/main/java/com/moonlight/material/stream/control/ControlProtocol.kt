package com.moonlight.material.stream.control

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ControlProtocol {

    // Packet Types
    const val PACKET_TYPE_KEYBOARD = 0x0006
    const val PACKET_TYPE_MOUSE_MOVE_REL = 0x0007
    const val PACKET_TYPE_MOUSE_BUTTON = 0x0008
    const val PACKET_TYPE_MOUSE_SCROLL = 0x0009
    const val PACKET_TYPE_CONTROLLER = 0x000A
    const val PACKET_TYPE_MOUSE_MOVE_ABS = 0x0015
    const val PACKET_TYPE_UTF8_TEXT = 0x0017
    const val PACKET_TYPE_EXT_CONTROLLER = 0x001E

    // Controller Buttons Bitmask
    const val BUTTON_A = 0x1000
    const val BUTTON_B = 0x2000
    const val BUTTON_X = 0x4000
    const val BUTTON_Y = 0x8000
    const val BUTTON_UP = 0x0001
    const val BUTTON_DOWN = 0x0002
    const val BUTTON_LEFT = 0x0004
    const val BUTTON_RIGHT = 0x0008
    const val BUTTON_START = 0x0010
    const val BUTTON_BACK = 0x0020
    const val BUTTON_LS = 0x0040 // Left Stick click
    const val BUTTON_RS = 0x0080 // Right Stick click
    const val BUTTON_LB = 0x0100
    const val BUTTON_RB = 0x0200
    const val BUTTON_GUIDE = 0x0400

    // Mouse Buttons
    const val BUTTON_LEFT_MOUSE = 0x01
    const val BUTTON_MIDDLE_MOUSE = 0x02
    const val BUTTON_RIGHT_MOUSE = 0x03
    const val BUTTON_X1_MOUSE = 0x04
    const val BUTTON_X2_MOUSE = 0x05

    fun createControllerPacket(
        controllerNumber: Short,
        buttonFlags: Int,
        leftTrigger: Byte,
        rightTrigger: Byte,
        leftStickX: Short,
        leftStickY: Short,
        rightStickX: Short,
        rightStickY: Short
    ): ByteArray {
        val buffer = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(PACKET_TYPE_CONTROLLER.toShort()) // 2 bytes
        buffer.putShort(22.toShort()) // length: 22 bytes payload
        buffer.putShort(0) // sequence
        buffer.putShort(controllerNumber)
        buffer.putShort(0) // active gamepad mask
        buffer.putInt(buttonFlags)
        buffer.put(leftTrigger)
        buffer.put(rightTrigger)
        buffer.putShort(leftStickX)
        buffer.putShort(leftStickY)
        buffer.putShort(rightStickX)
        buffer.putShort(rightStickY)
        return buffer.array()
    }

    fun createMouseMoveRelativePacket(deltaX: Short, deltaY: Short): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(PACKET_TYPE_MOUSE_MOVE_REL.toShort())
        buffer.putShort(6.toShort())
        buffer.putShort(0)
        buffer.putShort(deltaX)
        buffer.putShort(deltaY)
        return buffer.array()
    }

    fun createMouseMoveAbsolutePacket(x: Short, y: Short, screenWidth: Short, screenHeight: Short): ByteArray {
        val buffer = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(PACKET_TYPE_MOUSE_MOVE_ABS.toShort())
        buffer.putShort(10.toShort())
        buffer.putShort(0)
        buffer.putShort(x)
        buffer.putShort(y)
        buffer.putShort(screenWidth)
        buffer.putShort(screenHeight)
        return buffer.array()
    }

    fun createMouseButtonPacket(button: Byte, isDown: Boolean): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(PACKET_TYPE_MOUSE_BUTTON.toShort())
        buffer.putShort(4.toShort())
        buffer.put(if (isDown) 0x07.toByte() else 0x08.toByte())
        buffer.put(button)
        buffer.putShort(0)
        return buffer.array()
    }

    fun createMouseScrollPacket(scrollAmount: Short): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(PACKET_TYPE_MOUSE_SCROLL.toShort())
        buffer.putShort(4.toShort())
        buffer.putShort(scrollAmount)
        buffer.putShort(0)
        return buffer.array()
    }

    fun createKeyboardPacket(keyCode: Short, isDown: Boolean, modifiers: Byte = 0): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(PACKET_TYPE_KEYBOARD.toShort())
        buffer.putShort(6.toShort())
        buffer.put(if (isDown) 0x03.toByte() else 0x04.toByte())
        buffer.put(keyCode.toByte())
        buffer.put(modifiers)
        buffer.put(0.toByte())
        buffer.putShort(0)
        return buffer.array()
    }

    fun createUtf8TextPacket(text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(6 + textBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(PACKET_TYPE_UTF8_TEXT.toShort())
        buffer.putShort((2 + textBytes.size).toShort())
        buffer.putShort(0)
        buffer.put(textBytes)
        return buffer.array()
    }
}
