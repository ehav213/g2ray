package com.a340.technicalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsmCauseReferenceMapperTest {
    private val task = """
        TASK 23-33-00-810-913-A
        1. Possible Causes
        - PES Controller 8MK
        - Component 115RH
        - DIR1 101RH
        - DIR2 102RH
        - aircraft wiring

        References
        AMM TASK 23-33-34-000-801
        AMM TASK 23-33-34-400-801

        4. Fault Isolation
        (1) Do a check of the PES Controller 8MK.
            If the fault continues, remove the PES Controller 8MK
            (Ref. AMM TASK 23-33-34-000-801) and install it
            (Ref. AMM TASK 23-33-34-400-801).
        (2) Do a check of component 115RH.
            If necessary, remove 115RH (Ref. AMM TASK 23-73-19-000-801)
            and install 115RH (Ref. AMM TASK 23-73-19-400-801).
        (3) Do a test of DIR1 101RH and DIR2 102RH.
            If necessary remove the applicable director
            (Ref. AMM TASK 23-73-34-000-801) and install it
            (Ref. AMM TASK 23-73-34-400-801).
        (4) Check the aircraft wiring (Ref. AWM 23-33-01).

        5. Close-up
        End.
    """.trimIndent()

    @Test fun maps8mkOnlyFromItsFaultIsolationAction() {
        val r = TsmCauseReferenceMapper.route(task, "PES Controller 8MK", listOf("8MK"), false)
        assertTrue(r.matched)
        assertEquals(
            listOf("23-33-34-000-801", "23-33-34-400-801"),
            r.references.filter { it.manual == "AMM" }.map { it.reference }
        )
        assertFalse(r.references.any { it.reference == "23-73-19-000-801" })
    }

    @Test fun maps115rhToItsOwnRemovalAndInstallation() {
        val r = TsmCauseReferenceMapper.route(task, "Component 115RH", listOf("115RH"), false)
        assertEquals(
            listOf("23-73-19-000-801", "23-73-19-400-801"),
            r.references.filter { it.manual == "AMM" }.map { it.reference }
        )
    }

    @Test fun mapsBothCidsDirectorsToSharedDirectorReferences() {
        for (fin in listOf("101RH", "102RH")) {
            val r = TsmCauseReferenceMapper.route(task, "CIDS Director $fin", listOf(fin), false)
            assertEquals(
                listOf("23-73-34-000-801", "23-73-34-400-801"),
                r.references.filter { it.manual == "AMM" }.map { it.reference }
            )
        }
    }

    @Test fun wiringUsesOnlyReferenceInItsOwnAction() {
        val r = TsmCauseReferenceMapper.route(task, "aircraft wiring", emptyList(), true)
        assertEquals(listOf("23-33-01"), r.references.filter { it.manual == "AWM" }.map { it.reference })
        assertFalse(r.references.any { it.manual == "AMM" })
    }

    @Test fun possibleCauseAndReferenceTableDoNotCreateFalseRelationship() {
        val noSpecific = """
            1. Possible Causes
            - 8MK
            References
            AMM TASK 23-33-34-000-801
            4. Fault Isolation
            (1) Check a different component 999XX.
            5. Close-up
        """.trimIndent()
        val r = TsmCauseReferenceMapper.route(noSpecific, "8MK", listOf("8MK"), false)
        assertFalse(r.matched)
        assertTrue(r.references.isEmpty())
    }
}
