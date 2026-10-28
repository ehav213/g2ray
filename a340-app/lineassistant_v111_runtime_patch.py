from pathlib import Path
import runpy

# Materialize the stable 1.1 engine changes first.
runpy.run_path('lineassistant_v110_runtime_patch.py', run_name='__main__')

engine_path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
s = engine_path.read_text()

# Manual defect text must not be promoted to a synthetic PFR merely because
# PostFlightAnalyzer found a loose ATA/chapter token.
old = '''        val parsed = runCatching { PostFlightAnalyzer.analyze(text).faults.firstOrNull() }.getOrNull()\n        if (parsed != null) return resolvePfr(parsed, aircraftProfile)\n'''
new = '''        val parsed = runCatching { PostFlightAnalyzer.analyze(text).faults.firstOrNull() }.getOrNull()\n        val structuredPfr = parsed?.let {\n            !it.ataFull.isNullOrBlank() ||\n            !it.cockpitEffect.isNullOrBlank() ||\n            !it.source.isNullOrBlank() ||\n            !it.faultClass.isNullOrBlank() ||\n            text.contains("COCKPIT EFFECT:", true) ||\n            text.contains("MAINT ATA:", true) ||\n            text.contains("SOURCE:", true) ||\n            text.contains("CLASS:", true)\n        } == true\n        if (structuredPfr) return resolvePfr(parsed!!, aircraftProfile)\n'''
if old not in s:
    raise SystemExit('1.1.1 patch: resolveText structured-PFR guard not found')
s = s.replace(old, new, 1)

# ATA is hierarchical: 6 digits exact, 4 digits family, 2 digits chapter hint.
old_ata = 'input.maintenanceAta.isNullOrBlank() || normalizeAta(input.maintenanceAta) == normalizeAta(record.maintenanceAta)'
if s.count(old_ata) != 2:
    raise SystemExit(f'1.1.1 patch: ATA compatibility sites={s.count(old_ata)} expected=2')
s = s.replace(old_ata, 'ataCompatible(input.maintenanceAta, record.maintenanceAta)')

needle = '    private fun normalizeAta(text: String): String = text.filter(Char::isDigit).take(6)\n'
insert = '''    private fun normalizeAta(text: String): String = text.filter(Char::isDigit).take(6)\n\n    private fun ataCompatible(requested: String?, candidate: String): Boolean {\n        if (requested.isNullOrBlank()) return true\n        val r = normalizeAta(requested)\n        val c = normalizeAta(candidate)\n        return when {\n            r.length >= 6 -> c.take(6) == r.take(6)\n            r.length >= 4 -> c.take(4) == r.take(4)\n            r.length >= 2 -> c.take(2) == r.take(2)\n            else -> true\n        }\n    }\n'''
if needle not in s:
    raise SystemExit('1.1.1 patch: normalizeAta anchor not found')
s = s.replace(needle, insert, 1)

# MEL source formatting: tolerate leading whitespace.
if '(?im)^Applicable to:' in s:
    s = s.replace('(?im)^Applicable to:', '(?im)^\\\\s*Applicable to:')
elif '(?im)^\\\\s*Applicable to:' not in s:
    raise SystemExit('1.1.1 patch: MEL applicability regex not found')

if '(?im)^ECAM Alert:' in s:
    s = s.replace('(?im)^ECAM Alert:', '(?im)^\\\\s*ECAM Alert:')
elif '(?im)^\\\\s*ECAM Alert:' not in s:
    raise SystemExit('1.1.1 patch: ECAM regex not found')

old = 'it.startsWith("Applicable to:", true)'
if old in s:
    s = s.replace(old, 'it.trimStart().startsWith("Applicable to:", true)')
elif 'it.trimStart().startsWith("Applicable to:", true)' not in s:
    raise SystemExit('1.1.1 patch: condition applicability parser not found')

