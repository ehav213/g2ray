package com.a340.technicalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsmSymptomIndexParserTest {
    @Test fun standaloneFaultMessageOwnsItsRefTask() {
        val text = """
            Fault messages
            Ref. TASK 25-34-0
            0-810-804
            ATA Ref
            Message
            Source
            Class
            25-34-52
            RCS NO LIQUID (3800ML)
            VC
            1
        """.trimIndent()
        val r = TsmSymptomIndexParser.parse(text).single()
        assertEquals("25-34-00-810-804", r.taskRef)
        assertEquals("25-34-52", r.maintenanceAta)
        assertEquals("RCS NO LIQUID (3800ML)", r.maintenanceMessage)
        assertEquals("VC", r.source)
        assertEquals("1", r.faultClass)
    }

    @Test fun standalonePescUsesAtaToKeep913And915Distinct() {
        val text = """
            Fault messages
            Ref. TASK 23-33-0
            0-810-913
            ATA Ref
            Message
            Source
            Class
            23-33-00
            PES CTRL (8MK)/ DIR2 (102RH)
            CIDS2
            1
            Fault messages
            Ref. TASK 23-33-0
            0-810-915
            ATA Ref
            Message
            Source
            Class
            23-33-34
            PES CTRL (8MK)/ DIR2 (102RH)
            CIDS2
            1
        """.trimIndent()
        val rs = TsmSymptomIndexParser.parse(text)
        assertEquals(2, rs.size)
        assertEquals("23-33-00-810-913", rs.first { it.maintenanceAta == "23-33-00" }.taskRef)
        assertEquals("23-33-00-810-915", rs.first { it.maintenanceAta == "23-33-34" }.taskRef)
    }

    @Test fun warningAndCmsShareWarningRefTask() {
        val text = """
            Warnings/Malfunction
            Ref.
            TASK 73-21-85-810-939
            ATA Ref
            Message
            Type
            73-25
            ENG 3 FADEC SYS FAULT
            EWD WARNING(S)
            Fault messages
            ATA Ref
            Message
            Source
            Class
            73-21-34
            EEC (E3-4000KS)
            EEC3A
            1
            Identifiers
            EEC3B
        """.trimIndent()
        val r = TsmSymptomIndexParser.parse(text).single()
        assertEquals("73-21-85-810-939", r.taskRef)
        assertEquals("ENG 3 FADEC SYS FAULT", r.cockpitMessage)
        assertEquals("EEC (E3-4000KS)", r.maintenanceMessage)
        assertTrue(r.identifiers.contains("EEC3B"))
    }

    @Test fun smokeLoopBExactWarningRouteIs806NotStandalone804() {
        val text = """
            Fault messages
            Ref. TASK 26-10-0
            0-810-804
            ATA Ref
            Message
            Source
            Class
            26-10-00
            WRG: SMOKE LOOP B
            SDCU
            3
            Warnings/Malfunction
            Ref.
            TASK 26-10-00-810-806
            ATA Ref
            Message
            Type
            26-10
            SMOKE FLT REST DET FAULT
            EWD WARNING(S)
            Fault messages
            ATA Ref
            Message
            Source
            Class
            26-10-00
            SMOKE LOOP B
            SDCU
            1
        """.trimIndent()
        val rs = TsmSymptomIndexParser.parse(text)
        assertEquals("26-10-00-810-804", rs.first { it.cockpitMessage.isBlank() }.taskRef)
        assertEquals("26-10-00-810-806", rs.first { it.cockpitMessage == "SMOKE FLT REST DET FAULT" }.taskRef)
    }
}
