package com.moonlight.material

import com.moonlight.material.core.utils.ConnectionDiagnostics
import org.junit.Assert.*
import org.junit.Test

class NativeDiagnosticsTest {
    @Test fun allTerminationFailuresKeepNativeCodes() {
        for (code in -104..-100) assertTrue(ConnectionDiagnostics.termination(code)!!.contains(code.toString()))
        assertNull(ConnectionDiagnostics.termination(0))
        assertTrue(ConnectionDiagnostics.termination(-999)!!.contains("-999"))
    }
    @Test fun earlyTerminationDoesNotDeclareProtectedContent() {
        val early = ConnectionDiagnostics.termination(-102)!!
        assertTrue(early.contains("capture/encoder"))
        assertTrue(ConnectionDiagnostics.termination(-103)!!.contains("protected content"))
    }
    @Test fun failuresHaveUsefulStageAndPortContextWithoutJni() {
        assertTrue(ConnectionDiagnostics.stageFailure(2, -5).contains("DNS"))
        assertTrue(ConnectionDiagnostics.stageFailure(6, -1).contains("codec"))
        val message = ConnectionDiagnostics.stageFailure(9, -110, "Video connection", "UDP 47998")
        assertTrue(message.contains("-110"))
        assertTrue(message.contains("UDP 47998"))
    }
}
