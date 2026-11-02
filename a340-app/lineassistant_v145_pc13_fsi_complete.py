from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parent
PARSER = ROOT / 'app/src/main/java/com/a340/technicalassistant/TsmCockpitStatusIndexParser.kt'
ENGINE = ROOT / 'app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt'
GRADLE = ROOT / 'app/build.gradle.kts'
TEST = ROOT / 'app/src/test/java/com/a340/technicalassistant/TsmCockpitStatusIndexParserTest.kt'


def require_replace(text: str, old: str, new: str, label: str, count: int = 1) -> str:
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f'{label}: expected {count} match(es), found {actual}')
    return text.replace(old, new, count)

# FSI sidecar parser only
p = PARSER.read_text()
p = require_replace(
    p,
    'private val taskRef = Regex("(\\\\d{2}-\\\\d{2}-\\\\d{2}-810-\\\\d{3}(?:-[A-Z]\\\\d*)?)", RegexOption.IGNORE_CASE)',
    'private val taskRef = Regex("(\\\\d{2}-\\\\d{2}-\\\\d{2}-810-[A-Z0-9]{3}(?:-[A-Z]\\\\d*)?)", RegexOption.IGNORE_CASE)',
    'task regex'
)
p = require_replace(
    p,
    '        "SD ADVISORY(IES)",\n        "WARNING LIGHT(S)",',
    '        "SD ADVISORY(IES)",\n        "STS-MAINTENANCE",\n        "STS-INOP SYSTEM",\n        "WARNING LIGHT(S)",',
    'STS type insertion'
)
p = require_replace(
    p,
    '        "EWD FLAG(S)",\n        "INDICATOR(S)"\n    )',
    '        "EWD FLAG(S)",\n        "INDICATOR(S)",\n        "EWD WARNING(S)"\n    )',
    'EWD warning insertion'
)
p = require_replace(
    p,
    '''                line.startsWith("Identifiers", true) ||
                line.startsWith("Customer :", true) ||
                line.startsWith("Manual :", true) ||
                line.startsWith("Print Date", true) ||
                line.startsWith("End of document", true)) return i''',
    '''                line.startsWith("Identifiers", true) ||
                line.startsWith("End of document", true)) return i''',
    'page-break boundary relaxation'
)
p = require_replace(
    p,
    '''        return compact == "ATAREF" || compact == "MESSAGE" || compact == "TYPE" ||
            line.equals("Ref.", true) || line.startsWith("TASK ", true) ||
            line.startsWith("** ON A/C", true) || line.startsWith("EMB SB", true) ||
            line.startsWith("PRE SB", true) || line.startsWith("POST SB", true)''',
    '''        return compact == "ATAREF" || compact == "MESSAGE" || compact == "TYPE" ||
            line.equals("Ref.", true) || line.startsWith("TASK ", true) ||
            line.startsWith("** ON A/C", true) || line.startsWith("EMB SB", true) ||
            line.startsWith("PRE SB", true) || line.startsWith("POST SB", true) ||
            line.startsWith("Customer :", true) || line.startsWith("Manual :", true) ||
            line.startsWith("Selected applicability", true) || line.startsWith("Rev. Date", true) ||
            line.startsWith("Print Date", true) || line.startsWith("Page ", true) ||
            line.startsWith("© AIRBUS", true) || line.startsWith("Chapter Number", true)''',
    'page header/footer noise filtering'
)
p = require_replace(
    p,
    '''            if (type in supportedTypes && row.isNotBlank()) {''',
    '''            // Airbus FSI extraction sometimes emits the column label as part of a
            // blank-ATA row. It is a table label, not part of the warning wording.
            row = row.replace(Regex("(?i)^Message\\\\s+"), "").trim()

            if (type in supportedTypes && row.isNotBlank()) {''',
    'Message-column label cleanup'
)
PARSER.write_text(p)

# Candidate dedupe only. CMS-only path retains legacy identity.
e = ENGINE.read_text()
old_seen_key = '''                            val key = "${r.indexTaskRef}|${r.maintenanceAta}|${r.maintenanceMessage}|${r.source}|${r.faultClass}"
                            if (seen.add(key)) records += r'''
new_seen_key = '''                            val key = tsmCandidateDedupeKey(r)
                            if (seen.add(key)) records += r'''
