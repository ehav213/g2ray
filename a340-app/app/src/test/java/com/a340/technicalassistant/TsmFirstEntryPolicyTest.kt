package com.a340.technicalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TsmFirstEntryPolicyTest {
    @Test fun combinedEcamCmsLocksUniqueTaskEvenWithDualEecChannels() {
        assertTrue(TsmFirstEntryPolicy.shouldUseCockpitIndex("ENG 3 FADEC SYS FAULT"))
        assertEquals(
            "73-21-85-810-939",
            TsmFirstEntryPolicy.uniqueTaskRef(
                listOf("73-21-85-810-939", "73-21-85-810-939")
            )
        )
    }

    @Test fun conflictingTasksNeverAutoSelect() {
        assertNull(
            TsmFirstEntryPolicy.uniqueTaskRef(
                listOf("73-21-85-810-939", "22-30-00-810-803")
            )
        )
    }

    @Test fun exactCockpitCandidatesBlockBroadIdentifierFallback() {
        assertFalse(TsmFirstEntryPolicy.allowBroadFallback(1))
        assertFalse(TsmFirstEntryPolicy.allowBroadFallback(2))
        assertTrue(TsmFirstEntryPolicy.allowBroadFallback(0))
    }
}
