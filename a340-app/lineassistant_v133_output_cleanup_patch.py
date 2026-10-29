from pathlib import Path

# 1) Keep PFR maintenance message separate from SOURCE / IDENTIFIERS metadata.
p = Path('app/src/main/java/com/a340/technicalassistant/PfrGeometryAnalyzerV2.kt')
s = p.read_text()

old = '''                val identifiers = parseIdentifiers(fault, metaStart)\n                val message = parseFaultMessage(fault, maintAtaDigits, metaStart)\n'''
new = '''                val identifiers = parseIdentifiers(fault, metaStart)\n                val message = parseFaultMessage(fault, maintAtaDigits, metaStart, identifiers, source)\n'''
if old not in s:
    raise SystemExit('1.3.3 patch: PFR message call anchor not found')
s = s.replace(old, new, 1)

old = '''    private fun parseFaultMessage(lines: List<OcrLine>, ataDigits: String, metaStart: Float): String {\n        val sorted = lines.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })\n        val ataIndex = sorted.indexOfFirst { parseAtaDigits(it.text) == ataDigits }\n        val afterAta = if (ataIndex >= 0) sorted.drop(ataIndex + 1) else sorted\n        return afterAta\n            .filter { it.box.left < metaStart }\n            .map { it.text }\n            .filterNot(::isFaultMetadata)\n            .filterNot(::isNoise)\n            .filterNot { parseAtaDigits(it) != null || parseUtc(it) != null || canonicalPhase(it) != null }\n            .joinToString(" ")\n            .replace(Regex("\\\\s*/\\\\s*"), "/")\n            .replace(Regex("\\\\s+"), " ")\n            .trim()\n    }\n'''
new = '''    private fun parseFaultMessage(\n        lines: List<OcrLine>,\n        ataDigits: String,\n        metaStart: Float,\n        identifiers: List<String>,\n        source: String?\n    ): String {\n        val sorted = lines.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })\n        val ataIndex = sorted.indexOfFirst { parseAtaDigits(it.text) == ataDigits }\n        val afterAta = if (ataIndex >= 0) sorted.drop(ataIndex + 1) else sorted\n        val metadataTokens = (identifiers + listOfNotNull(source))\n            .map { normalize(it).replace(Regex("[^A-Z0-9]"), "") }\n            .filter { it.isNotBlank() }\n            .toSet()\n        return afterAta\n            .filter { it.box.left < metaStart }\n            .map { it.text }\n            .filterNot(::isFaultMetadata)\n            .filterNot(::isNoise)\n            .filterNot { parseAtaDigits(it) != null || parseUtc(it) != null || canonicalPhase(it) != null }\n            .filterNot { text ->\n                val token = normalize(text).replace(Regex("[^A-Z0-9]"), "")\n                token in metadataTokens\n            }\n            .joinToString(" ")\n            .replace(Regex("\\\\s*/\\\\s*"), "/")\n            .replace(Regex("\\\\s+"), " ")\n            .trim()\n    }\n'''
if old not in s:
    raise SystemExit('1.3.3 patch: parseFaultMessage block not found')
s = s.replace(old, new, 1)
p.write_text(s)

# 2) AWM/IPC identifier cards must come only from this PFR/TSM record, not every token in branch evidence.
e = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
t = e.read_text()
old = '''        ids += input.identifiers\n        ids += extractIdentifiers(input.maintenanceMessage)\n        ids += tsm.identifiers\n        ids += extractIdentifiers(scoped).take(12)\n'''
new = '''        ids += input.identifiers\n        ids += extractIdentifiers(input.maintenanceMessage)\n        ids += tsm.identifiers\n'''
if old not in t:
    raise SystemExit('1.3.3 patch: support identifier scope anchor not found')
t = t.replace(old, new, 1)

t = t.replace(
    'LINE ASSISTANT 1.3.1 • PFR-FIRST EXACT TSM WORKFLOW',
    'LINE ASSISTANT 1.3.3 • PFR-FIRST EXACT TSM WORKFLOW',
    1
)
e.write_text(t)

# 3) Version bump after the existing 1.3.2 build-time version patch.
g = Path('app/build.gradle.kts')
u = g.read_text()
if 'versionCode = 30' not in u or 'versionName = "1.3.2"' not in u:
    raise SystemExit('1.3.3 patch: expected 1.3.2 build metadata not found')
u = u.replace('versionCode = 30', 'versionCode = 31', 1)
u = u.replace('versionName = "1.3.2"', 'versionName = "1.3.3"', 1)
g.write_text(u)

print('Line Assistant 1.3.3 PFR metadata separation and exact identifier scoping applied')