if e.count(old_seen_key) != 2:
    raise SystemExit(f'legacy r-key sites: expected 2, found {e.count(old_seen_key)}')
e = e.replace(old_seen_key, new_seen_key, 1)

old_key = '''                            val key = "${record.indexFilename}|${record.indexPage}|${record.indexTaskRef}|${record.maintenanceMessage}|${record.cockpitMessage}|${record.source}"
                            if (dedupe.add(key)) candidates += record'''
new_key = '''                            val key = tsmCandidateDedupeKey(record)
                            if (dedupe.add(key)) candidates += record'''
actual = e.count(old_key)
if actual != 2:
    raise SystemExit(f'candidate dedupe call sites: expected 2, found {actual}')
e = e.replace(old_key, new_key)
anchor = '    private fun parseTsmIndexRecords(filename: String, page: Int, content: String): List<TsmIndexRecord> ='
helper = '''    /** PC13: cockpit/status FSI candidates dedupe strictly by Task + Type + Message.
     * Standalone CMS-only rows have no cockpit Type/Message and retain their legacy identity.
     */
    private fun tsmCandidateDedupeKey(record: TsmIndexRecord): String {
        val type = record.cockpitType.trim()
        val message = record.cockpitMessage.trim()
        return if (type.isNotBlank() && message.isNotBlank()) {
            listOf(record.indexTaskRef.trim().uppercase(Locale.US), type.uppercase(Locale.US), normalizeText(message)).joinToString("§")
        } else {
            listOf(record.indexFilename, record.indexPage.toString(), record.indexTaskRef, record.maintenanceMessage, record.source).joinToString("§")
        }
    }

'''
if e.count(anchor) != 1:
    raise SystemExit(f'parseTsmIndexRecords anchor: expected 1, found {e.count(anchor)}')
e = e.replace(anchor, helper + anchor, 1)
ENGINE.write_text(e)

# Version only
g = GRADLE.read_text()
g = require_replace(g, 'versionCode = 57', 'versionCode = 59', 'versionCode')
g = require_replace(g, 'versionName = "1.4.5-PC12-FULL-TYPE-ROUTING"', 'versionName = "1.4.5-PC13-FSI-COMPLETE"', 'versionName')
GRADLE.write_text(g)

# PC13 regression tests
TEST.write_text(r'''package com.a340.technicalassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsmCockpitStatusIndexParserTest {
    @Test fun parsesAllElevenFaultSymptomTypesFromRealTsmShapes() {
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
            TASK 27-93-00-810-901
            ATA Ref Message Type
            F/CTL / FCPC 3 MAINTENANCE STATUS STS-MAINTENANCE

            Warnings/Malfunction Ref.
            TASK 29-10-00-810-902
            ATA Ref Message Type
            HYD / GREEN RSVR LO AIR PRESS STS-INOP SYSTEM

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
            "21-21-00-810-858#EWD" to "EWD WARNING(S)",
            "27-93-00-810-901" to "STS-MAINTENANCE",
            "29-10-00-810-902" to "STS-INOP SYSTEM",
            "22-70-00-810-827" to "ND FLAG(S)",
            "22-10-00-810-845" to "PFD FLAG(S)",
            "24-30-00-810-827" to "EWD FLAG(S)",
            "24-24-00-810-809" to "INDICATOR(S)"
        )
        expected.forEach { (taskKey, type) ->
            val task = taskKey.substringBefore('#')
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
        val ewd = records.single { it.cockpitType == "EWD WARNING(S)" }
        assertEquals("22-70", ewd.cockpitAta)
        assertEquals("AUTO FLT FM 1 FAULT", ewd.cockpitMessage)
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
        val rows = TsmCockpitStatusIndexParser.parse(text)
        val ewd = rows.single { it.cockpitType == "EWD WARNING(S)" }
        assertEquals("21-21-00-810-858", ewd.taskRef)
        assertEquals("21-21", ewd.cockpitAta)
        assertEquals("COND ONE CAB FAN FAULT", ewd.cockpitMessage)
        val row = rows.single { it.cockpitType == "WARNING LIGHT(S)" }
        assertEquals("21-21-00-810-858", row.taskRef)
        assertEquals("AIR / CRG VENT / P/bsw- AIR OVHT COND FANS RESET-FAULT light on", row.cockpitMessage)
    }

    @Test fun parsesPossibleAssociatedWarningMalfunctionRows() {
        val text = """
            Warnings/Malfunction Ref.
            TASK 21-21-00-810-866
            ATA Ref Message Type
            21-21 COND TWO CAB FANS FAULT EWD WARNING(S)
            Fault messages
            ATA Ref Message Source Class
            21-21-51 RECIRCULATION FAN 2 (210HG) VC 1
            21-21-51 RECIRCULATION FAN 1 (211HG) VC 1
            Possible associated Warning/Malfunction
            ATA Ref Message Type
            21-21 TWO CAB FANS STS-INOP SYSTEM
        """.trimIndent()
        val rows = TsmCockpitStatusIndexParser.parse(text)
        assertTrue(rows.any {
            it.taskRef == "21-21-00-810-866" &&
                it.cockpitAta == "21-21" &&
                it.cockpitMessage == "TWO CAB FANS" &&
                it.cockpitType == "STS-INOP SYSTEM"
        })
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

    @Test fun acceptsThreeCharacterAlphanumericTaskAndStripsMessageColumnPrefix() {
        val text = """
            Warnings/Malfunction Ref. TASK 21-63-00-810-
            A00
            ATA Ref Message Type
            Message AIR / CAB TEMP / HOT AIR 1 Valve - Replaced by amber XX in flight only SD FLAG(S)
        """.trimIndent()
        val row = TsmCockpitStatusIndexParser.parse(text).single()
        assertEquals("21-63-00-810-A00", row.taskRef)
        assertEquals("SD FLAG(S)", row.cockpitType)
        assertEquals("AIR / CAB TEMP / HOT AIR 1 Valve - Replaced by amber XX in flight only", row.cockpitMessage)
    }
}
''')

