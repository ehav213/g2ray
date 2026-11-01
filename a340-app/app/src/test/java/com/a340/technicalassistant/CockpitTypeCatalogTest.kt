package com.a340.technicalassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitTypeCatalogTest {
    @Test fun includesRequestedTsmTypes() {
        assertTrue(CockpitTypeCatalog.values.contains("OBSERVATIONS"))
        assertTrue(CockpitTypeCatalog.values.contains("SD FLAG(S)"))
        assertTrue(CockpitTypeCatalog.values.contains("STS-MAINTENANCE"))
        assertTrue(CockpitTypeCatalog.values.contains("STS-INOP SYSTEM"))
        assertTrue(CockpitTypeCatalog.values.contains("WARNING LIGHT(S)"))
    }

    @Test fun ecamWarningRemainsInDedicatedField() {
        assertFalse(CockpitTypeCatalog.values.contains("EWD WARNING(S)"))
    }
}
