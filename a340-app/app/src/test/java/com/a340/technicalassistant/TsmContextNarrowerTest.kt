package com.a340.technicalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsmContextNarrowerTest {
    private fun ref(manual: String, value: String) = TsmCauseReferenceMapper.ManualRef(manual, value)

    @Test fun computer2ce3ExcludesFcscReferences() {
        val action = """
            (1) Replace the computer related to the maintenance message:
            · for 2CE3: Ref. AMM 27-93-34-000-801 and Ref. AMM 27-93-34-400-801 .
            · for 3CE1 or 3CE2: Ref. AMM 27-94-34-000-801 and Ref. AMM 27-94-34-400-801 .
        """.trimIndent()
        val refs = listOf(
            ref("AMM", "27-93-34-000-801"), ref("AMM", "27-93-34-400-801"),
            ref("AMM", "27-94-34-000-801"), ref("AMM", "27-94-34-400-801")
        )
        val selected = TsmContextNarrower.narrowReferences(action, refs, listOf("2CE3", "10CS1"))
        assertEquals(listOf("27-93-34-000-801", "27-93-34-400-801"), selected.map { it.reference })
    }

    @Test fun selected10cs1KeepsCommonServoAmmAndOnlyAsm29() {
        val action = """
            (3) If the fault continues:
            · replace the SERVO CTL-L OUTBD AIL, G (9CS1) or SERVO CTL-R OUTBD AIL, Y (9CS2) or SERVO CTL-L OUTBD AIL, Y (10CS1) or SERVO CTL-R OUTBD AIL, G (10CS2) related to the maintenance message Ref. AMM 27-14-51-000-802 and Ref. AMM 27-14-51-400-802 .
            (6) If the fault continues:
            . for 9CS1, 10CS1: Ref. ASM 27-98-29
            . for 9CS2, 10CS2: Ref. ASM 27-98-30.
        """.trimIndent()
        val refs = listOf(
            ref("AMM", "27-14-51-000-802"), ref("AMM", "27-14-51-400-802"),
            ref("ASM", "27-98-29"), ref("ASM", "27-98-30")
        )
        val selected = TsmContextNarrower.narrowReferences(action, refs, listOf("10CS1"))
        assertTrue(selected.any { it.reference == "27-14-51-000-802" })
        assertTrue(selected.any { it.reference == "27-14-51-400-802" })
        assertTrue(selected.any { it.reference == "27-98-29" })
        assertFalse(selected.any { it.reference == "27-98-30" })
    }

    @Test fun differentPrintedFinIsNeverPromoted() {
        val context = TsmContextNarrower.causeContext(
            "SERVO CTL-L OUTBD AIL, G (9CS1)", listOf("9CS1"),
            "replace 9CS1 or 10CS1 Ref. AMM 27-14-51-000-802",
            listOf(ref("AMM", "27-14-51-000-802")), listOf("2CE3", "10CS1")
        )
        assertEquals(TsmContextNarrower.MatchKind.NONE, context.kind)
    }

    @Test fun genericComputerCanBeContextMatchedBy2ce3() {
        val action = "for 2CE3: Ref. AMM 27-93-34-000-801 and Ref. AMM 27-93-34-400-801"
        val context = TsmContextNarrower.causeContext(
            "computer", emptyList(), action,
            listOf(ref("AMM", "27-93-34-000-801"), ref("AMM", "27-93-34-400-801")),
            listOf("2CE3", "10CS1")
        )
        assertEquals(TsmContextNarrower.MatchKind.CONTEXT, context.kind)
        assertEquals(listOf("2CE3"), context.selectedIdentifiers)
    }

    @Test fun melOutboardContextSelectsOnlyOutboardItem() {
        val selected = TsmContextNarrower.matchedMelRefs(
            listOf("27-14-01", "27-14-02"),
            mapOf("27-14-01" to "Outboard Aileron Servo-Control", "27-14-02" to "Inboard Aileron Servo-Control"),
            "FCPC3(2CE3)/L Y OUTR AIL SERVOCTL (10CS1) SV MON"
        )
        assertEquals(listOf("27-14-01"), selected)
    }

    @Test fun ipcSplitAileronAndWrongFinColumnAreRepairedFromVerifiedCauseId() {
        val repaired = TsmContextNarrower.repairIpc(
            "ERON", "SC4830-3", ".SERVOCONTROL-OUTBOARD AIL-",
            "17A 180A\nSC4830-3\n.SERVOCONTROL-OUTBOARD AIL-\nERON\nSEE 27-14-51-10 001A FOR DET\n10CS1\n001",
            listOf("10CS1")
        )
        assertEquals("10CS1", repaired.fin)
        assertEquals("SC4830-3", repaired.partNumber)
        assertEquals(".SERVOCONTROL-OUTBOARD AILERON", repaired.nomenclature)
        assertTrue(repaired.identifierRecoveredFromRaw)
    }
}
