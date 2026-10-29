from pathlib import Path

path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
s = path.read_text()

def rep(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'Line Assistant 1.2 PFR patch failed: {label}')
    s = s.replace(old, new, 1)

rep(
'''import com.google.mlkit.vision.text.TextRecognition\n''',
'''import com.google.mlkit.vision.text.Text\nimport com.google.mlkit.vision.text.TextRecognition\n''',
'ML Kit Text import')

rep(
'''                recognizer.process(image)\n                    .addOnSuccessListener { result -> handleOcrText(result.text.trim()) }\n''',
'''                recognizer.process(image)\n                    .addOnSuccessListener { result ->\n                        if (photoMode == PhotoMode.PFR) handlePfrOcr(result)\n                        else handleOcrText(result.text.trim())\n                    }\n''',
'geometry-aware PFR OCR routing')

marker = '''    private fun handleOcrText(text: String) {\n'''
if marker not in s:
    raise SystemExit('Line Assistant 1.2 PFR patch failed: handleOcrText marker')

helper = '''    private fun handlePfrOcr(result: Text) {\n        val text = result.text.trim()\n        if (text.isBlank()) return toast("No readable text detected")\n        ocrContainer.removeAllViews()\n        ocrContainer.visibility = View.VISIBLE\n\n        val parsed = PfrGeometryAnalyzer.analyze(result)\n        if (parsed.faults.isEmpty()) {\n            addOcrLabel("PFR • table geometry not confidently resolved")\n            addOcrInfo("The photo was read, but no exact PFR event row was accepted. No TSM task is guessed from loose OCR text.")\n            editQuery.setText(text)\n            status("PFR OCR • TABLE STRUCTURE NOT RESOLVED")\n            return\n        }\n\n        addOcrLabel("PFR TABLE EVENTS • ${parsed.faults.size} • GEOMETRY VERIFIED")\n        parsed.faults.forEach { fault ->\n            val kind = if (fault.category == "WARNING/MALFUNCTION") "WARNING/MALFUNCTION ONLY" else "CMS FAULT MESSAGE"\n            addOcrInfo(buildString {\n                append(kind).append('\\n')\n                append(fault.displaySummary())\n            })\n            addOcrButton("RESOLVE EXACT TSM • EVENT ${fault.number}") {\n                resolvePfrFault(fault, "PFR EVENT ${fault.number}")\n            }\n        }\n\n        // Resolve the first real CMS fault message first. If the PFR only has Warning/Malfunction entries, use the first one.\n        val primary = parsed.faults.firstOrNull { it.category == "CMS FAULT MESSAGE" } ?: parsed.faults.first()\n        resolvePfrFault(primary, "PFR EVENT ${primary.number}")\n    }\n\n'''
s = s.replace(marker, helper + marker, 1)
path.write_text(s)
print('Line Assistant 1.2 geometry-aware PFR photo patch applied')
