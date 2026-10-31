from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parent
ENGINE = ROOT / "app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt"
GRADLE = ROOT / "app/build.gradle.kts"

def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

expected_before = {
    ENGINE: "d9d66b5a92663a357075fe17769cd57664d1a3f2bb6fab7f5003c4ccd3442d0f",
    GRADLE: "e8f93eb328d4788dfdb531851f3a5287aefcb86f824edc65ef0221de40239361",
}
for path, expected in expected_before.items():
    actual = sha(path)
    if actual != expected:
        raise SystemExit(f"PC6 refuses non-PC5 runtime {path}: {actual} != {expected}")

text = ENGINE.read_text()
old = '''        val cockpitCandidates = if (isCockpitOnlyInput(input)) findCockpitTsmCandidates(input) else emptyList()\n        val indexRecord = when {\n            cockpitCandidates.size == 1 -> cockpitCandidates.first()\n            cockpitCandidates.size > 1 -> null\n            else -> findTsmIndexRecord(input)\n        }\n        val selectionRequired = cockpitCandidates.size > 1\n        val directTask = if (indexRecord == null && !selectionRequired) findDirectTsmTask(input) else null\n'''
new = '''        // Exact cockpit warning/status is the authoritative first-entry field.\n        // Resolve it first, then use the CMS row only to disambiguate records\n        // under that same cockpit effect. Never let an identifier-only/FTS\n        // record override an exact cockpit warning.\n        val cockpitCandidates = if (TsmFirstEntryPolicy.shouldUseCockpitIndex(input.cockpitMessage)) {\n            findCockpitTsmCandidates(input)\n        } else {\n            emptyList()\n        }\n        val cockpitCompatible = if (cockpitCandidates.isNotEmpty() && input.maintenanceMessage.isNotBlank()) {\n            cockpitCandidates.filter { acceptableIndexMatch(it, input) && pfrPhotoMatchIsStrong(it, input) }\n        } else {\n            cockpitCandidates\n        }\n        val authoritativeTaskRef = TsmFirstEntryPolicy.uniqueTaskRef(cockpitCompatible.map { it.indexTaskRef })\n        val authoritativeIndex = authoritativeTaskRef?.let { taskRef ->\n            cockpitCompatible.filter { it.indexTaskRef.equals(taskRef, true) }.maxByOrNull { indexScore(it, input) }\n        }\n        val selectionRequired = cockpitCompatible.map { it.indexTaskRef.uppercase(Locale.US) }.distinct().size > 1\n        val exactCockpitExists = cockpitCandidates.isNotEmpty()\n        val indexRecord = authoritativeIndex ?: when {\n            selectionRequired -> null\n            exactCockpitExists -> null // fail closed instead of broad identifier/FTS fallback\n            !TsmFirstEntryPolicy.allowBroadFallback(cockpitCandidates.size) -> null\n            else -> findTsmIndexRecord(input)\n        }\n        val directTask = if (indexRecord == null && !selectionRequired && !exactCockpitExists) findDirectTsmTask(input) else null\n'''
if old not in text:
    raise SystemExit("PC6 resolve block not found")
text = text.replace(old, new, 1)

old = '''        val cmsMatches = matches.filter { it.hasFaultMessage }\n        return (if (cmsMatches.isNotEmpty()) cmsMatches else matches)\n            .sortedWith(compareBy<TsmIndexRecord> { it.maintenanceMessage }.thenBy { it.indexTaskRef })\n'''
new = '''        val exactCockpitMatches = matches.filter { normalizeText(query) == normalizeText(it.cockpitMessage) }\n        val preferred = if (exactCockpitMatches.isNotEmpty()) exactCockpitMatches else matches\n        val cmsMatches = preferred.filter { it.hasFaultMessage }\n        return (if (cmsMatches.isNotEmpty()) cmsMatches else preferred)\n            .sortedWith(compareBy<TsmIndexRecord> { it.maintenanceMessage }.thenBy { it.indexTaskRef })\n'''
if old not in text:
    raise SystemExit("PC6 cockpit candidate return block not found")
text = text.replace(old, new, 1)

old = '''            cockpitMessage = record.cockpitMessage,\n            cockpitAta = record.cockpitAta,\n'''
new = '''            cockpitMessage = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: record.cockpitMessage,\n            cockpitAta = input.cockpitAta?.takeIf { it.isNotBlank() } ?: record.cockpitAta,\n'''
if old not in text:
    raise SystemExit("PC6 authoritative cockpit output block not found")
text = text.replace(old, new, 1)
text = text.replace(
    "LINE ASSISTANT 1.4.5-PC5 • COMPLETE TSM BRANCH SOURCE PACKET",
    "LINE ASSISTANT 1.4.5-PC6 • AUTHORITATIVE ECAM + CMS TASK LOCK",
    1,
)
ENGINE.write_text(text)

gradle = GRADLE.read_text()
gradle = gradle.replace('versionCode = 50', 'versionCode = 51', 1)
gradle = gradle.replace('versionName = "1.4.5-PC5"', 'versionName = "1.4.5-PC6"', 1)
GRADLE.write_text(gradle)

expected_after = {
    ENGINE: "7c58c9f8739b017fb1cae16e75bd7990363bc856dcd5490a3369bc654273856b",
    GRADLE: "2bed173ea0fd7a913d8cb77a3a5eb1660f1b92a902a5313be4356f67bd49f1f5",
}
for path, expected in expected_after.items():
    actual = sha(path)
    if actual != expected:
        raise SystemExit(f"PC6 output hash mismatch {path}: {actual} != {expected}")

print("Line Assistant 1.4.5-PC6 authoritative ECAM/CMS task lock applied")
