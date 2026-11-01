package com.a340.technicalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsmCockpitStatusIndexParserTest {
    @Test fun parsesObservationWithBlankAtaRef() {
        val text = """
            Chapter Number - 77
            Warnings/Malfunction Ref.
            TASK 77-00-00-810-859
            ATA Ref Message Type
            ENG 1 / CONTROL / Slow or hung acceleration OBSERVATIONS
            Warnings/Malfunction Ref.
            TASK 77-00-00-810-860
            ATA Ref Message Type
            ENG 1 / INDICATING / N1 VIB indication flashing green SD FLAG(S)
        """.trimIndent()
        val records = TsmCockpitStatusIndexParser.parse(text)
        val obs = records.single { it.taskRef == "77-00-00-810-859" }
        assertEquals("", obs.cockpitAta)
        assertEquals("ENG 1 / CONTROL / Slow or hung acceleration", obs.cockpitMessage)
        assertEquals("OBSERVATIONS", obs.cockpitType)
    }

    @Test fun parsesSdFlagWithBlankAtaRef() {
        val text = """
            Warnings/Malfunction Ref.
            TASK 77-00-00-810-860
            ATA Ref Message Type
            ENG 1 / INDICATING / N1 VIB indication flashing green SD FLAG(S)
            Warnings/Malfunction Ref.
            TASK 77-00-00-810-861
            ATA Ref Message Type
            ENG 1 / INDICATING / N2 VIB indication flashing green SD FLAG(S)
        """.trimIndent()
        val records = TsmCockpitStatusIndexParser.parse(text)
        assertTrue(records.any {
            it.taskRef == "77-00-00-810-860" &&
            it.cockpitType == "SD FLAG(S)" &&
            it.cockpitMessage == "ENG 1 / INDICATING / N1 VIB indication flashing green"
        })
        assertTrue(records.any {
            it.taskRef == "77-00-00-810-861" &&
            it.cockpitMessage == "ENG 1 / INDICATING / N2 VIB indication flashing green"
        })
    }

    @Test fun doesNotClaimUnsupportedWarningTypes() {
        val text = """
            Warnings/Malfunction Ref.
            TASK 77-00-00-810-860
            ATA Ref Message Type
            ENG 1 / INDICATING / ENG1 N1 VIBRATION SD ADVISORY(IES)
        """.trimIndent()
        assertTrue(TsmCockpitStatusIndexParser.parse(text).isEmpty())
    }
}