# Mixed MEL branch (NO DISPATCH + Refer to Item) must fail closed.
old = '''        return when {\n            upper.contains("NO DISPATCH") && refs.isEmpty() -> LineDispatchStatus.NO_DISPATCH\n            upper.contains("MUST BE OPERATIVE") -> LineDispatchStatus.MUST_BE_OPERATIVE\n            refs.isNotEmpty() -> LineDispatchStatus.CONDITIONAL\n            upper.contains("MAY BE INOPERATIVE") -> LineDispatchStatus.CONDITIONAL\n            else -> LineDispatchStatus.VERIFY_REQUIRED\n        }\n'''
new = '''        return when {\n            upper.contains("MUST BE OPERATIVE") -> LineDispatchStatus.MUST_BE_OPERATIVE\n            upper.contains("NO DISPATCH") && refs.isEmpty() -> LineDispatchStatus.NO_DISPATCH\n            upper.contains("NO DISPATCH") && refs.isNotEmpty() -> LineDispatchStatus.VERIFY_REQUIRED\n            refs.isNotEmpty() -> LineDispatchStatus.CONDITIONAL\n            upper.contains("MAY BE INOPERATIVE") -> LineDispatchStatus.CONDITIONAL\n            else -> LineDispatchStatus.VERIFY_REQUIRED\n        }\n'''
if old not in s:
    raise SystemExit('1.1.1 patch: dispatchStatus block not found')
s = s.replace(old, new, 1)

# Downstream references only from selected isolation branch/continuation.
old = '        val scoped = tsm.branch?.evidence?.takeIf { it.isNotBlank() } ?: tsm.taskContent\n'
new = '''        val scoped = tsm.branch?.let { branch ->\n            listOf(branch.isolation, branch.continuation)\n                .filter { it.isNotBlank() }\n                .joinToString("\\n")\n                .takeIf { it.isNotBlank() }\n        } ?: tsm.taskContent\n'''
if old not in s:
    raise SystemExit('1.1.1 patch: supportHits scope anchor not found')
s = s.replace(old, new, 1)

s = s.replace('LINE ASSISTANT 1.1 • DATABASE-NATIVE WORKFLOW', 'LINE ASSISTANT 1.1.1 • DATABASE-NATIVE WORKFLOW')
engine_path.write_text(s)

# AI is locked unless deterministic evidence exists.
activity_path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity_path.read_text()
old = '''        btnAi.isEnabled = result.evidencePack.isNotBlank() && models.installedModel() != null\n        txtAiState.visibility = View.VISIBLE\n        txtAiState.text = if (models.installedModel() == null) {\n            "LOCAL AI NOT INSTALLED • verified MEL/TSM result remains available"\n        } else {\n            "AI READY • exact MEL/TSM evidence packet prepared"\n        }\n'''
new = '''        val verifiedEvidence = result.tsm != null ||\n            (result.mel.status != LineDispatchStatus.NO_ENTRY && result.mel.exactBlock.isNotBlank())\n        btnAi.isEnabled = verifiedEvidence && models.installedModel() != null\n        txtAiState.visibility = View.VISIBLE\n        txtAiState.text = when {\n            !verifiedEvidence -> "LOCAL AI LOCKED • no verified MEL/TSM evidence resolved"\n            models.installedModel() == null -> "LOCAL AI NOT INSTALLED • deterministic result remains available"\n            else -> "AI READY • verified MEL/TSM evidence packet prepared"\n        }\n'''
if old not in a:
    raise SystemExit('1.1.1 patch: AI UI gate not found')
a = a.replace(old, new, 1)

old = '''    private fun runGroundedAi() {\n        val result = currentResult ?: return toast("Resolve a defect first")\n        val model = models.installedModel() ?: return toast("No local AI model installed")\n'''
new = '''    private fun runGroundedAi() {\n        val result = currentResult ?: return toast("Resolve a defect first")\n        val verifiedEvidence = result.tsm != null ||\n            (result.mel.status != LineDispatchStatus.NO_ENTRY && result.mel.exactBlock.isNotBlank())\n        if (!verifiedEvidence) return toast("No verified MEL/TSM evidence to explain")\n        val model = models.installedModel() ?: return toast("No local AI model installed")\n'''
if old not in a:
    raise SystemExit('1.1.1 patch: AI runtime gate not found')
a = a.replace(old, new, 1)
activity_path.write_text(a)

print('Line Assistant 1.1.1 resolver patch applied successfully')