# Hard scope checks for untouched technical core
core_untouched = {
    'app/src/main/java/com/a340/technicalassistant/TsmSymptomIndexParser.kt': '8e7ce477dfc9c26e2485333a93b8663b9272863da5c7d7d5fc941773246a07f5',
    'app/src/main/java/com/a340/technicalassistant/TsmCauseReferenceMapper.kt': 'ab1b051b07626006110bb862aeb3e697f549b625be7434c11a5e2a6dbb72e791',
    'app/src/main/java/com/a340/technicalassistant/SearchRepository.kt': '1d70c5b0dcbbf8fe80cbb20c04231396f9473b461d869adee44ba8736ba3b2f7',
    'app/src/main/java/com/a340/technicalassistant/PdfIndex.kt': 'ce78b78406c2cd39c9fbf6fa11396dd2f258ec4db2e0abedd07b53e297e8763e',
    'app/src/main/java/com/a340/technicalassistant/PfrGeometryAnalyzerV2.kt': '37ec455724a3a6071602dc187a224b2e673722194cf977785a8aa63ecb28da62',
    'app/src/main/java/com/a340/technicalassistant/PostFlightAnalyzer.kt': '99a2fff778a1bc3512b2d38f627c5a568a2a627109bd7e3b7e1ff35964223860',
    'app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt': '903565168f4f653a1846235928cc5eb2654e2450c33aa73852855d33843e9eaa',
    'app/src/main/java/com/a340/technicalassistant/CockpitTypeCatalog.kt': '06169a79067c4f395a1c89e82778a32e125e21075fe68383d5507338361afa1c',
    'app/src/main/java/com/a340/technicalassistant/CockpitTypeSelector.kt': 'de5f79145c5f441fea9857a9cc78062d56c025b986d3953d7777b85d7404103e',
    'app/src/main/res/layout/activity_line_assistant.xml': '7ae9195f163d8395b62b2fe893dc7b8e9c771d6cf093d32230dc2e62341911db',
}
for rel, expected in core_untouched.items():
    actual = hashlib.sha256((ROOT / rel).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f'PC13 scope violation: {rel}: {actual} != {expected}')

print('PC13 patch applied: 11 FSI Types + Possible Associated + alphanumeric task IDs + Task/Type/Message candidate dedupe')
print('Technical core outside FSI parser/candidate dedupe: frozen and hash-verified')
