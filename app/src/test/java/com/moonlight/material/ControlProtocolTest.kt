package com.moonlight.material

import com.moonlight.material.stream.control.ControlProtocol
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ControlProtocolTest {

    @Test
    fun testControllerPacketSerialization() {
        val packet = ControlProtocol.createControllerPacket(
            controllerNumber = 0,
            buttonFlags = ControlProtocol.BUTTON_A or ControlProtocol.BUTTON_START,
            leftTrigger = 128.toByte(),
            rightTrigger = 255.toByte(),
            leftStickX = 1000,
            leftStickY = -1000,
            rightStickX = 0,
            rightStickY = 0
        )

        assertEquals(26, packet.size)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(ControlProtocol.PACKET_TYPE_CONTROLLER.toShort(), buffer.getShort())
        assertEquals(22.toShort(), buffer.getShort()) // length
        buffer.getShort() // seq
        assertEquals(0.toShort(), buffer.getShort()) // controllerNumber
        buffer.getShort() // mask
        val buttons = buffer.getInt()
        assertTrue((buttons and ControlProtocol.BUTTON_A) != 0)
        assertTrue((buttons and ControlProtocol.BUTTON_START) != 0)
    }

    @Test
    fun testMouseMoveRelativePacket() {
        val packet = ControlProtocol.createMouseMoveRelativePacket(10, -5)
        assertEquals(10, packet.size)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        assertEquals(ControlProtocol.PACKET_TYPE_MOUSE_MOVE_REL.toShort(), buffer.getShort())
        assertEquals(6.toShort(), buffer.getShort())
        buffer.getShort()
        assertEquals(10.toShort(), buffer.getShort())
        assertEquals((-5).toShort(), buffer.getShort())
    }

    @Test
    fun testUtf8TextPacket() {
        val text = "Moonlight Stream"
        val packet = ControlProtocol.createUtf8TextPacket(text)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(6 + textBytes.size, packet.size)
    }
}
