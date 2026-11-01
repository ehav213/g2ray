package com.a340.technicalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsmCockpitStatusIndexParserTest {
    @Test fun parsesAllEightSidecarTypesFromRealTsmShapes() {
        val text = """
            Warnings/Malfunction Ref.
            TASK 77-00-00-810-859
            ATA Ref Message Type
            ENG 1 / CONTROL / Slow or hung acceleration OBSERVATIONS

            Warnings/Malfunction Ref.
            TASK 77-00-00-810-860
            ATA Ref Message Type
            ENG 1 / INDICATING / N1 VIB indication flashing green SD FLAG(S)

            Warnings/Malfunction Ref.
            TASK 21-31-00-810-892
            ATA Ref Message Type
            AIR / PRESS / V/S- Needle and digital indication pulses SD ADVISORY(IES)

            Warnings/Malfunction Ref.
            TASK 21-21-00-810-858
            ATA Ref Message Type
            21-21 COND ONE CAB FAN FAULT EWD WARNING(S)
            AIR / CRG VENT / P/bsw- AIR OVHT COND FANS RESET-FAULT light on WARNING LIGHT(S)

            Warnings/Malfunction Ref.
            TASK 22-70-00-810-827
            ATA Ref Message Type
            22-70 AUTO FLT FM 1 FAULT EWD WARNING(S)
            AFS / FLAG ON CAPT ND MAP NOT AVAIL ND FLAG(S)

            Warnings/Malfunction Ref.
            TASK 22-10-00-810-845
            ATA Ref Message Type
            AFS / 2FD2 comes into view in the FMA zone (on both PFDs) PFD FLAG(S)

            Warnings/Malfunction Ref.
            TASK 24-30-00-810-827
            ATA Ref Message Type
            ELEC / DC / BAT 2 OFF warning does not appear EWD FLAG(S)

            Warnings/Malfunction Ref.
            TASK 24-24-00-810-809
            ATA Ref Message Type
            ELEC / AC EMER / CSM/G GCU red LED on INDICATOR(S)
        """.trimIndent()

        val records = TsmCockpitStatusIndexParser.parse(text)
        val expected = mapOf(
            "77-00-00-810-859" to "OBSERVATIONS",
            "77-00-00-810-860" to "SD FLAG(S)",
            "21-31-00-810-892" to "SD ADVISORY(IES)",
            "21-21-00-810-858" to "WARNING LIGHT(S)",
            "22-70-00-810-827" to "ND FLAG(S)",
            "22-10-00-810-845" to "PFD FLAG(S)",
            "24-30-00-810-827" to "EWD FLAG(S)",
            "24-24-00-810-809" to "INDICATOR(S)"
        )
        expected.forEach { (task, type) ->
            assertTrue("missing $task / $type", records.any { it.taskRef == task && it.cockpitType == type })
        }
    }

    @Test fun precedingEwdWarningDoesNotContaminateBlankAtaRow() {
        val text = """
            Warnings/Malfunction Ref.
            TASK 22-70-00-810-827
            ATA Ref Message Type
            22-70 AUTO FLT FM 1 FAULT EWD WARNING(S)
            AFS / FLAG ON CAPT ND MAP NOT AVAIL ND FLAG(S)
            Fault messages
            ATA Ref Message Source Class
            22-83-34 FMGEC1(1CA1) AFS 1
        """.trimIndent()
        val records = TsmCockpitStatusIndexParser.parse(text)
        val nd = records.single { it.cockpitType == "ND FLAG(S)" }
        assertEquals("", nd.cockpitAta)
        assertEquals("AFS / FLAG ON CAPT ND MAP NOT AVAIL", nd.cockpitMessage)
        assertFalse(nd.cockpitMessage.contains("AUTO FLT FM 1 FAULT"))
        assertFalse(records.any { it.cockpitType == "EWD WARNING(S)" })
    }

    @Test fun warningLightIsSeparatedFromEwdWarningInSameTask() {
        val text = """
            Warnings/Malfunction Ref.
            TASK 21-21-00-810-858
            ATA Ref Message Type
            21-21 COND ONE CAB FAN FAULT EWD WARNING(S)
            AIR / CRG VENT / P/bsw- AIR OVHT COND FANS RESET-FAULT
            light on
            WARNING LIGHT(S)
            Fault messages
            ATA Ref Message Source Class
            21-21-51 RECIRCULATION FAN 1 (211HG) VC 1
        """.trimIndent()
        val row = TsmCockpitStatusIndexParser.parse(text).single()
        assertEquals("21-21-00-810-858", row.taskRef)
        assertEquals("WARNING LIGHT(S)", row.cockpitType)
        assertEquals("AIR / CRG VENT / P/bsw- AIR OVHT COND FANS RESET-FAULT light on", row.cockpitMessage)
    }

    @Test fun parsesSplitAtaRefMessageTypeHeader() {
        val text = """
            Warnings/Malfunction Ref.
            TASK 24-24-00-810-809
            ATA Ref
            Message
            Type
            ELEC / AC EMER / CSM/G GCU red LED on
            INDICATOR(S)
        """.trimIndent()
        val row = TsmCockpitStatusIndexParser.parse(text).single()
        assertEquals("INDICATOR(S)", row.cockpitType)
        assertEquals("ELEC / AC EMER / CSM/G GCU red LED on", row.cockpitMessage)
    }
}
