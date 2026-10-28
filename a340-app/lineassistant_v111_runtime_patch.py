from pathlib import Path
import runpy

runpy.run_path('lineassistant_v110_runtime_patch.py', run_name='__main__')

engine_path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
s = engine_path.read_text()

def rep(old: str, new: str, label: str, count: int = 1):
    global s
    actual = s.count(old)
    if actual < count:
        raise SystemExit(f'Line Assistant 1.1.1 patch failed: {label} (found {actual}, need {count})')
    s = s.replace(old, new, count)

rep(
'''        val parsed = runCatching { PostFlightAnalyzer.analyze(text).faults.firstOrNull() }.getOrNull()
        if (parsed != null) return resolvePfr(parsed, aircraftProfile)
''',
'''        val parsed = runCatching { PostFlightAnalyzer.analyze(text).faults.firstOrNull() }.getOrNull()
        val structuredPfr = parsed?.let {
            !it.ataFull.isNullOrBlank() ||
            !it.cockpitEffect.isNullOrBlank() ||
            !it.source.isNullOrBlank() ||
            !it.faultClass.isNullOrBlank() ||
            text.contains("COCKPIT EFFECT:", true) ||
            text.contains("MAINT ATA:", true) ||
            text.contains("SOURCE:", true) ||
            text.contains("CLASS:", true)
        } == true
        if (structuredPfr) return resolvePfr(parsed!!, aircraftProfile)
''',
'manual defect must not become synthetic PFR'
)

old_ata = '''        val ataOk = input.maintenanceAta.isNullOrBlank() || normalizeAta(input.maintenanceAta) == normalizeAta(record.maintenanceAta)'''
new_ata = '''        val ataOk = ataCompatible(input.maintenanceAta, record.maintenanceAta)'''
actual = s.count(old_ata)
if actual != 2:
    raise SystemExit(f'Line Assistant 1.1.1 patch failed: ATA compatibility expected 2 sites, found {actual}')
s = s.replace(old_ata, new_ata)

rep(
'''    private fun normalizeAta(text: String): String = text.filter(Char::isDigit).take(6)
''',
'''    private fun normalizeAta(text: String): String = text.filter(Char::isDigit).take(6)

    private fun ataCompatible(requested: String?, candidate: String): Boolean {
        if (requested.isNullOrBlank()) return true
        val r = normalizeAta(requested)
        val c = normalizeAta(candidate)
        return when {
            r.length >= 6 -> c.take(6) == r.take(6)
            r.length >= 4 -> c.take(4) == r.take(4)
            r.length >= 2 -> c.take(2) == r.take(2)
            else -> true
        }
    }
''',
'hierarchical ATA helper'
)

rep(
'''    private fun parseApplicability(block: String): String = Regex("(?im)^Applicable to:\\s*(.+?)\\s*$")
''',
'''    private fun parseApplicability(block: String): String = Regex("(?im)^\\s*Applicable to:\\s*(.+?)\\s*$")
''',
'MEL applicability leading whitespace'
)

rep(
'''        val matches = Regex("(?im)^ECAM Alert:\\s*(.+?)\\s*$").findAll(text).toList()
''',
'''        val matches = Regex("(?im)^\\s*ECAM Alert:\\s*(.+?)\\s*$").findAll(text).toList()
''',
'MEL ECAM leading whitespace'
)

rep(
'''            val applicability = lines.subList(0, start + 1).lastOrNull { it.startsWith("Applicable to:", true) }
                ?.substringAfter(':')?.trim().orEmpty()
''',
'''            val applicability = lines.subList(0, start + 1).lastOrNull { it.trimStart().startsWith("Applicable to:", true) }
                ?.substringAfter(':')?.trim().orEmpty()
''',
'MEL condition applicability leading whitespace'
)

rep(
'''        return when {
            upper.contains("NO DISPATCH") && refs.isEmpty() -> LineDispatchStatus.NO_DISPATCH
            upper.contains("MUST BE OPERATIVE") -> LineDispatchStatus.MUST_BE_OPERATIVE
            refs.isNotEmpty() -> LineDispatchStatus.CONDITIONAL
            upper.contains("MAY BE INOPERATIVE") -> LineDispatchStatus.CONDITIONAL
            else -> LineDispatchStatus.VERIFY_REQUIRED
        }
''',
'''        return when {
            upper.contains("MUST BE OPERATIVE") -> LineDispatchStatus.MUST_BE_OPERATIVE
            upper.contains("NO DISPATCH") && refs.isEmpty() -> LineDispatchStatus.NO_DISPATCH
            upper.contains("NO DISPATCH") && refs.isNotEmpty() -> LineDispatchStatus.VERIFY_REQUIRED
            refs.isNotEmpty() -> LineDispatchStatus.CONDITIONAL
            upper.contains("MAY BE INOPERATIVE") -> LineDispatchStatus.CONDITIONAL
            else -> LineDispatchStatus.VERIFY_REQUIRED
        }
''',
'MEL mixed branch fail closed'
)

rep(
'''        val scoped = tsm.branch?.evidence?.takeIf { it.isNotBlank() } ?: tsm.taskContent
''',
'''        val scoped = tsm.branch?.let { branch ->
            listOf(branch.isolation, branch.continuation)
                .filter { it.isNotBlank() }
                .joinToString("\\n")
                .takeIf { it.isNotBlank() }
        } ?: tsm.taskContent
''',
'strict branch-scoped downstream references'
)

engine_path.write_text(s)

activity_path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity_path.read_text()

old = '''        btnAi.isEnabled = result.evidencePack.isNotBlank() && models.installedModel() != null
        txtAiState.visibility = View.VISIBLE
        txtAiState.text = if (models.installedModel() == null) {
            "LOCAL AI NOT INSTALLED • verified MEL/TSM result remains available"
        } else {
            "AI READY • exact MEL/TSM evidence packet prepared"
        }
'''
new = '''        val verifiedEvidence = result.tsm != null ||
            (result.mel.status != LineDispatchStatus.NO_ENTRY && result.mel.exactBlock.isNotBlank())
        btnAi.isEnabled = verifiedEvidence && models.installedModel() != null
        txtAiState.visibility = View.VISIBLE
        txtAiState.text = when {
            !verifiedEvidence -> "LOCAL AI LOCKED • no verified MEL/TSM evidence resolved"
            models.installedModel() == null -> "LOCAL AI NOT INSTALLED • verified deterministic result remains available"
            else -> "AI READY • verified MEL/TSM evidence packet prepared"
        }
'''
if old not in a:
    raise SystemExit('Line Assistant 1.1.1 patch failed: AI gate UI')
a = a.replace(old, new, 1)

old = '''    private fun runGroundedAi() {
        val result = currentResult ?: return toast("Resolve a defect first")
        val model = models.installedModel() ?: return toast("No local AI model installed")
'''
new = '''    private fun runGroundedAi() {
        val result = currentResult ?: return toast("Resolve a defect first")
        val verifiedEvidence = result.tsm != null ||
            (result.mel.status != LineDispatchStatus.NO_ENTRY && result.mel.exactBlock.isNotBlank())
        if (!verifiedEvidence) return toast("No verified MEL/TSM evidence to explain")
        val model = models.installedModel() ?: return toast("No local AI model installed")
'''
if old not in a:
    raise SystemExit('Line Assistant 1.1.1 patch failed: AI runtime gate')
a = a.replace(old, new, 1)

activity_path.write_text(a)
print('Line Assistant 1.1.1 manual-defect, MEL and AI gating patch applied')
