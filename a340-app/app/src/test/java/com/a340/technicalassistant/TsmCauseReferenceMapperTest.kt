package com.a340.technicalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsmCauseReferenceMapperTest {
    private val cidsTask = """
        TASK 23-33-00-810-913-A
        1. Possible Causes
        - PES Controller 8MK
        - Component 115RH
        - DIR1 101RH
        - DIR2 102RH
        - aircraft wiring

        2. Job Set-up Information
        References
        AMM TASK 99-99-99-000-999

        4. Fault Isolation
        A. Troubleshoot the reported CIDS fault.
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

    private val smokeLoopB = """
        TASK 26-10-00-810-806-A
        1. Possible Causes
        - aircraft wiring
        2. Job Set-up Information
        Ref. AMM 26-16-00-740-801 BITE Test
        Ref. ASM 99-99-99
        3. Fault Confirmation
        Do the BITE test Ref. AMM 26-16-00-740-801.
        4. Fault Isolation
        Subtask 26-10-00-810-055-A
        A. If the test gives the maintenance message SMOKE LOOP B:
        (1) Do the troubleshooting procedure (Ref. TSM 26-17-00-810-811).
        (2) Make sure that all electrical connectors are connected to the SMOKE DETECTORS in loop B.
        (3) If the fault continues:
            Do a check and repair the aircraft wiring between each of the SMOKE DETECTORS in the
            detection loop B Ref. ASM 26-17-01.
        (4) If the fault continues:
            Do a check of the SDCU PIN PROGRAMMING (Ref. TSM 26-10-00-810-808).
            Refer to Ref. AMM 26-15-00-00, Ref. AMM 26-16-00-00, Ref. AMM 26-17-00-00
            for the locations of the smoke detectors in loop B.
        Subtask 26-10-00-710-078-A
        B. Do the test given in Para. 3.
        5. Close-up
    """.trimIndent()

    @Test fun maps8mkOnlyFromItsFaultIsolationAction() {
        val r = TsmCauseReferenceMapper.route(cidsTask, "PES Controller 8MK", listOf("8MK"), false)
        assertTrue(r.matched)
        assertEquals(
            listOf("23-33-34-000-801", "23-33-34-400-801"),
            r.references.filter { it.manual == "AMM" }.map { it.reference }
        )
        assertFalse(r.references.any { it.reference == "23-73-19-000-801" })
        assertFalse(r.branchReferences.any { it.reference == "99-99-99-000-999" })
    }

    @Test fun maps115rhToItsOwnRemovalAndInstallation() {
        val r = TsmCauseReferenceMapper.route(cidsTask, "Component 115RH", listOf("115RH"), false)
        assertEquals(
            listOf("23-73-19-000-801", "23-73-19-400-801"),
            r.references.filter { it.manual == "AMM" }.map { it.reference }
        )
    }

    @Test fun mapsBothCidsDirectorsToSharedDirectorReferences() {
        for (fin in listOf("101RH", "102RH")) {
            val r = TsmCauseReferenceMapper.route(cidsTask, "CIDS Director $fin", listOf(fin), false)
            assertEquals(
                listOf("23-73-34-000-801", "23-73-34-400-801"),
                r.references.filter { it.manual == "AMM" }.map { it.reference }
            )
        }
    }

    @Test fun smokeLoopBWiringKeepsCauseSpecificAsmAndEverySameBranchReference() {
        val r = TsmCauseReferenceMapper.route(smokeLoopB, "aircraft wiring", emptyList(), true)
        assertTrue(r.matched)
        assertEquals(listOf("26-17-01"), r.references.filter { it.manual == "ASM" }.map { it.reference })

        val branch = r.branchReferences.map { "${it.manual} ${it.reference}" }
        assertEquals(
            listOf(
                "TSM 26-17-00-810-811",
                "ASM 26-17-01",
                "TSM 26-10-00-810-808",
                "AMM 26-15-00-00",
                "AMM 26-16-00-00",
                "AMM 26-17-00-00"
            ),
            branch
        )
        assertFalse(branch.contains("AMM 26-16-00-740-801"))
        assertFalse(branch.contains("ASM 99-99-99"))
    }

    @Test fun possibleCauseAndJobSetupReferencesDoNotCreateFalseRelationship() {
        val noSpecific = """
            1. Possible Causes
            - 8MK
            2. Job Set-up Information
            Ref. AMM 23-33-34-000-801
            4. Fault Isolation
            A. Check a different component 999XX.
            5. Close-up
        """.trimIndent()
        val r = TsmCauseReferenceMapper.route(noSpecific, "8MK", listOf("8MK"), false)
        assertFalse(r.matched)
        assertTrue(r.references.isEmpty())
        assertTrue(r.branchReferences.isEmpty())
    }
}
